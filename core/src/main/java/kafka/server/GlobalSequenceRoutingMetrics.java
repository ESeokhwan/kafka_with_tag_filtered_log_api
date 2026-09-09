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
package kafka.server;

import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.metrics.Gauge;
import org.apache.kafka.common.metrics.Metrics;
import org.apache.kafka.common.metrics.Sensor;
import org.apache.kafka.common.metrics.stats.Avg;
import org.apache.kafka.common.metrics.stats.Max;
import org.apache.kafka.common.metrics.stats.Meter;

import java.util.concurrent.atomic.AtomicInteger;

final class GlobalSequenceRoutingMetrics implements AutoCloseable {
    static final String METRICS_GROUP = "global-sequence-routing-metrics";
    static final String PHYSICAL_FETCH_SENSOR_NAME = "GlobalSequencePhysicalFetch";
    static final String PHYSICAL_FETCH_LATENCY_SENSOR_NAME = "GlobalSequencePhysicalFetchLatency";

    private final Metrics metrics;
    private final MetricName physicalFetchInFlightMetricName;
    private final Sensor physicalFetchSensor;
    private final Sensor physicalFetchLatencySensor;
    private final AtomicInteger physicalFetchesInFlight = new AtomicInteger(0);

    GlobalSequenceRoutingMetrics(Metrics metrics) {
        this.metrics = metrics;
        this.physicalFetchInFlightMetricName = metrics.metricName(
            "physical-fetch-in-flight",
            METRICS_GROUP,
            "The number of physical data reads currently in flight."
        );
        metrics.addMetric(
            physicalFetchInFlightMetricName,
            (Gauge<Integer>) (config, now) -> physicalFetchesInFlight.get()
        );

        this.physicalFetchSensor = metrics.sensor(PHYSICAL_FETCH_SENSOR_NAME);
        physicalFetchSensor.add(new Meter(
            metrics.metricName(
                "physical-fetch-rate",
                METRICS_GROUP,
                "The rate of physical data reads."
            ),
            metrics.metricName(
                "physical-fetch-total",
                METRICS_GROUP,
                "The total number of physical data reads."
            )
        ));
        this.physicalFetchLatencySensor = metrics.sensor(PHYSICAL_FETCH_LATENCY_SENSOR_NAME);
        physicalFetchLatencySensor.add(
            metrics.metricName(
                "physical-fetch-latency-avg",
                METRICS_GROUP,
                "The average physical data read latency in milliseconds."
            ),
            new Avg()
        );
        physicalFetchLatencySensor.add(
            metrics.metricName(
                "physical-fetch-latency-max",
                METRICS_GROUP,
                "The maximum physical data read latency in milliseconds."
            ),
            new Max()
        );
    }

    void recordPhysicalFetchStarted() {
        physicalFetchesInFlight.incrementAndGet();
    }

    void recordPhysicalFetchCompleted(long latencyMs) {
        physicalFetchesInFlight.decrementAndGet();
        physicalFetchSensor.record();
        physicalFetchLatencySensor.record(latencyMs);
    }

    @Override
    public void close() {
        metrics.removeMetric(physicalFetchInFlightMetricName);
        metrics.removeSensor(PHYSICAL_FETCH_SENSOR_NAME);
        metrics.removeSensor(PHYSICAL_FETCH_LATENCY_SENSOR_NAME);
    }
}
