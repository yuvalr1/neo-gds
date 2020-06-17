/*
 * Copyright (c) 2017-2020 "Neo4j,"
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
package org.neo4j.gds.core.pagecached;

import org.jetbrains.annotations.Nullable;
import org.neo4j.graphalgo.Orientation;
import org.neo4j.graphalgo.annotation.ValueClass;
import org.neo4j.graphalgo.api.Graph;
import org.neo4j.graphalgo.api.NodeMapping;
import org.neo4j.graphalgo.api.NodeProperties;
import org.neo4j.graphalgo.api.RelationshipConsumer;
import org.neo4j.graphalgo.api.RelationshipIntersect;
import org.neo4j.graphalgo.api.RelationshipWithPropertyConsumer;
import org.neo4j.graphalgo.core.utils.collection.primitive.PrimitiveLongIterable;
import org.neo4j.graphalgo.core.utils.collection.primitive.PrimitiveLongIterator;
import org.neo4j.graphalgo.core.utils.paged.AllocationTracker;

import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongPredicate;

/**
 * Graph implementation backed by PageCache
 */
public class PageCacheGraph implements Graph {

    private final IdMap idMapping;
    private final AllocationTracker tracker;

    private final Map<String, NodeProperties> nodeProperties;

    private final Orientation orientation;

    private final long relationshipCount;
    private AdjacencyList adjacencyList;
    private AdjacencyOffsets adjacencyOffsets;

    private final double defaultPropertyValue;

    private AdjacencyList.DecompressingCursor emptyCursor;
    private AdjacencyList.DecompressingCursor cursorCache;

    private boolean canRelease = true;

    private final boolean hasRelationshipProperty;

    private @Nullable AdjacencyList properties;
    private @Nullable AdjacencyOffsets propertyOffsets;

    public static PageCacheGraph create(
        IdMap nodes,
        Map<String, NodeProperties> nodeProperties,
        TopologyCSR topologyCSR,
        Optional<PropertyCSR> maybePropertyCSR,
        AllocationTracker tracker
    ) throws IOException {
        return new PageCacheGraph(
            nodes,
            nodeProperties,
            topologyCSR.elementCount(),
            topologyCSR.list(),
            topologyCSR.offsets(),
            maybePropertyCSR.isPresent(),
            maybePropertyCSR.map(PropertyCSR::defaultPropertyValue).orElse(Double.NaN),
            maybePropertyCSR.map(PropertyCSR::list).orElse(null),
            maybePropertyCSR.map(PropertyCSR::offsets).orElse(null),
            topologyCSR.orientation(),
            tracker
        );
    }

    public PageCacheGraph(
        IdMap idMapping,
        Map<String, NodeProperties> nodeProperties,
        long relationshipCount,
        AdjacencyList adjacencyList,
        AdjacencyOffsets adjacencyOffsets,
        boolean hasRelationshipProperty,
        double defaultPropertyValue,
        @Nullable AdjacencyList properties,
        @Nullable AdjacencyOffsets propertyOffsets,
        Orientation orientation,
        AllocationTracker tracker
    ) throws IOException {
        this.idMapping = idMapping;
        this.tracker = tracker;
        this.nodeProperties = nodeProperties;
        this.relationshipCount = relationshipCount;
        this.adjacencyList = adjacencyList;
        this.adjacencyOffsets = adjacencyOffsets;
        this.defaultPropertyValue = defaultPropertyValue;
        this.properties = properties;
        this.propertyOffsets = propertyOffsets;
        this.orientation = orientation;
        this.hasRelationshipProperty = hasRelationshipProperty;
        this.cursorCache = newAdjacencyCursor(this.adjacencyList);
        this.emptyCursor = newAdjacencyCursor(this.adjacencyList);
    }

    @Override
    public long nodeCount() {
        return idMapping.nodeCount();
    }

    public IdMap idMap() {
        return idMapping;
    }

    @Override
    public NodeMapping nodeMapping() {
        return idMapping;
    }

    @Override
    public long relationshipCount() {
        return relationshipCount;
    }

    @Override
    public Collection<PrimitiveLongIterable> batchIterables(int batchSize) {
        return idMapping.batchIterables(batchSize);
    }

    @Override
    public void forEachNode(LongPredicate consumer) {
        idMapping.forEachNode(consumer);
    }

    @Override
    public PrimitiveLongIterator nodeIterator() {
        return idMapping.nodeIterator();
    }

    @Override
    public double relationshipProperty(long sourceNodeId, long targetNodeId) {
        return relationshipProperty(sourceNodeId, targetNodeId, defaultPropertyValue);
    }

    @Override
    public double relationshipProperty(long sourceId, long targetId, double fallbackValue) {
        throw new UnsupportedOperationException();
    }

    @Override
    public NodeProperties nodeProperties(String propertyKey) {
        return nodeProperties.get(propertyKey);
    }

    @Override
    public Set<String> availableNodeProperties() {
        return nodeProperties.keySet();
    }

    @Override
    public void forEachRelationship(long nodeId, RelationshipConsumer consumer) {
        try {
            runForEach(nodeId, consumer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void forEachRelationship(long nodeId, double fallbackValue, RelationshipWithPropertyConsumer consumer) {
        try {
            runForEach(nodeId, fallbackValue, consumer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public int degree(long node) {
        if (adjacencyOffsets == null) {
            return 0;
        }
        long offset = 0;
        try {
            offset = adjacencyOffsets.get(node);
        } catch (IOException e) {
            e.printStackTrace();
        }
        if (offset == 0L) {
            return 0;
        }
        try {
            return adjacencyList.getDegree(offset);
        } catch (IOException e) {
            return -1;
        }
    }

    @Override
    public long toMappedNodeId(long nodeId) {
        return idMapping.toMappedNodeId(nodeId);
    }

    @Override
    public long toOriginalNodeId(long nodeId) {
        return idMapping.toOriginalNodeId(nodeId);
    }

    @Override
    public boolean contains(long nodeId) {
        return idMapping.contains(nodeId);
    }

    @Override
    public PageCacheGraph concurrentCopy() {
        try {
            return new PageCacheGraph(
                idMapping,
                nodeProperties,
                relationshipCount,
                adjacencyList,
                adjacencyOffsets,
                hasRelationshipProperty,
                defaultPropertyValue,
                properties,
                propertyOffsets,
                orientation,
                tracker
            );
        } catch (IOException e) {
            throw new IllegalStateException();
        }
    }

    @Override
    public RelationshipIntersect intersection(long maxDegree) {
        throw new UnsupportedOperationException();
    }

    /**
     * O(n) !
     */
    @Override
    public boolean exists(long sourceNodeId, long targetNodeId) {
        ExistsConsumer consumer = new ExistsConsumer(targetNodeId);
        try {
            runForEach(sourceNodeId, consumer);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return consumer.found;
    }

    /*
     * O(n) !
     */
    @Override
    public long getTarget(long sourceNodeId, long index) {
        GetTargetConsumer consumer = new GetTargetConsumer(index);
        try {
            runForEach(sourceNodeId, consumer);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return consumer.target;
    }

    private void runForEach(long sourceId, RelationshipConsumer consumer) throws IOException {
        AdjacencyList.DecompressingCursor adjacencyCursor = adjacencyCursorForIteration(sourceId);
        consumeAdjacentNodes(sourceId, adjacencyCursor, consumer);
    }

    private void runForEach(long sourceId, double fallbackValue, RelationshipWithPropertyConsumer consumer) throws
        IOException {
        if (!hasRelationshipProperty()) {
            runForEach(sourceId, (s, t) -> consumer.accept(s, t, fallbackValue));
        } else {
            AdjacencyList.DecompressingCursor adjacencyCursor = adjacencyCursorForIteration(sourceId);
            AdjacencyList.Cursor propertyCursor = propertyCursorForIteration(sourceId);
            consumeAdjacentNodesWithProperty(sourceId, adjacencyCursor, propertyCursor, consumer);
        }
    }

    private AdjacencyList.DecompressingCursor adjacencyCursorForIteration(long sourceNodeId) throws IOException {
        if (adjacencyOffsets == null) {
            throw new NullPointerException();
        }
        long offset = adjacencyOffsets.get(sourceNodeId);
        if (offset == 0L) {
            return emptyCursor;
        }
        return adjacencyList.decompressingCursor(cursorCache, offset);

    }

    private AdjacencyList.Cursor propertyCursorForIteration(long sourceNodeId) throws IOException {
        if (!hasRelationshipProperty()) {
            throw new UnsupportedOperationException(
                "Can not create property cursor on a graph without relationship property");
        }

        long offset = propertyOffsets.get(sourceNodeId);
        if (offset == 0L) {
            return AdjacencyList.Cursor.EMPTY;
        }
        return properties.cursor(offset);
    }

    @Override
    public void canRelease(boolean canRelease) {
        this.canRelease = canRelease;
    }

    @Override
    public void releaseTopology() {
        if (!canRelease) return;

        if (adjacencyList != null) {
            tracker.remove(adjacencyList.release());
            tracker.remove(adjacencyOffsets.release());
            adjacencyList = null;
            properties = null;
            adjacencyOffsets = null;
            propertyOffsets = null;
        }
        emptyCursor = null;
        cursorCache = null;
    }

    @Override
    public void releaseProperties() {
        if (canRelease) {
            for (NodeProperties nodeMapping : nodeProperties.values()) {
                tracker.remove(nodeMapping.release());
            }
        }
    }

    @Override
    public boolean isUndirected() {
        return orientation == Orientation.UNDIRECTED;
    }

    public Relationships relationships() {
        return Relationships.of(
            relationshipCount,
            orientation,
            adjacencyList,
            adjacencyOffsets,
            properties,
            propertyOffsets,
            defaultPropertyValue
        );
    }

    @Override
    public boolean hasRelationshipProperty() {
        return hasRelationshipProperty;
    }

    private AdjacencyList.DecompressingCursor newAdjacencyCursor(AdjacencyList adjacency) throws IOException {
        return adjacency != null ? adjacency.rawDecompressingCursor() : null;
    }

    private void consumeAdjacentNodes(
        long sourceId,
        AdjacencyList.DecompressingCursor adjacencyCursor,
        RelationshipConsumer consumer
    ) throws IOException {
        while (adjacencyCursor.hasNextVLong()) {
            if (!consumer.accept(sourceId, adjacencyCursor.nextVLong())) {
                break;
            }
        }
    }

    private void consumeAdjacentNodesWithProperty(
        long sourceId,
        AdjacencyList.DecompressingCursor adjacencyCursor,
        AdjacencyList.Cursor propertyCursor,
        RelationshipWithPropertyConsumer consumer
    ) throws IOException {

        while (adjacencyCursor.hasNextVLong()) {
            long targetId = adjacencyCursor.nextVLong();

            long propertyBits = propertyCursor.nextLong();
            double property = Double.longBitsToDouble(propertyBits);

            if (!consumer.accept(sourceId, targetId, property)) {
                break;
            }
        }
    }

    public static class GetTargetConsumer implements RelationshipConsumer {
        static final long TARGET_NOT_FOUND = -1L;

        private long count;
        public long target = TARGET_NOT_FOUND;

        GetTargetConsumer(long count) {
            this.count = count;
        }

        @Override
        public boolean accept(long s, long t) {
            if (count-- == 0) {
                target = t;
                return false;
            }
            return true;
        }
    }

    private static class ExistsConsumer implements RelationshipConsumer {
        private final long targetNodeId;
        private boolean found = false;

        ExistsConsumer(long targetNodeId) {
            this.targetNodeId = targetNodeId;
        }

        @Override
        public boolean accept(long s, long t) {
            if (t == targetNodeId) {
                found = true;
                return false;
            }
            return true;
        }
    }

    @ValueClass
    public interface Relationships {

        TopologyCSR topology();

        Optional<PropertyCSR> properties();

        static Relationships of(
            long relationshipCount,
            Orientation orientation,
            AdjacencyList adjacencyList,
            AdjacencyOffsets adjacencyOffsets,
            @Nullable AdjacencyList properties,
            @Nullable AdjacencyOffsets propertyOffsets,
            double defaultPropertyValue
        ) {
            TopologyCSR topologyCSR = ImmutableTopologyCSR.of(adjacencyList, adjacencyOffsets, relationshipCount, orientation);

            Optional<PropertyCSR> maybePropertyCSR = properties != null && propertyOffsets != null
                ? Optional.of(ImmutablePropertyCSR.of(
                    properties,
                    propertyOffsets,
                    relationshipCount,
                    orientation,
                    defaultPropertyValue
                )) : Optional.empty();

            return ImmutableRelationships.of(topologyCSR, maybePropertyCSR);
        }
    }

    @ValueClass
    public interface TopologyCSR {
        AdjacencyList list();

        AdjacencyOffsets offsets();

        long elementCount();

        Orientation orientation();
    }

    @ValueClass
    @SuppressWarnings("immutables:subtype")
    public interface PropertyCSR extends TopologyCSR {
        double defaultPropertyValue();
    }
}
