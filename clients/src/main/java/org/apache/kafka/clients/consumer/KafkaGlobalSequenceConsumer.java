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
package org.apache.kafka.clients.consumer;

import org.apache.kafka.clients.ApiVersions;
import org.apache.kafka.clients.ClientUtils;
import org.apache.kafka.clients.consumer.internals.ConsumerMetadata;
import org.apache.kafka.clients.consumer.internals.ConsumerNetworkClient;
import org.apache.kafka.clients.consumer.internals.ConsumerUtils;
import org.apache.kafka.clients.consumer.internals.Deserializers;
import org.apache.kafka.clients.consumer.internals.FetchMetricsManager;
import org.apache.kafka.clients.consumer.internals.GlobalSequenceFetcher;
import org.apache.kafka.clients.consumer.internals.GlobalSequenceResponseDecoder;
import org.apache.kafka.clients.consumer.internals.SubscriptionState;
import org.apache.kafka.common.IsolationLevel;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.internals.ClusterResourceListeners;
import org.apache.kafka.common.metrics.Metrics;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.utils.LogContext;
import org.apache.kafka.common.utils.Time;
import org.apache.kafka.common.utils.Utils;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The default {@link GlobalSequenceConsumer} implementation.
 *
 * <p>The standard consumer network, security, deserializer, isolation, and
 * fetch-size configurations are used. Group membership and offset commit
 * configurations are ignored because every fetch supplies an explicit global
 * offset range.</p>
 */
public class KafkaGlobalSequenceConsumer<K, V> implements GlobalSequenceConsumer<K, V> {
    private static final long NO_CURRENT_THREAD = -1L;
    private static final Duration DEFAULT_CLOSE_TIMEOUT = Duration.ofSeconds(30);

    private final Metrics metrics;
    private final Deserializers<K, V> deserializers;
    private final ConsumerMetadata metadata;
    private final ConsumerNetworkClient client;
    private final GlobalSequenceResponseDecoder<K, V> decoder;
    private final GlobalSequenceFetcher<K, V> fetcher;
    private final AtomicLong currentThread = new AtomicLong(NO_CURRENT_THREAD);
    private final AtomicInteger refcount = new AtomicInteger(0);
    private volatile boolean closed = false;

    public KafkaGlobalSequenceConsumer(Map<String, Object> configs) {
        this(configs, null, null);
    }

    public KafkaGlobalSequenceConsumer(Properties properties) {
        this(Utils.propsToMap(properties), null, null);
    }

    public KafkaGlobalSequenceConsumer(
        Properties properties,
        Deserializer<K> keyDeserializer,
        Deserializer<V> valueDeserializer
    ) {
        this(Utils.propsToMap(properties), keyDeserializer, valueDeserializer);
    }

    public KafkaGlobalSequenceConsumer(
        Map<String, Object> configs,
        Deserializer<K> keyDeserializer,
        Deserializer<V> valueDeserializer
    ) {
        this(new ConsumerConfig(ConsumerConfig.appendDeserializerToConfig(
            configs,
            keyDeserializer,
            valueDeserializer
        )), keyDeserializer, valueDeserializer);
    }

    KafkaGlobalSequenceConsumer(
        ConsumerConfig config,
        Deserializer<K> keyDeserializer,
        Deserializer<V> valueDeserializer
    ) {
        Time time = Time.SYSTEM;
        String clientId = config.getString(ConsumerConfig.CLIENT_ID_CONFIG);
        LogContext logContext = new LogContext("[GlobalSequenceConsumer clientId=" + clientId + "] ");
        Metrics createdMetrics = null;
        Deserializers<K, V> createdDeserializers = null;
        ConsumerMetadata createdMetadata = null;
        ConsumerNetworkClient createdClient = null;
        GlobalSequenceResponseDecoder<K, V> createdDecoder = null;
        try {
            createdMetrics = ConsumerUtils.createMetrics(config, time);
            createdDeserializers = new Deserializers<>(config, keyDeserializer, valueDeserializer, createdMetrics);
            SubscriptionState subscriptions = ConsumerUtils.createSubscriptionState(config, logContext);
            ClusterResourceListeners clusterResourceListeners = ClientUtils.configureClusterResourceListeners(
                createdMetrics.reporters(),
                Collections.emptyList(),
                Arrays.asList(createdDeserializers.keyDeserializer(), createdDeserializers.valueDeserializer())
            );
            createdMetadata = new ConsumerMetadata(config, subscriptions, logContext, clusterResourceListeners);
            List<InetSocketAddress> addresses = ClientUtils.parseAndValidateAddresses(config);
            createdMetadata.bootstrap(addresses);
            FetchMetricsManager fetchMetricsManager = ConsumerUtils.createFetchMetricsManager(createdMetrics);
            createdClient = ConsumerUtils.createConsumerNetworkClient(
                config,
                createdMetrics,
                logContext,
                new ApiVersions(),
                time,
                createdMetadata,
                fetchMetricsManager.throttleTimeSensor(),
                config.getLong(ConsumerConfig.RETRY_BACKOFF_MS_CONFIG),
                null
            );
            IsolationLevel isolationLevel = ConsumerUtils.configuredIsolationLevel(config);
            createdDecoder = new GlobalSequenceResponseDecoder<>(
                createdDeserializers.keyDeserializer(),
                createdDeserializers.valueDeserializer(),
                isolationLevel,
                config.getBoolean(ConsumerConfig.CHECK_CRCS_CONFIG)
            );
            this.fetcher = new GlobalSequenceFetcher<>(
                createdClient,
                createdMetadata,
                createdDecoder,
                time,
                config.getLong(ConsumerConfig.RETRY_BACKOFF_MS_CONFIG),
                config.getLong(ConsumerConfig.RETRY_BACKOFF_MAX_MS_CONFIG),
                config.getInt(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG),
                config.getInt(ConsumerConfig.FETCH_MAX_BYTES_CONFIG),
                isolationLevel
            );
        } catch (Throwable exception) {
            closeCreatedResources(
                createdClient,
                createdMetadata,
                createdDecoder,
                createdDeserializers,
                createdMetrics
            );
            throw new KafkaException("Failed to construct global sequence consumer", exception);
        }
        this.metrics = createdMetrics;
        this.deserializers = createdDeserializers;
        this.metadata = createdMetadata;
        this.client = createdClient;
        this.decoder = createdDecoder;
    }

    @Override
    public GlobalSequenceFetchResult<K, V> fetch(
        String topic,
        long globalStartOffset,
        long globalEndOffsetExclusive,
        Duration timeout
    ) {
        acquireAndEnsureOpen();
        try {
            return fetcher.fetch(topic, globalStartOffset, globalEndOffsetExclusive, timeout);
        } finally {
            release();
        }
    }

    @Override
    public void wakeup() {
        client.wakeup();
    }

    @Override
    public void close() {
        close(DEFAULT_CLOSE_TIMEOUT);
    }

    @Override
    public void close(Duration timeout) {
        if (timeout == null) {
            throw new IllegalArgumentException("timeout must not be null");
        }
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative");
        }
        acquire();
        try {
            if (closed) {
                return;
            }
            closed = true;
            client.disableWakeups();
            AtomicReference<Throwable> firstException = new AtomicReference<>();
            Utils.closeQuietly(client, "global sequence network client", firstException);
            Utils.closeQuietly(metadata, "global sequence metadata", firstException);
            Utils.closeQuietly(decoder, "global sequence response decoder", firstException);
            Utils.closeQuietly(deserializers, "global sequence deserializers", firstException);
            Utils.closeQuietly(metrics, "global sequence consumer metrics", firstException);
            if (firstException.get() != null) {
                throw new KafkaException("Failed to close global sequence consumer", firstException.get());
            }
        } finally {
            release();
        }
    }

    private void acquireAndEnsureOpen() {
        acquire();
        if (closed) {
            release();
            throw new IllegalStateException("This global sequence consumer has already been closed");
        }
    }

    private void acquire() {
        Thread thread = Thread.currentThread();
        long threadId = thread.getId();
        if (threadId != currentThread.get() && !currentThread.compareAndSet(NO_CURRENT_THREAD, threadId)) {
            throw new ConcurrentModificationException(
                "KafkaGlobalSequenceConsumer is not safe for multi-threaded access"
            );
        }
        refcount.incrementAndGet();
    }

    private void release() {
        if (refcount.decrementAndGet() == 0) {
            currentThread.set(NO_CURRENT_THREAD);
        }
    }

    private static void closeCreatedResources(
        ConsumerNetworkClient client,
        ConsumerMetadata metadata,
        GlobalSequenceResponseDecoder<?, ?> decoder,
        Deserializers<?, ?> deserializers,
        Metrics metrics
    ) {
        AtomicReference<Throwable> firstException = new AtomicReference<>();
        Utils.closeQuietly(client, "global sequence network client", firstException);
        Utils.closeQuietly(metadata, "global sequence metadata", firstException);
        Utils.closeQuietly(decoder, "global sequence response decoder", firstException);
        Utils.closeQuietly(deserializers, "global sequence deserializers", firstException);
        Utils.closeQuietly(metrics, "global sequence consumer metrics", firstException);
    }
}
