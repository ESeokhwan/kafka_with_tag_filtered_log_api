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
import org.apache.kafka.common.compress.Compression;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.errors.InvalidRequestException;
import org.apache.kafka.common.internals.Topic;
import org.apache.kafka.common.protocol.Errors;
import org.apache.kafka.common.record.CompressionType;
import org.apache.kafka.common.utils.LogContext;
import org.apache.kafka.common.utils.Time;
import org.apache.kafka.common.utils.Utils;
import org.apache.kafka.coordinator.common.runtime.CoordinatorEventProcessor;
import org.apache.kafka.coordinator.common.runtime.CoordinatorLoader;
import org.apache.kafka.coordinator.common.runtime.CoordinatorRecord;
import org.apache.kafka.coordinator.common.runtime.CoordinatorRuntime;
import org.apache.kafka.coordinator.common.runtime.CoordinatorRuntimeMetrics;
import org.apache.kafka.coordinator.common.runtime.CoordinatorShardBuilderSupplier;
import org.apache.kafka.coordinator.common.runtime.MultiThreadedEventProcessor;
import org.apache.kafka.coordinator.common.runtime.PartitionWriter;
import org.apache.kafka.coordinator.globalsequence.metrics.GlobalSequenceCoordinatorMetrics;
import org.apache.kafka.image.MetadataDelta;
import org.apache.kafka.image.MetadataImage;
import org.apache.kafka.server.record.BrokerCompressionType;
import org.apache.kafka.server.util.timer.Timer;

import org.slf4j.Logger;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Properties;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntSupplier;

public class GlobalSequenceCoordinator {

    public static class Builder {
        private int nodeId;
        private GlobalSequenceCoordinatorConfig config;
        private PartitionWriter writer;
        private CoordinatorLoader<CoordinatorRecord> loader;
        private Time time;
        private Timer timer;
        private CoordinatorRuntimeMetrics coordinatorRuntimeMetrics;
        private GlobalSequenceCoordinatorMetrics coordinatorMetrics;
        private GlobalSequenceIndexLogReader indexLogReader;

        public Builder(
                int nodeId,
                GlobalSequenceCoordinatorConfig config
        ) {
            this.nodeId = nodeId;
            this.config = config;
        }

        public Builder withWriter(PartitionWriter writer) {
            this.writer = writer;
            return this;
        }

        public Builder withLoader(CoordinatorLoader<CoordinatorRecord> loader) {
            this.loader = loader;
            return this;
        }

        public Builder withTime(Time time) {
            this.time = time;
            return this;
        }

        public Builder withTimer(Timer timer) {
            this.timer = timer;
            return this;
        }

        public Builder withCoordinatorRuntimeMetrics(CoordinatorRuntimeMetrics coordinatorRuntimeMetrics) {
            this.coordinatorRuntimeMetrics = coordinatorRuntimeMetrics;
            return this;
        }

        public Builder withCoordinatorMetrics(GlobalSequenceCoordinatorMetrics coordinatorMetrics) {
            this.coordinatorMetrics = coordinatorMetrics;
            return this;
        }

        public Builder withIndexLogReader(GlobalSequenceIndexLogReader indexLogReader) {
            this.indexLogReader = indexLogReader;
            return this;
        }

        @SuppressWarnings("NPathComplexity")
        public GlobalSequenceCoordinator build() {
            if (config == null)
                throw new IllegalArgumentException("Config must be set.");
            if (writer == null)
                throw new IllegalArgumentException("Writer must be set.");
            if (loader == null)
                throw new IllegalArgumentException("Loader must be set.");
            if (time == null)
                throw new IllegalArgumentException("Time must be set.");
            if (timer == null)
                throw new IllegalArgumentException("Timer must be set.");
            if (coordinatorRuntimeMetrics == null)
                throw new IllegalArgumentException("CoordinatorRuntimeMetrics must be set.");
            if (coordinatorMetrics == null)
                throw new IllegalArgumentException("CoordinatorMetrics must be set.");
            if (indexLogReader == null)
                throw new IllegalArgumentException("GlobalSequenceIndexLogReader must be set.");

            String logPrefix = String.format("GlobalSequenceCoordinator id=%d", nodeId);
            LogContext logContext = new LogContext(String.format("[%s] ", logPrefix));

            CoordinatorShardBuilderSupplier<GlobalSequenceCoordinatorShard, CoordinatorRecord> supplier = () ->
                    new GlobalSequenceCoordinatorShard.Builder(config);

            CoordinatorEventProcessor processor = new MultiThreadedEventProcessor(
                    logContext,
                    "global-sequence-coordinator-event-processor-",
                    1,
                    time,
                    coordinatorRuntimeMetrics
            );

            CoordinatorRuntime<GlobalSequenceCoordinatorShard, CoordinatorRecord> runtime =
                    new CoordinatorRuntime.Builder<GlobalSequenceCoordinatorShard, CoordinatorRecord>()
                            .withTime(time)
                            .withTimer(timer)
                            .withLogPrefix(logPrefix)
                            .withLogContext(logContext)
                            .withEventProcessor(processor)
                            .withPartitionWriter(writer)
                            .withLoader(loader)
                            .withCoordinatorShardBuilderSupplier(supplier)
                            .withDefaultWriteTimeOut(Duration.ofMillis(config.commitTimeoutMs()))
                            .withCoordinatorRuntimeMetrics(coordinatorRuntimeMetrics)
                            .withCoordinatorMetrics(coordinatorMetrics)
                            .withSerializer(new GlobalSequenceCoordinatorRecordSerde())
                            .withCompression(Compression.of(CompressionType.NONE).build()) // TODO: make configurable
                            .withAppendLingerMs(1000) // TODO: make configurable
                            .withExecutorService(Executors.newSingleThreadExecutor())
                            .build();

            return new GlobalSequenceCoordinator(
                    logContext,
                    config,
                    runtime,
                    coordinatorMetrics,
                    indexLogReader
            );
        }
    }

    private final Logger log;

    private final GlobalSequenceCoordinatorConfig config;

    private final CoordinatorRuntime<GlobalSequenceCoordinatorShard, CoordinatorRecord> runtime;

    private final GlobalSequenceCoordinatorMetrics coordinatorMetrics;

    private final GlobalSequenceIndexLogReader indexLogReader;

    /**
     * The number of partitions of the __global_sequence_index topics. This is provided
     * when the component is started.
     */
    private volatile int numPartitions = -1;

    /**
     * Boolean indicating whether the coordinator is active or not.
     */
    private final AtomicBoolean isActive = new AtomicBoolean(false);

    GlobalSequenceCoordinator(
            LogContext logContext,
            GlobalSequenceCoordinatorConfig config,
            CoordinatorRuntime<GlobalSequenceCoordinatorShard, CoordinatorRecord> runtime,
            GlobalSequenceCoordinatorMetrics coordinatorMetrics,
            GlobalSequenceIndexLogReader indexLogReader
    ) {
        this.log = logContext.logger(GlobalSequenceCoordinator.class);
        this.config = config;
        this.runtime = runtime;
        this.coordinatorMetrics = coordinatorMetrics;
        this.indexLogReader = indexLogReader;
    }

    /**
     * Throws CoordinatorNotAvailableException if the not active.
     */
    private void throwIfNotActive() {
        if (!isActive.get()) {
            throw Errors.COORDINATOR_NOT_AVAILABLE.exception();
        }
    }

    private TopicPartition topicPartitionFor(Uuid topicId) {
        return new TopicPartition(Topic.GLOBAL_SEQUENCE_INDEX_TOPIC_NAME, partitionFor(topicId));
    }

    public int partitionFor(Uuid topicId) {
        throwIfNotActive();
        return Utils.abs(topicId.hashCode()) % numPartitions;
    }

    public Properties globalSequenceIndexTopicConfigs() {
        Properties properties = new Properties();
        properties.put(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_COMPACT);
        properties.put(TopicConfig.COMPRESSION_TYPE_CONFIG, BrokerCompressionType.PRODUCER.name);
        properties.put(TopicConfig.SEGMENT_BYTES_CONFIG, config.indexTopicSegmentBytes());
        properties.put(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, config.indexTopicMinIsr());
        return properties;
    }

    /**
     * Allocates a global offset range for a physical record batch.
     *
     * <p>The request is routed by data topic ID so that all allocations for the same topic are
     * serialized by one global sequence coordinator shard.</p>
     */
    public CompletableFuture<GlobalSequenceAppendResult> appendIndex(
            GlobalSequenceAppendRequest request
    ) {
        throwIfNotActive();
        TopicPartition topicPartition = topicPartitionFor(request.topicId());
        return runtime.<GlobalSequenceAppendPreparation>scheduleReadOperationWithEpoch(
            "prepare-global-sequence-index-append",
            topicPartition,
            (coordinator, indexLogHighWatermark, coordinatorLeaderEpoch) ->
                coordinator.prepareAppend(request, indexLogHighWatermark, coordinatorLeaderEpoch)
        ).thenCompose(preparation -> {
            if (preparation.duplicateResult().isPresent()) {
                return CompletableFuture.completedFuture(preparation.duplicateResult().get());
            }
            if (preparation.scanPlan().isEmpty()) {
                return scheduleAppend(topicPartition, request);
            }

            GlobalSequencePhysicalIndexScanPlan plan = preparation.scanPlan().get();
            return scanPhysicalIndex(topicPartition, plan).thenCompose(scannedIndexRecord ->
                runtime.scheduleWriteOperationWithEpoch(
                    "append-global-sequence-index-after-physical-scan",
                    topicPartition,
                    Duration.ofMillis(config.commitTimeoutMs()),
                    (coordinator, currentHighWatermark, currentCoordinatorLeaderEpoch) ->
                        coordinator.appendIndexAfterScan(
                            plan,
                            scannedIndexRecord,
                            currentHighWatermark,
                            currentCoordinatorLeaderEpoch
                        )
                )
            );
        });
    }

    private CompletableFuture<GlobalSequenceAppendResult> scheduleAppend(
        TopicPartition topicPartition,
        GlobalSequenceAppendRequest request
    ) {
        return runtime.scheduleWriteOperationWithEpoch(
            "append-global-sequence-index",
            topicPartition,
            Duration.ofMillis(config.commitTimeoutMs()),
            (coordinator, indexLogHighWatermark, ignoredCoordinatorLeaderEpoch) ->
                coordinator.appendIndex(request, indexLogHighWatermark)
        );
    }

    private CompletableFuture<Optional<GlobalSequenceIndexRecord>> scanPhysicalIndex(
        TopicPartition topicPartition,
        GlobalSequencePhysicalIndexScanPlan plan
    ) {
        return scanPhysicalIndexChunk(
            topicPartition,
            plan,
            plan.startIndexLogOffset(),
            new PhysicalScanAccumulator(plan)
        );
    }

    private CompletableFuture<Optional<GlobalSequenceIndexRecord>> scanPhysicalIndexChunk(
        TopicPartition topicPartition,
        GlobalSequencePhysicalIndexScanPlan plan,
        long nextLogOffset,
        PhysicalScanAccumulator accumulator
    ) {
        long remainingScanBytes = config.indexLookupMaxScanBytes() - accumulator.bytesRead();
        int readMaxBytes = (int) Math.min(config.indexLogReadMaxBytes(), remainingScanBytes);
        return indexLogReader.read(
            topicPartition,
            nextLogOffset,
            plan.capturedHighWatermark(),
            readMaxBytes
        ).thenCompose(readResult -> {
            coordinatorMetrics.record(GlobalSequenceCoordinatorMetrics.INDEX_LOG_READS_SENSOR_NAME);
            coordinatorMetrics.record(
                GlobalSequenceCoordinatorMetrics.INDEX_LOG_READ_BYTES_SENSOR_NAME,
                readResult.bytesRead()
            );
            accumulator.addBytes(readResult.bytesRead());
            accumulator.add(readResult.entries());

            if (readResult.reachedEndOffset()) {
                return CompletableFuture.completedFuture(accumulator.result());
            }
            if (accumulator.bytesRead() >= config.indexLookupMaxScanBytes()) {
                return CompletableFuture.failedFuture(new InvalidRequestException(
                    "Physical batch index lookup exceeded the configured scan limit of " +
                        config.indexLookupMaxScanBytes() + " bytes."
                ));
            }
            if (readResult.nextLogOffset() <= nextLogOffset) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                    "Global sequence index log reader did not make progress from offset " + nextLogOffset
                ));
            }
            return scanPhysicalIndexChunk(topicPartition, plan, readResult.nextLogOffset(), accumulator);
        });
    }

    /**
     * Resolves a committed global offset range to physical record batches.
     */
    public CompletableFuture<GlobalSequenceLookupResult> lookupIndex(
            GlobalSequenceLookupRequest request
    ) {
        throwIfNotActive();
        TopicPartition topicPartition = topicPartitionFor(request.topicId());
        return runtime.<GlobalSequenceIndexLookupPreparation>scheduleReadOperationWithEpoch(
            "prepare-global-sequence-index-lookup",
            topicPartition,
            (coordinator, indexLogHighWatermark, coordinatorLeaderEpoch) ->
                coordinator.prepareLookup(request, indexLogHighWatermark, coordinatorLeaderEpoch)
        ).thenCompose(preparation -> {
            if (preparation.cachedResult().isPresent()) {
                return CompletableFuture.completedFuture(preparation.cachedResult().get());
            }
            GlobalSequenceIndexScanPlan plan = preparation.scanPlan().orElseThrow();
            ScanAccumulator accumulator = new ScanAccumulator(plan);
            return scanIndex(topicPartition, plan, accumulator).thenCompose(result ->
                runtime.scheduleReadOperationWithEpoch(
                    "complete-global-sequence-index-lookup",
                    topicPartition,
                    (coordinator, ignoredHighWatermark, currentCoordinatorLeaderEpoch) ->
                        coordinator.completeLookup(plan, result, currentCoordinatorLeaderEpoch)
                )
            );
        });
    }

    private CompletableFuture<GlobalSequenceLookupResult> scanIndex(
        TopicPartition topicPartition,
        GlobalSequenceIndexScanPlan plan,
        ScanAccumulator accumulator
    ) {
        return scanIndexChunk(topicPartition, plan, plan.startIndexLogOffset(), accumulator);
    }

    private CompletableFuture<GlobalSequenceLookupResult> scanIndexChunk(
        TopicPartition topicPartition,
        GlobalSequenceIndexScanPlan plan,
        long nextLogOffset,
        ScanAccumulator accumulator
    ) {
        long remainingScanBytes = config.indexLookupMaxScanBytes() - accumulator.bytesRead();
        int readMaxBytes = (int) Math.min(config.indexLogReadMaxBytes(), remainingScanBytes);
        return indexLogReader.read(
            topicPartition,
            nextLogOffset,
            plan.capturedHighWatermark(),
            readMaxBytes
        ).thenCompose(readResult -> {
            coordinatorMetrics.record(GlobalSequenceCoordinatorMetrics.INDEX_LOG_READS_SENSOR_NAME);
            coordinatorMetrics.record(
                GlobalSequenceCoordinatorMetrics.INDEX_LOG_READ_BYTES_SENSOR_NAME,
                readResult.bytesRead()
            );
            accumulator.addBytes(readResult.bytesRead());
            accumulator.add(readResult.entries());

            Optional<GlobalSequenceLookupResult> resolved = accumulator.resolve(readResult.reachedEndOffset());
            if (resolved.isPresent()) {
                return CompletableFuture.completedFuture(resolved.get());
            }
            if (accumulator.bytesRead() >= config.indexLookupMaxScanBytes()) {
                return CompletableFuture.failedFuture(new InvalidRequestException(
                    "Global sequence index lookup exceeded the configured scan limit of " +
                        config.indexLookupMaxScanBytes() + " bytes."
                ));
            }
            if (readResult.nextLogOffset() <= nextLogOffset) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                    "Global sequence index log reader did not make progress from offset " + nextLogOffset
                ));
            }
            return scanIndexChunk(topicPartition, plan, readResult.nextLogOffset(), accumulator);
        });
    }

    public void onElection(
            int indexMetadataPartitionIndex,
            int indexMetadataPartitionLeaderEpoch
    ) {
        throwIfNotActive();
        runtime.scheduleLoadOperation(
            new TopicPartition(Topic.GLOBAL_SEQUENCE_INDEX_TOPIC_NAME, indexMetadataPartitionIndex),
            indexMetadataPartitionLeaderEpoch
        );
    }

    public void onResignation(
            int indexMetadataPartitionIndex,
            OptionalInt indexMetadataPartitionLeaderEpoch
    ) {
        throwIfNotActive();
        runtime.scheduleUnloadOperation(
            new TopicPartition(Topic.GLOBAL_SEQUENCE_INDEX_TOPIC_NAME, indexMetadataPartitionIndex),
            indexMetadataPartitionLeaderEpoch
        );
    }

    public void onNewMetadataImage(
            MetadataImage newImage,
            MetadataDelta delta
    ) {
        throwIfNotActive();
        runtime.onNewMetadataImage(newImage, delta);
    }

    public void startup(IntSupplier globalSequenceIndexTopicPartitionCount) {
        if (!isActive.compareAndSet(false, true)) {
            log.warn("Global sequence coordinator is already running.");
            return;
        }

        log.info("Starting up.");
        numPartitions = globalSequenceIndexTopicPartitionCount.getAsInt();
        isActive.set(true);
        log.info("Startup complete.");
    }

    public void shutdown() {
        if (!isActive.compareAndSet(true, false)) {
            log.warn("Global sequence coordinator is already shutting down.");
            return;
        }

        log.info("Shutting down.");
        isActive.set(false);
        Utils.closeQuietly(indexLogReader, "global sequence index log reader");
        Utils.closeQuietly(runtime, "coordinator runtime");
        Utils.closeQuietly(coordinatorMetrics, "global sequence coordinator metrics");
        log.info("Shutdown complete.");
    }

    private static final class PhysicalScanAccumulator {
        private final PhysicalBatchId physicalBatchId;
        private GlobalSequenceIndexRecord candidate;
        private long bytesRead;

        private PhysicalScanAccumulator(GlobalSequencePhysicalIndexScanPlan plan) {
            this.physicalBatchId = plan.request().physicalBatchId();
        }

        private void addBytes(int bytes) {
            bytesRead = Math.addExact(bytesRead, bytes);
        }

        private long bytesRead() {
            return bytesRead;
        }

        private void add(List<GlobalSequenceIndexLogEntry> entries) {
            for (GlobalSequenceIndexLogEntry entry : entries) {
                if (!physicalBatchId.topicId().equals(entry.topicId())) {
                    continue;
                }
                if (entry.indexRecord().isEmpty()) {
                    if (candidate != null && candidate.globalBaseOffset() == entry.globalBaseOffset()) {
                        candidate = null;
                    }
                    continue;
                }

                GlobalSequenceIndexRecord indexRecord = entry.indexRecord().get();
                if (indexRecord.partitionIndex() != physicalBatchId.partitionIndex() ||
                    indexRecord.partitionBaseOffset() != physicalBatchId.partitionBaseOffset()) {
                    continue;
                }
                if (candidate != null && !candidate.equals(indexRecord)) {
                    throw new IllegalStateException(
                        "Conflicting global sequence allocations for physical batch " + physicalBatchId
                    );
                }
                candidate = indexRecord;
            }
        }

        private Optional<GlobalSequenceIndexRecord> result() {
            return Optional.ofNullable(candidate);
        }
    }

    private static final class ScanAccumulator {
        private final GlobalSequenceLookupRequest request;
        private final int maxIndexEntries;
        private final TreeMap<Long, GlobalSequenceIndexRecord> candidates = new TreeMap<>();
        private long bytesRead;

        private ScanAccumulator(GlobalSequenceIndexScanPlan plan) {
            this.request = plan.request();
            this.maxIndexEntries = plan.maxIndexEntries();
        }

        private void addBytes(int bytes) {
            bytesRead = Math.addExact(bytesRead, bytes);
        }

        private long bytesRead() {
            return bytesRead;
        }

        private void add(List<GlobalSequenceIndexLogEntry> entries) {
            for (GlobalSequenceIndexLogEntry entry : entries) {
                if (!request.topicId().equals(entry.topicId())) {
                    continue;
                }
                if (entry.indexRecord().isEmpty()) {
                    candidates.remove(entry.globalBaseOffset());
                    continue;
                }
                GlobalSequenceIndexRecord indexRecord = entry.indexRecord().get();
                if (indexRecord.globalEndOffsetExclusive() > request.globalStartOffset() &&
                    indexRecord.globalBaseOffset() < request.globalEndOffsetExclusive()) {
                    candidates.put(indexRecord.globalBaseOffset(), indexRecord);
                }
            }
        }

        private Optional<GlobalSequenceLookupResult> resolve(boolean reachedEndOffset) {
            Map.Entry<Long, GlobalSequenceIndexRecord> current = candidates.floorEntry(
                request.globalStartOffset()
            );
            if (current == null ||
                current.getValue().globalEndOffsetExclusive() <= request.globalStartOffset()) {
                if ((!candidates.isEmpty() && candidates.firstKey() > request.globalStartOffset()) || reachedEndOffset) {
                    throw GlobalSequenceStateRegistry.outOfRange(request, request.globalStartOffset());
                }
                return Optional.empty();
            }

            List<GlobalSequenceIndexRecord> matches = new java.util.ArrayList<>();
            long nextOffsetToCover = request.globalStartOffset();
            while (current != null) {
                GlobalSequenceIndexRecord indexRecord = current.getValue();
                if (indexRecord.globalBaseOffset() > nextOffsetToCover) {
                    throw GlobalSequenceStateRegistry.outOfRange(request, nextOffsetToCover);
                }
                if (indexRecord.globalEndOffsetExclusive() > nextOffsetToCover) {
                    matches.add(indexRecord);
                    nextOffsetToCover = Math.min(
                        indexRecord.globalEndOffsetExclusive(),
                        request.globalEndOffsetExclusive()
                    );
                    if (nextOffsetToCover == request.globalEndOffsetExclusive() ||
                        matches.size() == maxIndexEntries) {
                        return Optional.of(new GlobalSequenceLookupResult(matches));
                    }
                }
                current = candidates.higherEntry(current.getKey());
            }

            if (reachedEndOffset) {
                throw GlobalSequenceStateRegistry.outOfRange(request, nextOffsetToCover);
            }
            return Optional.empty();
        }
    }
}
