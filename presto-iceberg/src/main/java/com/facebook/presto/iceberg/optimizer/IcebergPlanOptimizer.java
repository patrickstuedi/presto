/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.iceberg.optimizer;

import com.facebook.presto.common.Subfield;
import com.facebook.presto.common.predicate.TupleDomain;
import com.facebook.presto.hive.SubfieldExtractor;
import com.facebook.presto.iceberg.IcebergAbstractMetadata;
import com.facebook.presto.iceberg.IcebergColumnHandle;
import com.facebook.presto.iceberg.IcebergTableHandle;
import com.facebook.presto.iceberg.IcebergTransactionManager;
import com.facebook.presto.spi.ColumnHandle;
import com.facebook.presto.spi.ConnectorPlanOptimizer;
import com.facebook.presto.spi.ConnectorPlanRewriter;
import com.facebook.presto.spi.ConnectorSession;
import com.facebook.presto.spi.TableHandle;
import com.facebook.presto.spi.VariableAllocator;
import com.facebook.presto.spi.function.StandardFunctionResolution;
import com.facebook.presto.spi.plan.FilterNode;
import com.facebook.presto.spi.plan.PlanNode;
import com.facebook.presto.spi.plan.PlanNodeIdAllocator;
import com.facebook.presto.spi.plan.TableScanNode;
import com.facebook.presto.spi.relation.DomainTranslator;
import com.facebook.presto.spi.relation.RowExpression;
import com.facebook.presto.spi.relation.RowExpressionService;
import org.apache.iceberg.PartitionField;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Table;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.facebook.presto.expressions.LogicalRowExpressions.TRUE_CONSTANT;
import static com.facebook.presto.iceberg.IcebergSessionProperties.isPushdownFilterEnabled;
import static com.facebook.presto.iceberg.IcebergUtil.getIcebergTable;
import static com.facebook.presto.spi.ConnectorPlanRewriter.rewriteWith;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.Objects.requireNonNull;

public class IcebergPlanOptimizer
        implements ConnectorPlanOptimizer
{
    private final RowExpressionService rowExpressionService;
    private final StandardFunctionResolution functionResolution;
    private final IcebergTransactionManager transactionManager;

    IcebergPlanOptimizer(StandardFunctionResolution functionResolution,
                         RowExpressionService rowExpressionService,
                         IcebergTransactionManager transactionManager)
    {
        this.functionResolution = requireNonNull(functionResolution, "functionResolution is null");
        this.rowExpressionService = requireNonNull(rowExpressionService, "rowExpressionService is null");
        this.transactionManager = requireNonNull(transactionManager, "transactionManager is null");
    }

    @Override
    public PlanNode optimize(PlanNode maxSubplan, ConnectorSession session, VariableAllocator variableAllocator, PlanNodeIdAllocator idAllocator)
    {
        if (isPushdownFilterEnabled(session)) {
            return maxSubplan;
        }
        return rewriteWith(new FilterPushdownRewriter(functionResolution, rowExpressionService,
                transactionManager, idAllocator, session), maxSubplan);
    }

    private static class FilterPushdownRewriter
            extends ConnectorPlanRewriter<Void>
    {
        private final ConnectorSession session;
        private final RowExpressionService rowExpressionService;
        private final StandardFunctionResolution functionResolution;
        private final PlanNodeIdAllocator idAllocator;
        private final IcebergTransactionManager transactionManager;

        public FilterPushdownRewriter(
                StandardFunctionResolution functionResolution,
                RowExpressionService rowExpressionService,
                IcebergTransactionManager transactionManager,
                PlanNodeIdAllocator idAllocator,
                ConnectorSession session)
        {
            this.functionResolution = functionResolution;
            this.rowExpressionService = rowExpressionService;
            this.transactionManager = transactionManager;
            this.idAllocator = idAllocator;
            this.session = session;
        }

        @Override
        public PlanNode visitFilter(FilterNode filter, RewriteContext<Void> context)
        {
            if (!(filter.getSource() instanceof TableScanNode)) {
                return visitPlan(filter, context);
            }

            TableScanNode tableScan = (TableScanNode) filter.getSource();

            Map<String, IcebergColumnHandle> nameToColumnHandlesMapping = tableScan.getAssignments().entrySet().stream()
                    .collect(Collectors.toMap(e -> e.getKey().getName(), e -> (IcebergColumnHandle) e.getValue()));

            RowExpression filterPredicate = filter.getPredicate();

            //TODO we should optimize the filter expression
            DomainTranslator.ExtractionResult<Subfield> decomposedFilter = rowExpressionService.getDomainTranslator()
                    .fromPredicate(session, filterPredicate, new SubfieldExtractor(functionResolution, rowExpressionService.getExpressionOptimizer(), session).toColumnExtractor());

            // Only pushdown the range filters which apply to entire columns, because iceberg does not accept the filters on the subfields in nested structures
            TupleDomain<IcebergColumnHandle> entireColumnDomain = decomposedFilter.getTupleDomain()
                    .transform(subfield -> subfield.getPath().isEmpty() ? subfield.getRootName() : null)
                    .transform(nameToColumnHandlesMapping::get);
            boolean hasSubfieldsInNestedStructures = decomposedFilter.getTupleDomain().getDomains()
                    .map(map -> map.keySet().stream().anyMatch(subfield -> !subfield.getPath().isEmpty()))
                    .orElse(false);

            // Simplify call is required because iceberg does not support a large value list for IN predicate
            TupleDomain<IcebergColumnHandle> simplifiedColumnDomain = entireColumnDomain.simplify();

            TableHandle handle = tableScan.getTable();
            IcebergTableHandle oldTableHandle = (IcebergTableHandle) handle.getConnectorHandle();
            IcebergTableHandle newTableHandle = new IcebergTableHandle(
                    oldTableHandle.getSchemaName(),
                    oldTableHandle.getIcebergTableName(),
                    oldTableHandle.isSnapshotSpecified(),
                    simplifiedColumnDomain,
                    oldTableHandle.getTableSchemaJson());
            TableScanNode newTableScan = new TableScanNode(
                    tableScan.getSourceLocation(),
                    tableScan.getId(),
                    new TableHandle(handle.getConnectorId(), newTableHandle, handle.getTransaction(), handle.getLayout()),
                    tableScan.getOutputVariables(),
                    tableScan.getAssignments(),
                    tableScan.getCurrentConstraint(),
                    TupleDomain.all());

            if (TRUE_CONSTANT.equals(filterPredicate)) {
                return newTableScan;
            }

            if (TRUE_CONSTANT.equals(decomposedFilter.getRemainingExpression()) && !hasSubfieldsInNestedStructures && simplifiedColumnDomain.equals(entireColumnDomain)) {
                Set<Integer> predicateColumnIds = simplifiedColumnDomain.getDomains().get().keySet().stream()
                        .map(IcebergColumnHandle::getId)
                        .collect(toImmutableSet());

                IcebergTableHandle tableHandle = (IcebergTableHandle) handle.getConnectorHandle();
                IcebergAbstractMetadata metadata = (IcebergAbstractMetadata) transactionManager.get(handle.getTransaction());
                Table icebergTable = getIcebergTable(metadata, session, tableHandle.getSchemaTableName());

                // check iceberg table's every partition specs, to make sure the filterPredicate could be enforced
                boolean canEnforced = true;
                for (PartitionSpec spec : icebergTable.specs().values()) {
                    // Currently we do not support delete when any partition columns in predicate is not transform by identity()
                    Set<Integer> partitionColumnSourceIds = spec.fields().stream()
                            .filter(field -> field.transform().isIdentity())
                            .map(PartitionField::sourceId).collect(Collectors.toSet());

                    if (!partitionColumnSourceIds.containsAll(predicateColumnIds)) {
                        canEnforced = false;
                        break;
                    }
                }

                if (canEnforced) {
                    return new TableScanNode(
                            newTableScan.getSourceLocation(),
                            newTableScan.getId(),
                            newTableScan.getTable(),
                            newTableScan.getOutputVariables(),
                            newTableScan.getAssignments(),
                            newTableScan.getCurrentConstraint(),
                            simplifiedColumnDomain.transform(icebergColumnHandle -> (ColumnHandle) icebergColumnHandle));
                }
            }
            return new FilterNode(filter.getSourceLocation(), idAllocator.getNextId(), newTableScan, filterPredicate);
        }
    }
}
