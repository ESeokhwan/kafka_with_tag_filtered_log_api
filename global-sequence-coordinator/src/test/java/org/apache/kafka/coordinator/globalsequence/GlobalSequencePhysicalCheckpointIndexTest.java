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
package org.apache.kafka.coordinator.globalsequence;

import org.apache.kafka.common.Uuid;
import org.apache.kafka.common.utils.LogContext;
import org.apache.kafka.timeline.SnapshotRegistry;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlobalSequencePhysicalCheckpointIndexTest {
    private static final Uuid TOPIC_ID = Uuid.randomUuid();

    @Test
    void testRetainsLeveledCheckpointsPerPhysicalPartition() {
        GlobalSequencePhysicalCheckpointIndex index = new GlobalSequencePhysicalCheckpointIndex(
            new SnapshotRegistry(new LogContext()),
            4,
            2
        );
        for (int ordinal = 0; ordinal < 21; ordinal++) {
            index.replayAllocation(TOPIC_ID, 0, ordinal * 10L, ordinal * 100L);
            index.replayAllocation(TOPIC_ID, 1, ordinal * 20L, ordinal * 100L + 1L);
        }

        assertEquals(
            List.of(
                checkpoint(0),
                checkpoint(8),
                checkpoint(16),
                checkpoint(20)
            ),
            index.checkpoints(TOPIC_ID, 0, SnapshotRegistry.LATEST_EPOCH)
        );
        assertEquals(
            checkpoint(8),
            index.floor(TOPIC_ID, 0, 155L, SnapshotRegistry.LATEST_EPOCH).orElseThrow()
        );
        assertEquals(4, index.numCheckpoints(TOPIC_ID, 1, SnapshotRegistry.LATEST_EPOCH));
    }

    @Test
    void testRemoveTopicClearsEveryPhysicalPartition() {
        GlobalSequencePhysicalCheckpointIndex index = new GlobalSequencePhysicalCheckpointIndex(
            new SnapshotRegistry(new LogContext()),
            1,
            2
        );
        Uuid otherTopicId = Uuid.randomUuid();
        index.replayAllocation(TOPIC_ID, 0, 10L, 100L);
        index.replayAllocation(TOPIC_ID, 1, 20L, 101L);
        index.replayAllocation(otherTopicId, 0, 10L, 102L);

        index.removeTopic(TOPIC_ID);

        assertEquals(0, index.numCheckpoints(TOPIC_ID, 0, SnapshotRegistry.LATEST_EPOCH));
        assertEquals(0, index.numCheckpoints(TOPIC_ID, 1, SnapshotRegistry.LATEST_EPOCH));
        assertEquals(1, index.numCheckpoints(otherTopicId, 0, SnapshotRegistry.LATEST_EPOCH));
    }

    private static GlobalSequencePhysicalCheckpoint checkpoint(long ordinal) {
        return new GlobalSequencePhysicalCheckpoint(ordinal, ordinal * 10L, ordinal * 100L);
    }
}
