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
package org.neo4j.gds.leiden;

import org.neo4j.gds.result.AbstractCommunityResultBuilder;
import org.neo4j.gds.results.StandardStatsResult;
import org.neo4j.internal.kernel.api.procs.ProcedureCallContext;

import java.util.Map;

public class StatsResult extends StandardStatsResult {
    public final long ranLevels;
    public final boolean didConverge;
    public final long nodeCount;
    public final long communityCount;
    public final Map<String, Object> communityDistribution;

    StatsResult(
        long ranLevels,
        boolean didConverge,
        long nodeCount,
        long communityCount,
        Map<String, Object> communityDistribution,
        long preProcessingMillis,
        long computeMillis,
        long postProcessingMillis,
        Map<String, Object> configuration
    ) {
        super(preProcessingMillis, computeMillis, postProcessingMillis, configuration);
        this.ranLevels = ranLevels;
        this.didConverge = didConverge;
        this.nodeCount = nodeCount;
        this.communityCount = communityCount;
        this.communityDistribution = communityDistribution;
    }

    static class StatsBuilder extends AbstractCommunityResultBuilder<StatsResult> {

        long levels = -1;
        boolean didConverge = false;

        StatsBuilder(ProcedureCallContext context, int concurrency) {
            super(context, concurrency);
        }

        StatsBuilder withLevels(long levels) {
            this.levels = levels;
            return this;
        }

        StatsBuilder withDidConverge(boolean didConverge) {
            this.didConverge = didConverge;
            return this;
        }

        @Override
        protected StatsResult buildResult() {
            return new StatsResult(
                levels,
                didConverge,
                nodeCount,
                maybeCommunityCount.orElse(0L),
                communityHistogramOrNull(),
                preProcessingMillis,
                computeMillis,
                postProcessingDuration,
                config.toMap()
            );
        }
    }

}
