/*
 * Copyright (c) 2017-2021 "Neo4j,"
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
package org.neo4j.graphalgo.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.neo4j.graphalgo.PropertyMapping;
import org.neo4j.graphalgo.QueryRunner;
import org.neo4j.graphalgo.StoreLoaderBuilder;
import org.neo4j.graphalgo.TestDatabaseCreator;
import org.neo4j.graphalgo.api.Graph;
import org.neo4j.graphalgo.compat.GraphDbApi;
import org.neo4j.graphalgo.core.loading.NativeFactory;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class CypherExporterTest {

    private static GraphDbApi db;

    @BeforeEach
    void setup() {
        String createGraph =
                "CREATE (nA:Label1 { foo: 'bar' })\n" +
                "CREATE (nB:Label1 { property: 42.1337 })\n" +
                "CREATE (nC:Label2)\n" +
                "CREATE (nD)\n" +
                "CREATE\n" +
                "  (nA)-[:TYPE1 {bar: 'baz'}]->(nB),\n" +
                "  (nA)-[:TYPE1 {property:1337.42}]->(nC),\n" +
                "  (nB)-[:TYPE2]->(nC),\n" +
                "  (nC)-[:TYPE]->(nD)";

        db = TestDatabaseCreator.createTestDatabase();
        QueryRunner.runQuery(db, createGraph);
    }

    @AfterEach
    void tearDown() {
        db.shutdown();
    }

    @Test
    void testDumpByGraphDbApi() {
        StringWriter output = new StringWriter();
        CypherExporter.export(new PrintWriter(output), db);

        //language=Cypher
        String expected =
                "CREATE (n0:Label1 {foo:bar})\n" +
                "CREATE (n1:Label1 {property:42.1337})\n" +
                "CREATE (n2:Label2)\n" +
                "CREATE (n3)\n" +
                "CREATE\n" +
                "  (n0)-[:TYPE1 {property:1337.42}]->(n2),\n" +
                "  (n0)-[:TYPE1 {bar:baz}]->(n1),\n" +
                "  (n1)-[:TYPE2]->(n2),\n" +
                "  (n2)-[:TYPE]->(n3);";

        assertEquals(expected, output.toString().trim());
    }

    @Test
    void testDumpByGraphForHuge() {
        //language=Cypher
        String expected =
                "CREATE (n0 {property:42.0})\n" +
                "CREATE (n1 {property:42.1337})\n" +
                "CREATE (n2 {property:42.0})\n" +
                "CREATE (n3 {property:42.0})\n" +
                "CREATE\n" +
                "  (n0)-[ {weight:42.0}]->(n1),\n" +
                "  (n0)-[ {weight:1337.42}]->(n2),\n" +
                "  (n1)-[ {weight:42.0}]->(n2),\n" +
                "  (n2)-[ {weight:42.0}]->(n3);";

        String output = dumpGraph();
        assertEquals(expected, output);
    }

    private String dumpGraph() {
        Graph graph = new StoreLoaderBuilder()
            .api(db)
            .loadAnyLabel()
            .loadAnyRelationshipType()
            .addNodeProperty(PropertyMapping.of("property", 42))
            .addRelationshipProperty(PropertyMapping.of("property", 42))
            .build()
            .load(NativeFactory.class);


        StringWriter output = new StringWriter();
        CypherExporter.export(new PrintWriter(output), graph);
        return output.toString().trim();
    }
}
