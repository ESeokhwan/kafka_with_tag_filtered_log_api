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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlobalSequenceIndexCheckpointIndexTest {
    private static final Uuid TOPIC_ID = Uuid.randomUuid();

    @Test
    void testRetainsProgressivelySparserOldCheckpoints() {
        GlobalSequenceIndexCheckpointIndex index = newIndex();

        replay(index, 21);

        assertEquals(
            List.of(
                checkpoint(0),
                checkpoint(8),
                checkpoint(16),
                checkpoint(20)
            ),
            index.checkpoints(TOPIC_ID, SnapshotRegistry.LATEST_EPOCH)
        );
        assertEquals(21L, index.allocationCount(TOPIC_ID, SnapshotRegistry.LATEST_EPOCH));
        assertEquals(checkpoint(8), index.floor(TOPIC_ID, 155L, SnapshotRegistry.LATEST_EPOCH).orElseThrow());
        assertEquals(checkpoint(20), index.floor(TOPIC_ID, 200L, SnapshotRegistry.LATEST_EPOCH).orElseThrow());
    }

    @Test
    void testTopicsAreIndependent() {
        GlobalSequenceIndexCheckpointIndex index = newIndex();
        Uuid otherTopicId = Uuid.randomUuid();

        replay(index, 9);
        index.replayAllocation(otherTopicId, 50L, 500L);

        assertEquals(3, index.numCheckpoints(TOPIC_ID, SnapshotRegistry.LATEST_EPOCH));
        assertEquals(1, index.numCheckpoints(otherTopicId, SnapshotRegistry.LATEST_EPOCH));
        assertEquals(
            new GlobalSequenceIndexCheckpoint(0L, 50L, 500L),
            index.floor(otherTopicId, 50L, SnapshotRegistry.LATEST_EPOCH).orElseThrow()
        );
    }

    @Test
    void testSnapshotRollbackRestoresPrunedCheckpointsAndCount() {
        SnapshotRegistry snapshotRegistry = new SnapshotRegistry(new LogContext());
        GlobalSequenceIndexCheckpointIndex index = new GlobalSequenceIndexCheckpointIndex(snapshotRegistry, 4, 2);
        replay(index, 13);
        snapshotRegistry.idempotentCreateSnapshot(0L);

        for (int ordinal = 13; ordinal <= 20; ordinal++) {
            index.replayAllocation(TOPIC_ID, ordinal * 10L, ordinal * 100L);
        }
        assertFalse(index.checkpoints(TOPIC_ID, SnapshotRegistry.LATEST_EPOCH).contains(checkpoint(12)));

        snapshotRegistry.revertToSnapshot(0L);

        assertEquals(
            List.of(checkpoint(0), checkpoint(8), checkpoint(12)),
            index.checkpoints(TOPIC_ID, SnapshotRegistry.LATEST_EPOCH)
        );
        assertEquals(13L, index.allocationCount(TOPIC_ID, SnapshotRegistry.LATEST_EPOCH));
        assertTrue(index.floor(TOPIC_ID, 120L, SnapshotRegistry.LATEST_EPOCH).isPresent());
    }

    private static GlobalSequenceIndexCheckpointIndex newIndex() {
        return new GlobalSequenceIndexCheckpointIndex(
            new SnapshotRegistry(new LogContext()),
            4,
            2
        );
    }

    private static void replay(GlobalSequenceIndexCheckpointIndex index, int allocations) {
        for (int ordinal = 0; ordinal < allocations; ordinal++) {
            index.replayAllocation(TOPIC_ID, ordinal * 10L, ordinal * 100L);
        }
    }

    private static GlobalSequenceIndexCheckpoint checkpoint(long ordinal) {
        return new GlobalSequenceIndexCheckpoint(ordinal, ordinal * 10L, ordinal * 100L);
    }
}
