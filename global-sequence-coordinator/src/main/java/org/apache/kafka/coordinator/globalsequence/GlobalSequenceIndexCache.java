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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * A shard-wide bounded LRU cache of committed global sequence index records.
 *
 * <p>This cache is deliberately not snapshot-aware. Only records read below a captured coordinator
 * high watermark may be inserted. Replay state used to allocate offsets remains in the coordinator's
 * timeline data structures.</p>
 */
public class GlobalSequenceIndexCache {
    private final int maxEntries;
    private final LinkedHashMap<IndexKey, GlobalSequenceIndexRecord> lru;
    private final Map<Uuid, NavigableMap<Long, GlobalSequenceIndexRecord>> byTopic;
    private final Map<PhysicalBatchId, GlobalSequenceIndexRecord> byPhysicalBatch;
    private long hitCount;
    private long missCount;
    private long evictionCount;

    public GlobalSequenceIndexCache(int maxEntries) {
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries must be positive");
        }
        this.maxEntries = maxEntries;
        this.lru = new LinkedHashMap<>(16, 0.75f, true);
        this.byTopic = new HashMap<>();
        this.byPhysicalBatch = new HashMap<>();
    }

    public synchronized void put(GlobalSequenceIndexRecord indexRecord) {
        Objects.requireNonNull(indexRecord, "indexRecord");
        IndexKey indexKey = new IndexKey(indexRecord.topicId(), indexRecord.globalBaseOffset());
        PhysicalBatchId physicalBatchId = physicalBatchId(indexRecord);
        GlobalSequenceIndexRecord existingByOffset = lru.get(indexKey);
        GlobalSequenceIndexRecord existingByPhysicalBatch = byPhysicalBatch.get(physicalBatchId);

        if (existingByOffset != null && !existingByOffset.equals(indexRecord)) {
            throw new IllegalStateException(
                "Conflicting cached allocation at global base offset " + indexRecord.globalBaseOffset()
            );
        }
        if (existingByPhysicalBatch != null && !existingByPhysicalBatch.equals(indexRecord)) {
            throw new IllegalStateException("Conflicting cached allocation for physical batch " + physicalBatchId);
        }
        if (existingByOffset != null) {
            return;
        }

        lru.put(indexKey, indexRecord);
        byTopic.computeIfAbsent(indexRecord.topicId(), __ -> new TreeMap<>())
            .put(indexRecord.globalBaseOffset(), indexRecord);
        byPhysicalBatch.put(physicalBatchId, indexRecord);
        evictIfNecessary();
    }

    public synchronized void remove(Uuid topicId, long globalBaseOffset) {
        Objects.requireNonNull(topicId, "topicId");
        IndexKey indexKey = new IndexKey(topicId, globalBaseOffset);
        GlobalSequenceIndexRecord removed = lru.remove(indexKey);
        if (removed != null) {
            removeFromIndexes(indexKey, removed);
        }
    }

    public synchronized int removeTopic(Uuid topicId) {
        Objects.requireNonNull(topicId, "topicId");
        NavigableMap<Long, GlobalSequenceIndexRecord> removed = byTopic.remove(topicId);
        if (removed == null) {
            return 0;
        }
        removed.forEach((globalBaseOffset, indexRecord) -> {
            lru.remove(new IndexKey(topicId, globalBaseOffset));
            byPhysicalBatch.remove(physicalBatchId(indexRecord), indexRecord);
        });
        return removed.size();
    }

    public synchronized Optional<GlobalSequenceIndexRecord> getByPhysicalBatch(PhysicalBatchId physicalBatchId) {
        Objects.requireNonNull(physicalBatchId, "physicalBatchId");
        GlobalSequenceIndexRecord record = byPhysicalBatch.get(physicalBatchId);
        if (record == null) {
            missCount++;
            return Optional.empty();
        }
        lru.get(new IndexKey(record.topicId(), record.globalBaseOffset()));
        hitCount++;
        return Optional.of(record);
    }

    /**
     * Returns a result only when the cache contains a contiguous response through the requested end
     * or through the page entry limit. A partial cache fragment is treated as a miss.
     */
    public synchronized Optional<GlobalSequenceLookupResult> lookup(
        GlobalSequenceLookupRequest request,
        int maxIndexEntries
    ) {
        Objects.requireNonNull(request, "request");
        if (maxIndexEntries <= 0) {
            throw new IllegalArgumentException("maxIndexEntries must be positive");
        }
        NavigableMap<Long, GlobalSequenceIndexRecord> topicIndex = byTopic.get(request.topicId());
        if (topicIndex == null) {
            missCount++;
            return Optional.empty();
        }

        Map.Entry<Long, GlobalSequenceIndexRecord> current = topicIndex.floorEntry(request.globalStartOffset());
        if (current == null || current.getValue().globalEndOffsetExclusive() <= request.globalStartOffset()) {
            missCount++;
            return Optional.empty();
        }

        List<GlobalSequenceIndexRecord> matches = new ArrayList<>();
        long nextOffsetToCover = request.globalStartOffset();
        while (current != null && current.getValue().globalBaseOffset() <= nextOffsetToCover) {
            GlobalSequenceIndexRecord record = current.getValue();
            if (record.globalEndOffsetExclusive() <= nextOffsetToCover) {
                current = topicIndex.higherEntry(current.getKey());
                continue;
            }
            matches.add(record);
            lru.get(new IndexKey(record.topicId(), record.globalBaseOffset()));
            nextOffsetToCover = Math.min(record.globalEndOffsetExclusive(), request.globalEndOffsetExclusive());
            if (nextOffsetToCover == request.globalEndOffsetExclusive() || matches.size() == maxIndexEntries) {
                hitCount++;
                return Optional.of(new GlobalSequenceLookupResult(matches));
            }
            current = topicIndex.higherEntry(current.getKey());
        }

        missCount++;
        return Optional.empty();
    }

    public synchronized int size() {
        return lru.size();
    }

    public int maxEntries() {
        return maxEntries;
    }

    public synchronized long hitCount() {
        return hitCount;
    }

    public synchronized long missCount() {
        return missCount;
    }

    public synchronized long evictionCount() {
        return evictionCount;
    }

    public synchronized void clear() {
        lru.clear();
        byTopic.clear();
        byPhysicalBatch.clear();
    }

    private void evictIfNecessary() {
        while (lru.size() > maxEntries) {
            Iterator<Map.Entry<IndexKey, GlobalSequenceIndexRecord>> iterator = lru.entrySet().iterator();
            Map.Entry<IndexKey, GlobalSequenceIndexRecord> eldest = iterator.next();
            iterator.remove();
            removeFromIndexes(eldest.getKey(), eldest.getValue());
            evictionCount++;
        }
    }

    private void removeFromIndexes(IndexKey indexKey, GlobalSequenceIndexRecord indexRecord) {
        NavigableMap<Long, GlobalSequenceIndexRecord> topicIndex = byTopic.get(indexKey.topicId());
        if (topicIndex != null) {
            topicIndex.remove(indexKey.globalBaseOffset());
            if (topicIndex.isEmpty()) {
                byTopic.remove(indexKey.topicId());
            }
        }
        byPhysicalBatch.remove(physicalBatchId(indexRecord), indexRecord);
    }

    private static PhysicalBatchId physicalBatchId(GlobalSequenceIndexRecord record) {
        return new PhysicalBatchId(record.topicId(), record.partitionIndex(), record.partitionBaseOffset());
    }

    private record IndexKey(Uuid topicId, long globalBaseOffset) { }
}
