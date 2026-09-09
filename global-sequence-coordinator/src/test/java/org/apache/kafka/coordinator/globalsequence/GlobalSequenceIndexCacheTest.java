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

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlobalSequenceIndexCacheTest {
    private static final Uuid TOPIC_ID = Uuid.randomUuid();

    @Test
    void testLookupRequiresContiguousCoverage() {
        GlobalSequenceIndexCache cache = new GlobalSequenceIndexCache(10);
        GlobalSequenceIndexRecord first = record(0L, 3, 0, 10L);
        GlobalSequenceIndexRecord second = record(3L, 2, 1, 20L);
        cache.put(first);
        cache.put(second);

        assertEquals(
            new GlobalSequenceLookupResult(List.of(first, second)),
            cache.lookup(new GlobalSequenceLookupRequest(TOPIC_ID, 1L, 5L), 10).orElseThrow()
        );
        assertEquals(
            new GlobalSequenceLookupResult(List.of(first)),
            cache.lookup(new GlobalSequenceLookupRequest(TOPIC_ID, 1L, 5L), 1).orElseThrow()
        );
        assertFalse(cache.lookup(new GlobalSequenceLookupRequest(TOPIC_ID, 1L, 6L), 10).isPresent());
        assertEquals(2L, cache.hitCount());
        assertEquals(1L, cache.missCount());
    }

    @Test
    void testEvictsLeastRecentlyUsedAcrossTopics() {
        GlobalSequenceIndexCache cache = new GlobalSequenceIndexCache(2);
        GlobalSequenceIndexRecord first = record(0L, 1, 0, 10L);
        GlobalSequenceIndexRecord second = record(1L, 1, 0, 11L);
        GlobalSequenceIndexRecord third = new GlobalSequenceIndexRecord(
            Uuid.randomUuid(), 0L, 1, 0, 12L
        );
        cache.put(first);
        cache.put(second);

        assertTrue(cache.getByPhysicalBatch(physicalBatchId(first)).isPresent());
        cache.put(third);

        assertEquals(2, cache.size());
        assertTrue(cache.getByPhysicalBatch(physicalBatchId(first)).isPresent());
        assertFalse(cache.getByPhysicalBatch(physicalBatchId(second)).isPresent());
        assertEquals(1L, cache.evictionCount());
    }

    @Test
    void testTombstoneRemovesAllIndexes() {
        GlobalSequenceIndexCache cache = new GlobalSequenceIndexCache(2);
        GlobalSequenceIndexRecord record = record(0L, 1, 0, 10L);
        cache.put(record);

        cache.remove(TOPIC_ID, 0L);

        assertEquals(0, cache.size());
        assertFalse(cache.getByPhysicalBatch(physicalBatchId(record)).isPresent());
        assertFalse(cache.lookup(new GlobalSequenceLookupRequest(TOPIC_ID, 0L, 1L), 1).isPresent());
    }

    @Test
    void testRemoveTopicInvalidatesGlobalAndPhysicalIndexes() {
        GlobalSequenceIndexCache cache = new GlobalSequenceIndexCache(10);
        Uuid otherTopicId = Uuid.randomUuid();
        GlobalSequenceIndexRecord first = record(0L, 1, 0, 10L);
        GlobalSequenceIndexRecord second = record(1L, 1, 1, 20L);
        GlobalSequenceIndexRecord other = new GlobalSequenceIndexRecord(
            otherTopicId,
            0L,
            1,
            0,
            10L
        );
        cache.put(first);
        cache.put(second);
        cache.put(other);

        assertEquals(2, cache.removeTopic(TOPIC_ID));

        assertEquals(1, cache.size());
        assertFalse(cache.getByPhysicalBatch(physicalBatchId(first)).isPresent());
        assertFalse(cache.getByPhysicalBatch(physicalBatchId(second)).isPresent());
        assertTrue(cache.getByPhysicalBatch(physicalBatchId(other)).isPresent());
        assertEquals(0, cache.removeTopic(TOPIC_ID));
    }

    @Test
    void testRejectsConflictingIndexes() {
        GlobalSequenceIndexCache cache = new GlobalSequenceIndexCache(2);
        cache.put(record(0L, 1, 0, 10L));

        assertThrows(
            IllegalStateException.class,
            () -> cache.put(record(0L, 2, 1, 20L))
        );
        assertThrows(
            IllegalStateException.class,
            () -> cache.put(record(1L, 1, 0, 10L))
        );
    }

    private static GlobalSequenceIndexRecord record(
        long globalBaseOffset,
        int recordCount,
        int partitionIndex,
        long partitionBaseOffset
    ) {
        return new GlobalSequenceIndexRecord(
            TOPIC_ID,
            globalBaseOffset,
            recordCount,
            partitionIndex,
            partitionBaseOffset
        );
    }

    private static PhysicalBatchId physicalBatchId(GlobalSequenceIndexRecord record) {
        return new PhysicalBatchId(record.topicId(), record.partitionIndex(), record.partitionBaseOffset());
    }
}
