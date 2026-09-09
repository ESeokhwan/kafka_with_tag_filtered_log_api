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
import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.metrics.Gauge;
import org.apache.kafka.common.metrics.Metrics;
import org.apache.kafka.common.metrics.Sensor;
import org.apache.kafka.common.metrics.stats.Avg;
import org.apache.kafka.common.metrics.stats.Max;
import org.apache.kafka.common.metrics.stats.Meter;
import org.apache.kafka.common.utils.Utils;
import org.apache.kafka.coordinator.common.runtime.CoordinatorMetrics;
import org.apache.kafka.coordinator.common.runtime.CoordinatorMetricsShard;
import org.apache.kafka.timeline.SnapshotRegistry;

import com.yammer.metrics.core.MetricsRegistry;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public class GlobalSequenceCoordinatorMetrics extends CoordinatorMetrics implements AutoCloseable {

    public static final String METRICS_GROUP = "global-sequence-coordinator-metrics";
    public static final String INDEX_ALLOCATIONS_SENSOR_NAME = "GlobalSequenceIndexAllocations";
    public static final String INDEX_LOOKUP_ENTRIES_SENSOR_NAME = "GlobalSequenceIndexLookupEntries";
    public static final String INDEX_LOOKUP_PAGINATIONS_SENSOR_NAME = "GlobalSequenceIndexLookupPaginations";

    public static final com.yammer.metrics.core.MetricName NUM_RETAINED_ALLOCATIONS = getMetricName(
        "kafka.coordinator.globalsequence",
        "GlobalSequenceStateRegistry",
        "NumRetainedAllocations"
    );

    private final MetricsRegistry registry;
    private final Metrics metrics;
    private final Map<TopicPartition, GlobalSequenceCoordinatorMetricsShard> shards = new ConcurrentHashMap<>();
    private final MetricName retainedAllocationCountMetricName;

    public final Map<String, Sensor> globalSensors;

    public GlobalSequenceCoordinatorMetrics(MetricsRegistry registry, Metrics metrics) {
        this.registry = Objects.requireNonNull(registry);
        this.metrics = Objects.requireNonNull(metrics);
        this.retainedAllocationCountMetricName = metrics.metricName(
            "retained-allocation-count",
            METRICS_GROUP,
            "The number of allocation records retained in coordinator memory."
        );

        registerGauges();

        Sensor allocations = metrics.sensor(INDEX_ALLOCATIONS_SENSOR_NAME);
        allocations.add(new Meter(
            metrics.metricName(
                "index-allocation-rate",
                METRICS_GROUP,
                "The rate of newly replayed global sequence index allocations."
            ),
            metrics.metricName(
                "index-allocation-total",
                METRICS_GROUP,
                "The total number of newly replayed global sequence index allocations."
            )
        ));

        Sensor lookupEntries = metrics.sensor(INDEX_LOOKUP_ENTRIES_SENSOR_NAME);
        lookupEntries.add(
            metrics.metricName(
                "lookup-index-entries-avg",
                METRICS_GROUP,
                "The average number of index entries returned by a lookup."
            ),
            new Avg()
        );
        lookupEntries.add(
            metrics.metricName(
                "lookup-index-entries-max",
                METRICS_GROUP,
                "The maximum number of index entries returned by a lookup."
            ),
            new Max()
        );

        Sensor lookupPaginations = metrics.sensor(INDEX_LOOKUP_PAGINATIONS_SENSOR_NAME);
        lookupPaginations.add(new Meter(
            metrics.metricName(
                "lookup-pagination-rate",
                METRICS_GROUP,
                "The rate of lookups which require another index page."
            ),
            metrics.metricName(
                "lookup-pagination-total",
                METRICS_GROUP,
                "The total number of lookups which require another index page."
            )
        ));

        globalSensors = Map.copyOf(Utils.mkMap(
            Utils.mkEntry(INDEX_ALLOCATIONS_SENSOR_NAME, allocations),
            Utils.mkEntry(INDEX_LOOKUP_ENTRIES_SENSOR_NAME, lookupEntries),
            Utils.mkEntry(INDEX_LOOKUP_PAGINATIONS_SENSOR_NAME, lookupPaginations)
        ));
    }

    private void registerGauges() {
        registry.newGauge(NUM_RETAINED_ALLOCATIONS, new com.yammer.metrics.core.Gauge<>() {
            @Override
            public Long value() {
                return numRetainedAllocations();
            }
        });
        metrics.addMetric(
            retainedAllocationCountMetricName,
            (Gauge<Long>) (config, now) -> numRetainedAllocations()
        );
    }

    @Override
    public void close() {
        registry.removeMetric(NUM_RETAINED_ALLOCATIONS);
        metrics.removeMetric(retainedAllocationCountMetricName);
        List.of(
            INDEX_ALLOCATIONS_SENSOR_NAME,
            INDEX_LOOKUP_ENTRIES_SENSOR_NAME,
            INDEX_LOOKUP_PAGINATIONS_SENSOR_NAME
        ).forEach(metrics::removeSensor);
    }

    @Override
    public GlobalSequenceCoordinatorMetricsShard newMetricsShard(SnapshotRegistry snapshotRegistry, TopicPartition tp) {
        return new GlobalSequenceCoordinatorMetricsShard(snapshotRegistry, globalSensors, tp);
    }

    @Override
    public void activateMetricsShard(CoordinatorMetricsShard shard) {
        if (!(shard instanceof GlobalSequenceCoordinatorMetricsShard)) {
            throw new IllegalArgumentException(
                "GlobalSequenceCoordinatorMetrics can only activate GlobalSequenceCoordinatorMetricsShard"
            );
        }
        shards.put(shard.topicPartition(), (GlobalSequenceCoordinatorMetricsShard) shard);
    }

    @Override
    public void deactivateMetricsShard(CoordinatorMetricsShard shard) {
        shards.remove(shard.topicPartition());
    }

    @Override
    public MetricsRegistry registry() {
        return registry;
    }

    @Override
    public void onUpdateLastCommittedOffset(TopicPartition tp, long offset) {
        CoordinatorMetricsShard shard = shards.get(tp);
        if (shard != null) {
            shard.commitUpTo(offset);
        }
    }

    public long numRetainedAllocations() {
        return shards.values().stream()
            .mapToLong(GlobalSequenceCoordinatorMetricsShard::numRetainedAllocations)
            .sum();
    }
}
