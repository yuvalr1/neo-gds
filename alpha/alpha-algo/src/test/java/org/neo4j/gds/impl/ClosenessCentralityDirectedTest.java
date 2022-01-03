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
package org.neo4j.gds.impl;

import org.junit.jupiter.api.Test;
import org.neo4j.gds.api.Graph;
import org.neo4j.gds.config.ConcurrencyConfig;
import org.neo4j.gds.core.concurrency.Pools;
import org.neo4j.gds.core.utils.mem.AllocationTracker;
import org.neo4j.gds.core.utils.progress.tasks.ProgressTracker;
import org.neo4j.gds.extension.GdlExtension;
import org.neo4j.gds.extension.GdlGraph;
import org.neo4j.gds.extension.Inject;
import org.neo4j.gds.impl.closeness.MSClosenessCentrality;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * Graph:
 *
 * (A) <->  (B)  <- (C)
 *
 * Calculation:
 *
 * d(A,B)=1
 * d(C,B)=1  farness(B)=2  component(B)=2  CC(B)=1
 * d(B,A)=1
 * d(C,A)=2  farness(A)= 3 component(A)=2 CC(A)=2/3
 *
 * d(A,C)=inf
 * d(B,C)=inf farness(C)=inf, comp(C)=0  CC(C)=0
 */
@GdlExtension
class ClosenessCentralityDirectedTest {

    @GdlGraph
    private static final String DB_CYPHER =
        "CREATE " +
        "  (a:Node)" +
        ", (b:Node)" +
        ", (c:Node)" +

        ", (a)-[:TYPE]->(b)" +
        ", (b)-[:TYPE]->(a)" +
        ", (c)-[:TYPE]->(b)";

    private static final double[] EXPECTED = new double[]{2 / 3.0, 1, 0};

    @Inject
    private Graph graph;

    @Test
    void testGetCentrality() {
        MSClosenessCentrality algo = new MSClosenessCentrality(
            graph,
            ConcurrencyConfig.DEFAULT_CONCURRENCY,
            false,
            AllocationTracker.empty(),
            Pools.DEFAULT,
            ProgressTracker.NULL_TRACKER
        );
        algo.compute();
        final double[] centrality = algo.exportToArray();

        assertArrayEquals(EXPECTED, centrality, 0.1);
    }

    @Test
    void testStream() {
        final double[] centrality = new double[(int) graph.nodeCount()];

        MSClosenessCentrality algo = new MSClosenessCentrality(
            graph,
            ConcurrencyConfig.DEFAULT_CONCURRENCY,
            false,
            AllocationTracker.empty(),
            Pools.DEFAULT,
            ProgressTracker.NULL_TRACKER
        );
        algo.compute();
        algo.resultStream()
            .forEach(r -> centrality[Math.toIntExact(graph.toMappedNodeId(r.nodeId))] = r.centrality);

        assertArrayEquals(EXPECTED, centrality, 0.1);
    }
}
