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
package org.neo4j.gds.conductance;

import org.neo4j.gds.AlgorithmFactory;
import org.neo4j.gds.impl.conductance.Conductance;
import org.neo4j.gds.impl.conductance.ConductanceConfig;
import org.neo4j.gds.impl.conductance.ConductanceFactory;

public final class ConductanceProc {

    static final String CONDUCTANCE_DESCRIPTION = "Evaluates a division of nodes into communities based on if relationships cross community boundaries or not.";

    private ConductanceProc() {}

    static <CONFIG extends ConductanceConfig> AlgorithmFactory<Conductance, CONFIG> algorithmFactory() {
        return new ConductanceFactory<>();
    }
}
