/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.gds.projection;

import com.carrotsearch.hppc.DoubleArrayList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.neo4j.gds.BaseProc;
import org.neo4j.gds.NodeLabel;
import org.neo4j.gds.Orientation;
import org.neo4j.gds.RelationshipType;
import org.neo4j.gds.annotation.Configuration;
import org.neo4j.gds.annotation.ReturnType;
import org.neo4j.gds.annotation.ValueClass;
import org.neo4j.gds.api.DefaultValue;
import org.neo4j.gds.api.GraphStoreFactory;
import org.neo4j.gds.api.NodeProperties;
import org.neo4j.gds.api.PartialIdMap;
import org.neo4j.gds.api.nodeproperties.ValueType;
import org.neo4j.gds.api.schema.NodeSchema;
import org.neo4j.gds.api.schema.RelationshipPropertySchema;
import org.neo4j.gds.config.GraphProjectConfig;
import org.neo4j.gds.core.Aggregation;
import org.neo4j.gds.core.loading.CSRGraphStoreUtil;
import org.neo4j.gds.core.loading.CatalogRequest;
import org.neo4j.gds.core.loading.GraphStoreBuilder;
import org.neo4j.gds.core.loading.GraphStoreCatalog;
import org.neo4j.gds.core.loading.ImmutableCatalogRequest;
import org.neo4j.gds.core.loading.ReadHelper;
import org.neo4j.gds.core.loading.ValueConverter;
import org.neo4j.gds.core.loading.construction.GraphFactory;
import org.neo4j.gds.core.loading.construction.NodesBuilder;
import org.neo4j.gds.core.loading.construction.RelationshipsBuilder;
import org.neo4j.gds.core.utils.ProgressTimer;
import org.neo4j.gds.core.utils.mem.AllocationTracker;
import org.neo4j.graphdb.Node;
import org.neo4j.kernel.database.NamedDatabaseId;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Name;
import org.neo4j.procedure.UserAggregationFunction;
import org.neo4j.procedure.UserAggregationResult;
import org.neo4j.procedure.UserAggregationUpdate;
import org.neo4j.values.storable.Value;
import org.neo4j.values.storable.Values;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.stream.StreamSupport;

import static org.neo4j.gds.utils.StringFormatting.formatWithLocale;

public final class CypherAggregation extends BaseProc {

    @UserAggregationFunction(name = "gds.alpha.graph")
    @Description("Creates a named graph in the catalog for use by algorithms.")
    public GraphAggregator projectFromCypherAggregation() {

        var progressTimer = ProgressTimer.start();
        var catalogRequest = ImmutableCatalogRequest.of(
            databaseId().name(),
            username(),
            Optional.empty(),
            isGdsAdmin()
        );

        return new GraphAggregator(
            progressTimer,
            catalogRequest,
            this.api.databaseId(),
            username(),
            this.allocationTracker
        );
    }

    public static class GraphAggregator {

        private final ProgressTimer progressTimer;
        private final CatalogRequest catalogRequest;
        private final NamedDatabaseId databaseId;
        private final String username;
        private final AllocationTracker allocationTracker;

        private @Nullable String graphName;
        private @Nullable RelationshipsBuilder relImporter;
        private @Nullable LazyIdMapBuilder idMapBuilder;
        private final List<RelationshipPropertySchema> relationshipPropertySchemas;

        GraphAggregator(
            ProgressTimer progressTimer,
            CatalogRequest catalogRequest,
            NamedDatabaseId databaseId,
            String username,
            AllocationTracker allocationTracker
        ) {
            this.progressTimer = progressTimer;
            this.catalogRequest = catalogRequest;
            this.databaseId = databaseId;
            this.username = username;
            this.allocationTracker = allocationTracker;
            this.relationshipPropertySchemas = new ArrayList<>();
        }

        @UserAggregationUpdate
        public void update(
            @Name("graphName") String graphName,
            @Name("sourceNode") Node sourceNode,
            @Nullable @Name(value = "targetNode", defaultValue = "null") Node targetNode,
            @Nullable @Name(value = "nodesConfig", defaultValue = "null") Map<String, Object> nodesConfig,
            @Nullable @Name(value = "relationshipProperties", defaultValue = "null") Map<String, Object> relationshipProperties
        ) {

            if (this.graphName == null) {
                validateGraphName(graphName);
                this.graphName = graphName;
            }

            Map<String, Value> sourceNodePropertyValues = null;
            Map<String, Value> targetNodePropertyValues = null;
            NodeLabel[] sourceNodeLabels = null;
            NodeLabel[] targetNodeLabels = null;

            if (nodesConfig != null) {
                sourceNodePropertyValues = propertiesConfig("sourceNodeProperties", nodesConfig);
                sourceNodeLabels = labelsConfig(sourceNode, "sourceNodeLabels", nodesConfig);

                if (targetNode != null) {
                    targetNodePropertyValues = propertiesConfig("targetNodeProperties", nodesConfig);
                    targetNodeLabels = labelsConfig(targetNode, "targetNodeLabels", nodesConfig);
                }
            }

            if (this.idMapBuilder == null) {
                this.idMapBuilder = new LazyIdMapBuilder(this.allocationTracker);
            }

            if (this.relImporter == null) {
                this.relImporter = newRelImporter(relationshipProperties);
            }

            var sourceNodeId = loadNode(sourceNode, sourceNodeLabels, sourceNodePropertyValues);

            if (targetNode != null) {
                var targetNodeId = loadNode(targetNode, targetNodeLabels, targetNodePropertyValues);

                if (relationshipProperties != null) {
                    if (relationshipProperties.size() == 1) {
                        double propertyValue = loadOneRelationshipProperty(relationshipProperties);
                        this.relImporter.addFromInternal(sourceNodeId, targetNodeId, propertyValue);
                    } else {
                        var propertyValues = loadMultipleRelationshipProperties(relationshipProperties);
                        this.relImporter.addFromInternal(sourceNodeId, targetNodeId, propertyValues);
                    }
                } else {
                    this.relImporter.addFromInternal(sourceNodeId, targetNodeId);
                }
            }
        }

        @Nullable
        private Map<String, Value> propertiesConfig(
            String nodePropertyKey,
            @NotNull Map<String, Object> nodesConfig
        ) {
            var nodeProperties = nodesConfig.get(nodePropertyKey);
            if (nodeProperties == null || nodeProperties instanceof Map) {
                return objectsToValues((Map<String, Object>) nodeProperties);
            }
            throw new IllegalArgumentException(formatWithLocale(
                "The value of `%s` must be a `Map of Property Values`, but was `%s`.",
                nodePropertyKey,
                nodeProperties.getClass().getSimpleName()
            ));
        }

        private @Nullable NodeLabel[] labelsConfig(
            Node node,
            String nodeLabelKey,
            @NotNull Map<String, Object> nodesConfig
        ) {
            var nodeLabelsEntry = nodesConfig.get(nodeLabelKey);
            if (nodeLabelsEntry instanceof List) {
                return ((List<?>) nodeLabelsEntry)
                    .stream()
                    .map(label -> NodeLabel.of(String.valueOf(label)))
                    .toArray(NodeLabel[]::new);
            }
            if (nodeLabelsEntry instanceof String) {
                return new NodeLabel[]{NodeLabel.of((String) nodeLabelsEntry)};
            }
            if (Boolean.TRUE.equals(nodeLabelsEntry)) {
                return StreamSupport.stream(node.getLabels().spliterator(), false)
                    .map(label -> new NodeLabel(label.name()))
                    .toArray(NodeLabel[]::new);
            }
            if (nodeLabelsEntry == null || Boolean.FALSE.equals(nodeLabelsEntry)) {
                return null;
            }
            throw new IllegalArgumentException(formatWithLocale(
                "The value of `%s` must be either a `List of Strings`, a `String`, or a `Boolean`, but was `%s`.",
                nodeLabelKey,
                nodeLabelsEntry.getClass().getSimpleName()
            ));
        }

        private void validateGraphName(String graphName) {
            if (GraphStoreCatalog.exists(this.username, this.databaseId, graphName)) {
                throw new IllegalArgumentException("Graph " + graphName + " already exists");
            }
        }

        private RelationshipsBuilder newRelImporter(@Nullable Map<String, Object> relationshipProperties) {
            assert this.idMapBuilder != null;

            var relationshipsBuilderBuilder = GraphFactory.initRelationshipsBuilder()
                .nodes(this.idMapBuilder)
                .orientation(Orientation.NATURAL)
                .aggregation(Aggregation.NONE)
                // TODO: concurrency from config
                .concurrency(4)
                .allocationTracker(this.allocationTracker);

            if (relationshipProperties != null) {
                relationshipProperties.forEach((propertyKey, propertyValue) -> {
                    relationshipsBuilderBuilder.addPropertyConfig(
                        Aggregation.NONE,
                        DefaultValue.forDouble()
                    );
                    relationshipPropertySchemas.add(RelationshipPropertySchema.of(propertyKey, ValueType.DOUBLE));
                });
            }
            return relationshipsBuilderBuilder.build();
        }

        private static @Nullable Map<String, Value> objectsToValues(@Nullable Map<String, Object> properties) {
            if (properties == null) {
                return null;
            }
            var values = new HashMap<String, Value>(properties.size());
            properties.forEach((key, valueObject) -> {
                if (valueObject != null) {
                    var value = ValueConverter.toValue(valueObject);
                    values.put(key, value);
                }
            });
            return values;
        }

        private long loadNode(Node node, @Nullable NodeLabel [] nodeLabels, @Nullable Map<String, Value> nodeProperties) {
            return (nodeProperties == null)
                ? this.idMapBuilder.addNode(node.getId(), nodeLabels)
                : this.idMapBuilder.addNodeWithProperties(node.getId(), nodeProperties, nodeLabels);
        }

        private double loadOneRelationshipProperty(@NotNull Map<String, Object> relationshipProperties) {
            var propertyValueObject = relationshipProperties.values().iterator().next();
            var propertyValue = propertyValueObject != null ? ValueConverter.toValue(propertyValueObject) : Values.NO_VALUE;
            return ReadHelper.extractValue(propertyValue, DefaultValue.DOUBLE_DEFAULT_FALLBACK);
        }

        private double[] loadMultipleRelationshipProperties(@NotNull Map<String, Object> relationshipProperties) {
            var propertyValues = new DoubleArrayList(relationshipProperties.size());
            relationshipProperties.forEach((propertyKey, propertyValueObject) -> {
                var propertyValue = ReadHelper.extractValue(
                    ValueConverter.toValue(propertyValueObject),
                    DefaultValue.DOUBLE_DEFAULT_FALLBACK
                );
                propertyValues.add(propertyValue);
            });
            return propertyValues.toArray();
        }

        // TODO: generate some code for the ReturnType annotation to convert from an instance of its type
        //  to a Map<String, Object> (similar to toMap in configuration)
        @UserAggregationResult
        @ReturnType(AggregationResult.class)
        public Map<String, Object> result() {

            var graphName = this.graphName;

            if (graphName == null) {
                return Map.of(
                    "graphName", "<no data was projected>",
                    "nodeCount", 0,
                    "relationshipCount", 0,
                    "projectMillis", this.progressTimer.stop().getDuration()
                );
            }

            if (GraphStoreCatalog.exists(this.username, this.databaseId, graphName)) {
                var graphStore = GraphStoreCatalog.get(this.catalogRequest, graphName).graphStore();
                return Map.of(
                    "graphName", graphName,
                    "nodeCount", graphStore.nodeCount(),
                    "relationshipCount", graphStore.relationshipCount(),
                    "projectMillis", this.progressTimer.stop().getDuration()
                );
            }

            var graphStoreBuilder = new GraphStoreBuilder()
                .allocationTracker(allocationTracker)
                .concurrency(4)
                .databaseId(databaseId);

            var nodes = buildNodesWithProperties(graphStoreBuilder);
            buildRelationshipsWithProperties(graphStoreBuilder, nodes);

            var graphStore = graphStoreBuilder.build();

            var config = ImmutableGraphProjectFromCypherAggregation.builder()
                .graphName(graphName)
                .username(this.username)
                .build();

            GraphStoreCatalog.set(config, graphStore);

            var projectMillis = this.progressTimer.stop().getDuration();

            return Map.of(
                "graphName", graphName,
                "nodeCount", graphStore.nodeCount(),
                "relationshipCount", graphStore.relationshipCount(),
                "projectMillis", projectMillis
            );
        }

        private PartialIdMap buildNodesWithProperties(GraphStoreBuilder graphStoreBuilder) {
            assert this.idMapBuilder != null;

            var idMapAndProperties = this.idMapBuilder.build();
            var nodes = idMapAndProperties.idMap();
            var maybeNodeProperties = idMapAndProperties.nodeProperties();

            graphStoreBuilder.nodes(nodes);

            var nodeSchema = maybeNodeProperties
                .map(GraphAggregator::nodeSchemaWithProperties)
                .orElseGet(() -> nodeSchemaWithoutProperties(nodes.availableNodeLabels()));

            maybeNodeProperties.ifPresent(allNodeProperties -> {
                CSRGraphStoreUtil.extractNodeProperties(
                    graphStoreBuilder,
                    nodeSchema.properties()::get,
                    allNodeProperties
                );
            });
            return nodes;
        }

        private void buildRelationshipsWithProperties(GraphStoreBuilder graphStoreBuilder, PartialIdMap nodes) {
            assert this.relImporter != null;

            var allRelationships = this.relImporter.buildAll(Optional.of(nodes::toMappedNodeId));
            var propertyStore = CSRGraphStoreUtil.buildRelationshipPropertyStore(
                allRelationships,
                this.relationshipPropertySchemas
            );

            graphStoreBuilder.putRelationships(RelationshipType.ALL_RELATIONSHIPS, allRelationships.get(0).topology());
            graphStoreBuilder.putRelationshipPropertyStores(RelationshipType.ALL_RELATIONSHIPS, propertyStore);
        }

        private static NodeSchema nodeSchemaWithProperties(Map<NodeLabel, Map<String, NodeProperties>> nodeSchemaMap) {
            var nodeSchemaBuilder = NodeSchema.builder();

            nodeSchemaMap.forEach((nodeLabel, propertyMap) -> {
                propertyMap.forEach((propertyName, nodeProperties) -> {
                    nodeSchemaBuilder.addProperty(
                        nodeLabel,
                        propertyName,
                        nodeProperties.valueType()
                    );
                });
            });

            return nodeSchemaBuilder.build();
        }

        private static NodeSchema nodeSchemaWithoutProperties(Set<NodeLabel> nodeLabels) {
            var nodeSchemaBuilder = NodeSchema.builder();
            nodeLabels.forEach(nodeSchemaBuilder::addLabel);
            return nodeSchemaBuilder.build();
        }
    }

    @ValueClass
    @SuppressWarnings("immutables:subtype")
    public interface GraphProjectFromCypherAggregation extends GraphProjectConfig {

        @org.immutables.value.Value.Default
        default Orientation orientation() {
            return Orientation.NATURAL;
        }

        @org.immutables.value.Value.Default
        default Aggregation aggregation() {
            return Aggregation.NONE;
        }

        @Override
        default GraphStoreFactory.Supplier graphStoreFactory() {
            throw new UnsupportedOperationException(
                "Cypher aggregation does not work over the default graph store framework"
            );
        }

        @Override
        @Configuration.Ignore
        default <R> R accept(GraphProjectConfig.Cases<R> cases) {
            if (cases instanceof Cases) {
                return ((Cases<R>) cases).cypherAggregation(this);
            }
            return null;
        }

        interface Cases<R> extends GraphProjectConfig.Cases<R> {

            R cypherAggregation(GraphProjectFromCypherAggregation cypherAggregationConfig);
        }
    }

    public static final class AggregationResult {
        public String graphName;
        public long nodeCount;
        public long relationshipCount;
        public long projectMillis;
    }
}

final class LazyIdMapBuilder implements PartialIdMap {
    private final NodesBuilder nodesBuilder;

    LazyIdMapBuilder(AllocationTracker allocationTracker) {
        this.nodesBuilder = GraphFactory.initNodesBuilder()
            .maxOriginalId(NodesBuilder.UNKNOWN_MAX_ID)
            .hasLabelInformation(true)
            .hasProperties(true)
            .deduplicateIds(true)
            .allocationTracker(allocationTracker)
            .build();
    }

    long addNode(long nodeId, @Nullable NodeLabel[] labels) {
        return addNodeWithProperties(nodeId, Map.of(), labels);
    }

    long addNodeWithProperties(
        long nodeId,
        Map<String, Value> properties,
        @Nullable NodeLabel[] nodeLabels
    ) {

        if (properties.isEmpty()) {
            this.nodesBuilder.addNode(nodeId, nodeLabels);
        } else {
            this.nodesBuilder.addNode(nodeId, properties, nodeLabels);
        }
        return nodeId;
    }

    @Override
    public long toMappedNodeId(long nodeId) {
        return this.addNode(nodeId, null);
    }

    @Override
    public OptionalLong rootNodeCount() {
        return OptionalLong.of(this.nodesBuilder.importedNodes());
    }

    NodesBuilder.IdMapAndProperties build() {
        return this.nodesBuilder.build();
    }
}
