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

import org.apache.kafka.common.config.AbstractConfig;
import org.apache.kafka.common.config.ConfigDef;

import static org.apache.kafka.common.config.ConfigDef.Importance.HIGH;
import static org.apache.kafka.common.config.ConfigDef.Range.atLeast;
import static org.apache.kafka.common.config.ConfigDef.Type.INT;
import static org.apache.kafka.common.config.ConfigDef.Type.SHORT;

public class GlobalSequenceCoordinatorConfig {

    public static final String COMMIT_TIMEOUT_MS_CONFIG = "global.sequence.commit.timeout.ms";
    public static final int COMMIT_TIMEOUT_MS_DEFAULT = 5000;
    public static final String COMMIT_TIMEOUT_MS_DOC = "Index of global sequence enabled topic commit will be delayed until all replicas " +
            "for the index topic receive the commit or this timeout is reached. This is similar to producer request timeout.";

    public static final String NUM_INDEX_PARTITIONS_CONFIG = "global.sequence.topic.num.partitions";
    public static final int NUM_INDEX_PARTITIONS_DEFAULT = 50;
    public static final String NUM_INDEX_PARTITIONS_DOC = "The initial number of partitions for the global sequence enabled topic.";

    public static final String INDEX_TOPIC_REPLICATION_FACTOR_CONFIG = "global.sequence.topic.replication.factor";
    public static final short INDEX_TOPIC_REPLICATION_FACTOR_DEFAULT = 3;
    public static final String INDEX_TOPIC_REPLICATION_FACTOR_DOC = "Replication factor for the global sequence index topic. " +
            "Topic creation will fail until the cluster size meets this replication factor requirement.";

    public static final String INDEX_TOPIC_MIN_ISR_CONFIG = "global.sequence.topic.min.isr";
    public static final short INDEX_TOPIC_MIN_ISR_DEFAULT = 2;
    public static final String INDEX_TOPIC_MIN_ISR_DOC = "Overridden min.insync.replicas for the global sequence index topic.";

    public static final String INDEX_TOPIC_SEGMENT_BYTES_CONFIG = "global.sequence.topic.segment.bytes";
    public static final int INDEX_TOPIC_SEGMENT_BYTES_DEFAULT = 100 * 1024 * 1024;
    public static final String INDEX_TOPIC_SEGMENT_BYTES_DOC = "The log segment size for the global sequence index topic.";

    public static final String MAX_LOOKUP_INDEX_ENTRIES_CONFIG = "global.sequence.lookup.max.index.entries";
    public static final int MAX_LOOKUP_INDEX_ENTRIES_DEFAULT = 1024;
    public static final String MAX_LOOKUP_INDEX_ENTRIES_DOC = "The maximum number of index entries returned by one " +
        "global sequence lookup. Requests asking for more entries are capped at this value.";

    public static final String MAX_CONCURRENT_PHYSICAL_FETCHES_CONFIG =
        "global.sequence.fetch.max.concurrent.physical.requests";
    public static final int MAX_CONCURRENT_PHYSICAL_FETCHES_DEFAULT = 8;
    public static final String MAX_CONCURRENT_PHYSICAL_FETCHES_DOC = "The maximum number of physical data reads " +
        "issued concurrently by one global sequence fetch.";

    public static final String INDEX_LOG_READ_MAX_BYTES_CONFIG = "global.sequence.index.log.read.max.bytes";
    public static final int INDEX_LOG_READ_MAX_BYTES_DEFAULT = 1024 * 1024;
    public static final String INDEX_LOG_READ_MAX_BYTES_DOC = "The maximum bytes read from a global sequence " +
        "index partition in one lazy lookup I/O operation.";

    public static final String INDEX_CHECKPOINT_INTERVAL_CONFIG = "global.sequence.index.checkpoint.interval";
    public static final int INDEX_CHECKPOINT_INTERVAL_DEFAULT = 1024;
    public static final String INDEX_CHECKPOINT_INTERVAL_DOC = "The number of topic allocations between " +
        "checkpoints in the newest sparse index level.";

    public static final String INDEX_CHECKPOINT_LEVEL_FACTOR_CONFIG = "global.sequence.index.checkpoint.level.factor";
    public static final int INDEX_CHECKPOINT_LEVEL_FACTOR_DEFAULT = 16;
    public static final String INDEX_CHECKPOINT_LEVEL_FACTOR_DOC = "The geometric spacing and width factor used " +
        "to retain progressively sparser checkpoints for older index ranges.";

    public static final String INDEX_CACHE_MAX_ENTRIES_CONFIG = "global.sequence.index.cache.max.entries";
    public static final int INDEX_CACHE_MAX_ENTRIES_DEFAULT = 100_000;
    public static final String INDEX_CACHE_MAX_ENTRIES_DOC = "The maximum number of committed global sequence " +
        "index records cached in memory per coordinator shard.";

    public static final ConfigDef CONFIG_DEF =  new ConfigDef()
            .define(COMMIT_TIMEOUT_MS_CONFIG, INT, COMMIT_TIMEOUT_MS_DEFAULT, atLeast(1), HIGH, COMMIT_TIMEOUT_MS_DOC)
            .define(NUM_INDEX_PARTITIONS_CONFIG, INT, NUM_INDEX_PARTITIONS_DEFAULT, atLeast(1), HIGH, NUM_INDEX_PARTITIONS_DOC)
            .define(INDEX_TOPIC_REPLICATION_FACTOR_CONFIG, SHORT, INDEX_TOPIC_REPLICATION_FACTOR_DEFAULT,
                    atLeast(1), HIGH, INDEX_TOPIC_REPLICATION_FACTOR_DOC)
            .define(INDEX_TOPIC_MIN_ISR_CONFIG, SHORT, INDEX_TOPIC_MIN_ISR_DEFAULT,
                    atLeast(1), HIGH, INDEX_TOPIC_MIN_ISR_DOC)
            .define(INDEX_TOPIC_SEGMENT_BYTES_CONFIG, INT, INDEX_TOPIC_SEGMENT_BYTES_DEFAULT,
                    atLeast(1), HIGH, INDEX_TOPIC_SEGMENT_BYTES_DOC)
            .define(MAX_LOOKUP_INDEX_ENTRIES_CONFIG, INT, MAX_LOOKUP_INDEX_ENTRIES_DEFAULT,
                    atLeast(1), HIGH, MAX_LOOKUP_INDEX_ENTRIES_DOC)
            .define(MAX_CONCURRENT_PHYSICAL_FETCHES_CONFIG, INT, MAX_CONCURRENT_PHYSICAL_FETCHES_DEFAULT,
                    atLeast(1), HIGH, MAX_CONCURRENT_PHYSICAL_FETCHES_DOC)
            .define(INDEX_LOG_READ_MAX_BYTES_CONFIG, INT, INDEX_LOG_READ_MAX_BYTES_DEFAULT,
                    atLeast(1), HIGH, INDEX_LOG_READ_MAX_BYTES_DOC)
            .define(INDEX_CHECKPOINT_INTERVAL_CONFIG, INT, INDEX_CHECKPOINT_INTERVAL_DEFAULT,
                    atLeast(1), HIGH, INDEX_CHECKPOINT_INTERVAL_DOC)
            .define(INDEX_CHECKPOINT_LEVEL_FACTOR_CONFIG, INT, INDEX_CHECKPOINT_LEVEL_FACTOR_DEFAULT,
                    atLeast(2), HIGH, INDEX_CHECKPOINT_LEVEL_FACTOR_DOC)
            .define(INDEX_CACHE_MAX_ENTRIES_CONFIG, INT, INDEX_CACHE_MAX_ENTRIES_DEFAULT,
                    atLeast(1), HIGH, INDEX_CACHE_MAX_ENTRIES_DOC);

    private final int commitTimeoutMs;
    private final int numIndexPartitions;
    private final short indexTopicReplicationFactor;
    private final short indexTopicMinIsr;
    private final int indexTopicSegmentBytes;
    private final int maxLookupIndexEntries;
    private final int maxConcurrentPhysicalFetches;
    private final int indexLogReadMaxBytes;
    private final int indexCheckpointInterval;
    private final int indexCheckpointLevelFactor;
    private final int indexCacheMaxEntries;

    public GlobalSequenceCoordinatorConfig(AbstractConfig config) {
        this.commitTimeoutMs = config.getInt(COMMIT_TIMEOUT_MS_CONFIG);
        this.numIndexPartitions = config.getInt(NUM_INDEX_PARTITIONS_CONFIG);
        this.indexTopicReplicationFactor = config.getShort(INDEX_TOPIC_REPLICATION_FACTOR_CONFIG);
        this.indexTopicMinIsr = config.getShort(INDEX_TOPIC_MIN_ISR_CONFIG);
        this.indexTopicSegmentBytes = config.getInt(INDEX_TOPIC_SEGMENT_BYTES_CONFIG);
        this.maxLookupIndexEntries = config.getInt(MAX_LOOKUP_INDEX_ENTRIES_CONFIG);
        this.maxConcurrentPhysicalFetches = config.getInt(MAX_CONCURRENT_PHYSICAL_FETCHES_CONFIG);
        this.indexLogReadMaxBytes = config.getInt(INDEX_LOG_READ_MAX_BYTES_CONFIG);
        this.indexCheckpointInterval = config.getInt(INDEX_CHECKPOINT_INTERVAL_CONFIG);
        this.indexCheckpointLevelFactor = config.getInt(INDEX_CHECKPOINT_LEVEL_FACTOR_CONFIG);
        this.indexCacheMaxEntries = config.getInt(INDEX_CACHE_MAX_ENTRIES_CONFIG);
    }

    public int commitTimeoutMs() {
        return commitTimeoutMs;
    }

    public int numIndexPartitions() {
        return numIndexPartitions;
    }

    public short indexTopicReplicationFactor() {
        return indexTopicReplicationFactor;
    }

    public short indexTopicMinIsr() {
        return indexTopicMinIsr;
    }

    public int indexTopicSegmentBytes() {
        return indexTopicSegmentBytes;
    }

    public int maxLookupIndexEntries() {
        return maxLookupIndexEntries;
    }

    public int maxConcurrentPhysicalFetches() {
        return maxConcurrentPhysicalFetches;
    }

    public int indexLogReadMaxBytes() {
        return indexLogReadMaxBytes;
    }

    public int indexCheckpointInterval() {
        return indexCheckpointInterval;
    }

    public int indexCheckpointLevelFactor() {
        return indexCheckpointLevelFactor;
    }

    public int indexCacheMaxEntries() {
        return indexCacheMaxEntries;
    }
}
