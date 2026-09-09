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
import org.apache.kafka.common.metrics.Sensor;
import org.apache.kafka.coordinator.common.runtime.CoordinatorMetricsShard;
import org.apache.kafka.timeline.SnapshotRegistry;
import org.apache.kafka.timeline.TimelineLong;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

public class GlobalSequenceCoordinatorMetricsShard implements CoordinatorMetricsShard {

    private final Map<String, Sensor> globalSensors;

    private final TopicPartition topicPartition;
    private final TimelineLong retainedAllocations;
    private final AtomicLong committedRetainedAllocations = new AtomicLong(0L);

    public GlobalSequenceCoordinatorMetricsShard(
            SnapshotRegistry snapshotRegistry,
            Map<String, Sensor> globalSensors,
            TopicPartition topicPartition
    ) {
        retainedAllocations = new TimelineLong(Objects.requireNonNull(snapshotRegistry));

        this.globalSensors = Objects.requireNonNull(globalSensors);
        this.topicPartition = Objects.requireNonNull(topicPartition);
    }

    @Override
    public void record(String sensorName) {
        Sensor sensor = globalSensors.get(sensorName);
        if (sensor != null) {
            sensor.record();
        }
    }

    @Override
    public void record(String sensorName, double val) {
        Sensor sensor = globalSensors.get(sensorName);
        if (sensor != null) {
            sensor.record(val);
        }
    }

    @Override
    public TopicPartition topicPartition() {
        return topicPartition;
    }

    @Override
    public void commitUpTo(long offset) {
        synchronized (retainedAllocations) {
            committedRetainedAllocations.set(retainedAllocations.get(offset));
        }
    }

    public void incrementRetainedAllocations() {
        addRetainedAllocations(1L);
    }

    public void addRetainedAllocations(long delta) {
        synchronized (retainedAllocations) {
            retainedAllocations.set(Math.addExact(retainedAllocations.get(), delta));
        }
    }

    public long numRetainedAllocations() {
        return committedRetainedAllocations.get();
    }
}
