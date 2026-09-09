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

import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.Uuid;
import org.apache.kafka.common.errors.UnsupportedVersionException;
import org.apache.kafka.common.protocol.ApiMessage;
import org.apache.kafka.common.protocol.Errors;
import org.apache.kafka.common.requests.TransactionResult;
import org.apache.kafka.common.utils.LogContext;
import org.apache.kafka.common.utils.Time;
import org.apache.kafka.coordinator.common.runtime.CoordinatorExecutor;
import org.apache.kafka.coordinator.common.runtime.CoordinatorMetrics;
import org.apache.kafka.coordinator.common.runtime.CoordinatorMetricsShard;
import org.apache.kafka.coordinator.common.runtime.CoordinatorRecord;
import org.apache.kafka.coordinator.common.runtime.CoordinatorResult;
import org.apache.kafka.coordinator.common.runtime.CoordinatorShard;
import org.apache.kafka.coordinator.common.runtime.CoordinatorShardBuilder;
import org.apache.kafka.coordinator.common.runtime.CoordinatorTimer;
import org.apache.kafka.coordinator.globalsequence.generated.CoordinatorRecordType;
import org.apache.kafka.coordinator.globalsequence.generated.GlobalSequenceIndexLogKey;
import org.apache.kafka.coordinator.globalsequence.generated.GlobalSequenceIndexLogValue;
import org.apache.kafka.coordinator.globalsequence.generated.GlobalSequenceTopicMetadataKey;
import org.apache.kafka.coordinator.globalsequence.generated.GlobalSequenceTopicMetadataValue;
import org.apache.kafka.coordinator.globalsequence.metrics.GlobalSequenceCoordinatorMetrics;
import org.apache.kafka.coordinator.globalsequence.metrics.GlobalSequenceCoordinatorMetricsShard;
import org.apache.kafka.image.MetadataDelta;
import org.apache.kafka.image.MetadataImage;
import org.apache.kafka.server.common.ApiMessageAndVersion;
import org.apache.kafka.timeline.SnapshotRegistry;

import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class GlobalSequenceCoordinatorShard implements CoordinatorShard<CoordinatorRecord> {

    public static class Builder implements CoordinatorShardBuilder<GlobalSequenceCoordinatorShard, CoordinatorRecord> {

        private final GlobalSequenceCoordinatorConfig config;
        private LogContext logContext;
        private SnapshotRegistry snapshotRegistry;
        private Time time;
        private CoordinatorTimer<Void, CoordinatorRecord> timer;
        private CoordinatorExecutor<CoordinatorRecord> executor;
        private CoordinatorMetrics coordinatorMetrics;
        private TopicPartition topicPartition;

        public Builder(GlobalSequenceCoordinatorConfig config) {
            this.config = config;
        }

        @Override
        public Builder withSnapshotRegistry(SnapshotRegistry snapshotRegistry) {
            this.snapshotRegistry = snapshotRegistry;
            return this;
        }

        @Override
        public Builder withLogContext(LogContext logContext) {
            this.logContext = logContext;
            return this;
        }

        @Override
        public Builder withTime(Time time) {
            this.time = time;
            return this;
        }

        @Override
        public Builder withTimer(CoordinatorTimer<Void, CoordinatorRecord> timer) {
            this.timer = timer;
            return this;
        }

        @Override
        public Builder withExecutor(CoordinatorExecutor<CoordinatorRecord> executor) {
            this.executor = executor;
            return this;
        }

        @Override
        public Builder withCoordinatorMetrics(CoordinatorMetrics coordinatorMetrics) {
            this.coordinatorMetrics = coordinatorMetrics;
            return this;
        }

        @Override
        public Builder withTopicPartition(TopicPartition topicPartition) {
            this.topicPartition = topicPartition;
            return this;
        }

        @SuppressWarnings("NPathComplexity")
        @Override
        public GlobalSequenceCoordinatorShard build() {
            if (logContext == null) logContext = new LogContext();
            if (config == null)
                throw new IllegalArgumentException("Config must be set.");
            if (snapshotRegistry == null)
                throw new IllegalArgumentException("SnapshotRegistry must be set.");
            if (time == null)
                throw new IllegalArgumentException("Time must be set.");
            if (timer == null)
                throw new IllegalArgumentException("Timer must be set.");
            if (executor == null)
                throw new IllegalArgumentException("Executor must be set.");
            if (coordinatorMetrics == null || !(coordinatorMetrics instanceof GlobalSequenceCoordinatorMetrics))
                throw new IllegalArgumentException("CoordinatorMetrics must be set and be of type GlobalSequenceCoordinatorMetrics.");
            if (topicPartition == null)
                throw new IllegalArgumentException("TopicPartition must be set.");
            GlobalSequenceCoordinatorMetricsShard metricsShard = ((GlobalSequenceCoordinatorMetrics) coordinatorMetrics)
                    .newMetricsShard(snapshotRegistry, topicPartition);

            return new GlobalSequenceCoordinatorShard(
                    logContext,
                    new GlobalSequenceStateRegistry(
                        snapshotRegistry,
                        config.indexCheckpointInterval(),
                        config.indexCheckpointLevelFactor()
                    ),
                    new GlobalSequenceIndexCache(config.indexCacheMaxEntries()),
                    time,
                    timer,
                    config,
                    coordinatorMetrics,
                    metricsShard
            );
        }
    }

    static final String GLOBAL_SEQUENCE_EXPIRATION_KEY = "expire-global-sequence-metadata";
    static final String GLOBAL_SEQUENCE_METADATA_BOOTSTRAP_KEY_PREFIX =
        "bootstrap-global-sequence-topic-metadata-";

    private final Logger log;

    private final GlobalSequenceStateRegistry stateRegistry;

    private final GlobalSequenceIndexCache indexCache;

    private final Time time;

    private final CoordinatorTimer<Void, CoordinatorRecord> timer;

    private final GlobalSequenceCoordinatorConfig config;

    private final CoordinatorMetrics coordinatorMetrics;

    private final CoordinatorMetricsShard metricsShard;

    private final List<PendingTombstone> uncommittedTombstones = new ArrayList<>();
    private final Set<String> metadataBootstrapTimerKeys = new HashSet<>();

    private boolean loading = true;

    GlobalSequenceCoordinatorShard(
            LogContext logContext,
            GlobalSequenceStateRegistry stateRegistry,
            GlobalSequenceIndexCache indexCache,
            Time time,
            CoordinatorTimer<Void, CoordinatorRecord> timer,
            GlobalSequenceCoordinatorConfig config,
            CoordinatorMetrics coordinatorMetrics,
            CoordinatorMetricsShard metricsShard
    ) {
        this.log = logContext.logger(GlobalSequenceCoordinatorShard.class);
        this.stateRegistry = stateRegistry;
        this.indexCache = indexCache;
        this.time = time;
        this.timer = timer;
        this.config = config;
        this.coordinatorMetrics = coordinatorMetrics;
        this.metricsShard = metricsShard;
    }

    CoordinatorResult<GlobalSequenceAppendResult, CoordinatorRecord> appendIndex(
        GlobalSequenceAppendRequest request
    ) {
        GlobalSequenceStateRegistry.PreparedAppend preparedAppend = stateRegistry.prepareAppend(request);
        if (!preparedAppend.duplicate()) {
            Optional<GlobalSequenceIndexRecord> cached = indexCache.getByPhysicalBatch(request.physicalBatchId());
            if (cached.isPresent()) {
                return duplicateResult(cached.get(), request);
            }
        }
        return appendResult(preparedAppend);
    }

    GlobalSequenceAppendPreparation prepareAppend(
        GlobalSequenceAppendRequest request,
        long indexLogHighWatermark,
        int coordinatorLeaderEpoch
    ) {
        if (stateRegistry.isNewPhysicalBatch(request, indexLogHighWatermark)) {
            return GlobalSequenceAppendPreparation.fresh();
        }

        Optional<GlobalSequenceIndexRecord> cached = indexCache.getByPhysicalBatch(request.physicalBatchId());
        if (cached.isPresent()) {
            metricsShard.record(GlobalSequenceCoordinatorMetrics.INDEX_CACHE_HITS_SENSOR_NAME);
            return GlobalSequenceAppendPreparation.duplicate(toDuplicateResult(cached.get(), request));
        }
        metricsShard.record(GlobalSequenceCoordinatorMetrics.INDEX_CACHE_MISSES_SENSOR_NAME);

        long startIndexLogOffset = stateRegistry.physicalScanStartIndexLogOffset(
            request,
            indexLogHighWatermark
        );
        return GlobalSequenceAppendPreparation.scan(new GlobalSequencePhysicalIndexScanPlan(
            request.topicId(),
            request.partitionIndex(),
            request.partitionBaseOffset(),
            request.recordCount(),
            startIndexLogOffset,
            indexLogHighWatermark,
            coordinatorLeaderEpoch
        ));
    }

    CoordinatorResult<GlobalSequenceAppendResult, CoordinatorRecord> appendIndexAfterScan(
        GlobalSequencePhysicalIndexScanPlan plan,
        Optional<GlobalSequenceIndexRecord> scannedIndexRecord,
        long currentIndexLogHighWatermark,
        int currentCoordinatorLeaderEpoch
    ) {
        if (currentCoordinatorLeaderEpoch != plan.coordinatorLeaderEpoch() ||
            currentIndexLogHighWatermark < plan.capturedHighWatermark()) {
            throw Errors.NOT_COORDINATOR.exception(
                "Discarding a physical batch index scan captured at coordinator leader epoch " +
                    plan.coordinatorLeaderEpoch() + " because the current epoch is " +
                    currentCoordinatorLeaderEpoch
            );
        }

        GlobalSequenceAppendRequest request = plan.request();
        GlobalSequenceStateRegistry.PreparedAppend rechecked = stateRegistry.prepareAppend(request);
        if (rechecked.duplicate()) {
            return appendResult(rechecked);
        }

        Optional<GlobalSequenceIndexRecord> cached = indexCache.getByPhysicalBatch(request.physicalBatchId());
        if (cached.isPresent()) {
            metricsShard.record(GlobalSequenceCoordinatorMetrics.INDEX_CACHE_HITS_SENSOR_NAME);
            return duplicateResult(cached.get(), request);
        }
        metricsShard.record(GlobalSequenceCoordinatorMetrics.INDEX_CACHE_MISSES_SENSOR_NAME);

        if (scannedIndexRecord.isPresent()) {
            GlobalSequenceIndexRecord existing = scannedIndexRecord.get();
            GlobalSequenceAppendResult result = toDuplicateResult(existing, request);
            cacheIndexRecords(List.of(existing));
            return new CoordinatorResult<>(List.of(), result);
        }
        return appendResult(rechecked);
    }

    private CoordinatorResult<GlobalSequenceAppendResult, CoordinatorRecord> duplicateResult(
        GlobalSequenceIndexRecord existing,
        GlobalSequenceAppendRequest request
    ) {
        return new CoordinatorResult<>(List.of(), toDuplicateResult(existing, request));
    }

    private GlobalSequenceAppendResult toDuplicateResult(
        GlobalSequenceIndexRecord existing,
        GlobalSequenceAppendRequest request
    ) {
        if (!existing.topicId().equals(request.topicId()) ||
            existing.partitionIndex() != request.partitionIndex() ||
            existing.partitionBaseOffset() != request.partitionBaseOffset()) {
            throw new IllegalArgumentException("The existing allocation does not match physical batch " + request);
        }
        if (existing.recordCount() != request.recordCount()) {
            throw new IllegalArgumentException(
                "Physical batch " + request.physicalBatchId() + " is already allocated with recordCount=" +
                    existing.recordCount() + ", but the retry has recordCount=" + request.recordCount()
            );
        }
        return existing.toAppendResult(true);
    }

    private CoordinatorResult<GlobalSequenceAppendResult, CoordinatorRecord> appendResult(
        GlobalSequenceStateRegistry.PreparedAppend preparedAppend
    ) {

        if (preparedAppend.duplicate()) {
            return new CoordinatorResult<>(
                List.of(),
                preparedAppend.indexRecord().toAppendResult(true)
            );
        }

        return new CoordinatorResult<>(
            List.of(
                toCoordinatorRecord(preparedAppend.indexRecord()),
                toTopicMetadataRecord(
                    preparedAppend.indexRecord().topicId(),
                    preparedAppend.indexRecord().globalEndOffsetExclusive()
                )
            ),
            preparedAppend.indexRecord().toAppendResult(false)
        );
    }

    GlobalSequenceLookupResult lookupIndex(GlobalSequenceLookupRequest request, long indexLogHighWatermark) {
        GlobalSequenceIndexLookupPreparation preparation = prepareLookup(request, indexLogHighWatermark, 0);
        return preparation.cachedResult().orElseThrow(() ->
            GlobalSequenceStateRegistry.outOfRange(request, request.globalStartOffset())
        );
    }

    GlobalSequenceIndexLookupPreparation prepareLookup(
        GlobalSequenceLookupRequest request,
        long indexLogHighWatermark,
        int coordinatorLeaderEpoch
    ) {
        int maxIndexEntries = Math.min(request.maxIndexEntries(), config.maxLookupIndexEntries());
        Optional<GlobalSequenceLookupResult> cachedResult = lookupCached(request, maxIndexEntries);
        if (cachedResult.isPresent()) {
            return GlobalSequenceIndexLookupPreparation.cached(cachedResult.get());
        }

        long startIndexLogOffset = stateRegistry.scanStartIndexLogOffset(request, indexLogHighWatermark);
        return GlobalSequenceIndexLookupPreparation.scan(new GlobalSequenceIndexScanPlan(
            request.topicId(),
            request.globalStartOffset(),
            request.globalEndOffsetExclusive(),
            startIndexLogOffset,
            indexLogHighWatermark,
            coordinatorLeaderEpoch,
            maxIndexEntries
        ));
    }

    GlobalSequenceLookupResult completeLookup(
        GlobalSequenceIndexScanPlan plan,
        GlobalSequenceLookupResult result,
        int currentCoordinatorLeaderEpoch
    ) {
        if (currentCoordinatorLeaderEpoch != plan.coordinatorLeaderEpoch()) {
            throw Errors.NOT_COORDINATOR.exception(
                "Discarding a global sequence index scan captured at coordinator leader epoch " +
                    plan.coordinatorLeaderEpoch() + " because the current epoch is " +
                    currentCoordinatorLeaderEpoch
            );
        }
        return cacheAndRecordLookup(result, plan.request());
    }

    private Optional<GlobalSequenceLookupResult> lookupCached(
        GlobalSequenceLookupRequest request,
        int maxIndexEntries
    ) {
        Optional<GlobalSequenceLookupResult> result = indexCache.lookup(request, maxIndexEntries);
        if (result.isPresent()) {
            metricsShard.record(GlobalSequenceCoordinatorMetrics.INDEX_CACHE_HITS_SENSOR_NAME);
            recordLookup(result.get(), request);
        } else {
            metricsShard.record(GlobalSequenceCoordinatorMetrics.INDEX_CACHE_MISSES_SENSOR_NAME);
        }
        return result;
    }

    private GlobalSequenceLookupResult cacheAndRecordLookup(
        GlobalSequenceLookupResult result,
        GlobalSequenceLookupRequest request
    ) {
        cacheIndexRecords(result.indexRecords());
        recordLookup(result, request);
        return result;
    }

    private void cacheIndexRecords(List<GlobalSequenceIndexRecord> indexRecords) {
        long evictionsBefore = indexCache.evictionCount();
        indexRecords.forEach(indexCache::put);
        long evictions = indexCache.evictionCount() - evictionsBefore;
        for (long index = 0; index < evictions; index++) {
            metricsShard.record(GlobalSequenceCoordinatorMetrics.INDEX_CACHE_EVICTIONS_SENSOR_NAME);
        }
    }

    private void promoteCommittedAllocations(long indexLogHighWatermark) {
        List<GlobalSequenceIndexRecord> committed = stateRegistry.promoteCommittedAllocations(
            indexLogHighWatermark
        );
        if (!committed.isEmpty()) {
            cacheIndexRecords(committed);
            addRetainedAllocations(-committed.size());
        }
    }

    private void applyCommittedTombstones(long indexLogHighWatermark) {
        List<PendingTombstone> committed = uncommittedTombstones.stream()
            .filter(tombstone -> tombstone.indexLogOffset() < indexLogHighWatermark)
            .toList();
        for (PendingTombstone tombstone : committed) {
            stateRegistry.replayTombstone(tombstone.topicId(), tombstone.globalBaseOffset());
            indexCache.remove(tombstone.topicId(), tombstone.globalBaseOffset());
        }
        uncommittedTombstones.removeAll(committed);
    }

    private void addRetainedAllocations(long delta) {
        if (metricsShard instanceof GlobalSequenceCoordinatorMetricsShard globalSequenceMetricsShard) {
            globalSequenceMetricsShard.addRetainedAllocations(delta);
        }
    }

    private void recordLookup(GlobalSequenceLookupResult result, GlobalSequenceLookupRequest request) {
        metricsShard.record(
            GlobalSequenceCoordinatorMetrics.INDEX_LOOKUP_ENTRIES_SENSOR_NAME,
            result.indexRecords().size()
        );
        GlobalSequenceIndexRecord lastRecord = result.indexRecords().get(result.indexRecords().size() - 1);
        if (lastRecord.globalEndOffsetExclusive() < request.globalEndOffsetExclusive()) {
            metricsShard.record(GlobalSequenceCoordinatorMetrics.INDEX_LOOKUP_PAGINATIONS_SENSOR_NAME);
        }
    }

    private CoordinatorRecord toCoordinatorRecord(GlobalSequenceIndexRecord indexRecord) {
        GlobalSequenceIndexLogKey key = new GlobalSequenceIndexLogKey();
        key.setTopicId(indexRecord.topicId());
        key.setGlobalOffset(indexRecord.globalBaseOffset());

        GlobalSequenceIndexLogValue value = new GlobalSequenceIndexLogValue();
        value.setRecordsCount(indexRecord.recordCount());
        value.setPartitionIndex(indexRecord.partitionIndex());
        value.setPartitionOffset(indexRecord.partitionBaseOffset());

        return CoordinatorRecord.record(key, new ApiMessageAndVersion(value, (short) 0));
    }

    private CoordinatorRecord toTopicMetadataRecord(Uuid topicId, long nextGlobalOffset) {
        return CoordinatorRecord.record(
            new GlobalSequenceTopicMetadataKey().setTopicId(topicId),
            new ApiMessageAndVersion(
                new GlobalSequenceTopicMetadataValue().setNextGlobalOffset(nextGlobalOffset),
                (short) 0
            )
        );
    }

    private void scheduleTopicMetadataBootstrap() {
        stateRegistry.topicsMissingDurableMetadata().forEach((topicId, nextGlobalOffset) -> {
            String timerKey = GLOBAL_SEQUENCE_METADATA_BOOTSTRAP_KEY_PREFIX + topicId;
            metadataBootstrapTimerKeys.add(timerKey);
            timer.schedule(
                timerKey,
                0L,
                TimeUnit.MILLISECONDS,
                true,
                () -> new CoordinatorResult<>(List.of(toTopicMetadataRecord(topicId, nextGlobalOffset)))
            );
        });
    }

    @Override
    public void onLoaded(MetadataImage newImage) {
        int cleared = stateRegistry.clearUncommittedAllocations();
        if (cleared > 0) {
            addRetainedAllocations(-cleared);
        }
        uncommittedTombstones.clear();
        stateRegistry.topicIds().stream()
            .filter(topicId -> newImage.topics().getTopic(topicId) == null)
            .forEach(this::removeTopicState);
        loading = false;
        scheduleTopicMetadataBootstrap();
        coordinatorMetrics.activateMetricsShard(metricsShard);
    }

    @Override
    public void onHighWatermarkUpdated(long offset) {
        promoteCommittedAllocations(offset);
        applyCommittedTombstones(offset);
    }

    @Override
    public void onWrittenOffsetReverted(long offset) {
        int rolledBack = stateRegistry.rollbackUncommittedAllocations(offset);
        if (rolledBack > 0) {
            addRetainedAllocations(-rolledBack);
        }
        uncommittedTombstones.removeIf(tombstone -> tombstone.indexLogOffset() >= offset);
    }

    @Override
    public void onNewMetadataImage(MetadataImage newImage, MetadataDelta delta) {
        if (delta.topicsDelta() == null) {
            return;
        }
        delta.topicsDelta().deletedTopicIds().forEach(this::removeTopicState);
    }

    private void removeTopicState(Uuid topicId) {
        int removedUncommittedAllocations = stateRegistry.removeTopic(topicId);
        if (removedUncommittedAllocations > 0) {
            addRetainedAllocations(-removedUncommittedAllocations);
        }
        indexCache.removeTopic(topicId);
        uncommittedTombstones.removeIf(tombstone -> tombstone.topicId().equals(topicId));

        String timerKey = GLOBAL_SEQUENCE_METADATA_BOOTSTRAP_KEY_PREFIX + topicId;
        timer.cancel(timerKey);
        metadataBootstrapTimerKeys.remove(timerKey);
    }

    @Override
    public void onUnloaded() {
        timer.cancel(GLOBAL_SEQUENCE_EXPIRATION_KEY);
        metadataBootstrapTimerKeys.forEach(timer::cancel);
        metadataBootstrapTimerKeys.clear();
        indexCache.clear();
        coordinatorMetrics.deactivateMetricsShard(metricsShard);
    }

    @Override
    public void replay(long offset, long producerId, short producerEpoch, CoordinatorRecord record) throws RuntimeException {
        ApiMessage key = record.key();
        final CoordinatorRecordType recordType;
        try {
            recordType = CoordinatorRecordType.fromId(key.apiKey());
        } catch (UnsupportedVersionException exception) {
            throw new IllegalStateException("Unknown global sequence coordinator record type " + key.apiKey(), exception);
        }

        if (recordType == CoordinatorRecordType.GLOBAL_SEQUENCE_TOPIC_METADATA &&
            key instanceof GlobalSequenceTopicMetadataKey metadataKey) {
            replayTopicMetadata(metadataKey, record.value());
            return;
        }
        if (recordType != CoordinatorRecordType.GLOBAL_SEQUENCE_INDEX_LOG ||
            !(key instanceof GlobalSequenceIndexLogKey indexKey)) {
            throw new IllegalStateException("Unexpected global sequence coordinator record " + record);
        }

        ApiMessageAndVersion value = record.value();
        if (value == null) {
            if (loading) {
                stateRegistry.replayTombstone(indexKey.topicId(), indexKey.globalOffset());
                indexCache.remove(indexKey.topicId(), indexKey.globalOffset());
            } else {
                uncommittedTombstones.add(new PendingTombstone(
                    indexKey.topicId(),
                    indexKey.globalOffset(),
                    offset
                ));
            }
            return;
        }
        if (value.version() != 0 || !(value.message() instanceof GlobalSequenceIndexLogValue indexValue)) {
            throw new IllegalStateException("Unexpected global sequence index record value " + value);
        }

        GlobalSequenceIndexRecord indexRecord = new GlobalSequenceIndexRecord(
            indexKey.topicId(),
            indexKey.globalOffset(),
            indexValue.recordsCount(),
            indexValue.partitionIndex(),
            indexValue.partitionOffset()
        );
        boolean added = stateRegistry.replay(indexRecord, offset, !loading);
        if (added) {
            if (loading) {
                cacheIndexRecords(List.of(indexRecord));
            } else {
                addRetainedAllocations(1L);
            }
            metricsShard.record(GlobalSequenceCoordinatorMetrics.INDEX_ALLOCATIONS_SENSOR_NAME);
        }
    }

    @Override
    public void replayEndTransactionMarker(long producerId, short producerEpoch, TransactionResult result) throws RuntimeException {
        // TODO: Implement here
        CoordinatorShard.super.replayEndTransactionMarker(producerId, producerEpoch, result);
    }

    private void replayTopicMetadata(
        GlobalSequenceTopicMetadataKey key,
        ApiMessageAndVersion value
    ) {
        if (value == null || value.version() != 0 ||
            !(value.message() instanceof GlobalSequenceTopicMetadataValue metadataValue)) {
            throw new IllegalStateException("Unexpected global sequence topic metadata value " + value);
        }
        stateRegistry.replayTopicMetadata(key.topicId(), metadataValue.nextGlobalOffset());
        metadataBootstrapTimerKeys.remove(GLOBAL_SEQUENCE_METADATA_BOOTSTRAP_KEY_PREFIX + key.topicId());
    }

    private record PendingTombstone(Uuid topicId, long globalBaseOffset, long indexLogOffset) { }
}
