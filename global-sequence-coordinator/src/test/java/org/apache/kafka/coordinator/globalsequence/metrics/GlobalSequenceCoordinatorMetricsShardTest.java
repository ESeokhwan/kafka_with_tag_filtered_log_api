/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.coordinator.globalsequence.metrics;

import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.metrics.Metrics;
import org.apache.kafka.common.utils.LogContext;
import org.apache.kafka.timeline.SnapshotRegistry;

import com.yammer.metrics.core.MetricsRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlobalSequenceCoordinatorMetricsShardTest {

    @Test
    void testPublishesCommittedRetainedAllocationCount() {
        SnapshotRegistry snapshotRegistry = new SnapshotRegistry(new LogContext());
        TopicPartition topicPartition = new TopicPartition("__global_sequence_index", 0);

        try (Metrics metrics = new Metrics();
             GlobalSequenceCoordinatorMetrics coordinatorMetrics =
                 new GlobalSequenceCoordinatorMetrics(new MetricsRegistry(), metrics)) {
            GlobalSequenceCoordinatorMetricsShard shard = coordinatorMetrics.newMetricsShard(
                snapshotRegistry,
                topicPartition
            );
            coordinatorMetrics.activateMetricsShard(shard);

            shard.incrementRetainedAllocations();
            snapshotRegistry.idempotentCreateSnapshot(10L);
            shard.incrementRetainedAllocations();

            coordinatorMetrics.onUpdateLastCommittedOffset(topicPartition, 10L);
            assertEquals(1L, coordinatorMetrics.numRetainedAllocations());

            coordinatorMetrics.onUpdateLastCommittedOffset(topicPartition, SnapshotRegistry.LATEST_EPOCH);
            assertEquals(2L, coordinatorMetrics.numRetainedAllocations());

            coordinatorMetrics.deactivateMetricsShard(shard);
            assertEquals(0L, coordinatorMetrics.numRetainedAllocations());
        }
    }
}
