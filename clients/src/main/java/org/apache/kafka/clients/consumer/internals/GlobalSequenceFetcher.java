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
package org.apache.kafka.clients.consumer.internals;

import org.apache.kafka.clients.ClientResponse;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.consumer.GlobalSequenceFetchResult;
import org.apache.kafka.common.IsolationLevel;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.Uuid;
import org.apache.kafka.common.errors.RetriableException;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.internals.Topic;
import org.apache.kafka.common.message.FetchGlobalSequenceRequestData;
import org.apache.kafka.common.message.FetchGlobalSequenceResponseData;
import org.apache.kafka.common.protocol.Errors;
import org.apache.kafka.common.requests.FetchGlobalSequenceRequest;
import org.apache.kafka.common.requests.FetchGlobalSequenceResponse;
import org.apache.kafka.common.utils.ExponentialBackoff;
import org.apache.kafka.common.utils.Time;
import org.apache.kafka.common.utils.Timer;

import java.time.Duration;
import java.util.Collections;
import java.util.Objects;

/**
 * Blocking request loop for global sequence fetches.
 */
public final class GlobalSequenceFetcher<K, V> {
    private final ConsumerNetworkClient client;
    private final ConsumerMetadata metadata;
    private final GlobalSequenceResponseDecoder<K, V> decoder;
    private final Time time;
    private final ExponentialBackoff retryBackoff;
    private final int requestTimeoutMs;
    private final int maxBytes;
    private final IsolationLevel isolationLevel;

    public GlobalSequenceFetcher(
        ConsumerNetworkClient client,
        ConsumerMetadata metadata,
        GlobalSequenceResponseDecoder<K, V> decoder,
        Time time,
        long retryBackoffMs,
        long retryBackoffMaxMs,
        int requestTimeoutMs,
        int maxBytes,
        IsolationLevel isolationLevel
    ) {
        this.client = client;
        this.metadata = metadata;
        this.decoder = decoder;
        this.time = time;
        this.retryBackoff = new ExponentialBackoff(
            retryBackoffMs,
            CommonClientConfigs.RETRY_BACKOFF_EXP_BASE,
            retryBackoffMaxMs,
            CommonClientConfigs.RETRY_BACKOFF_JITTER
        );
        this.requestTimeoutMs = requestTimeoutMs;
        this.maxBytes = maxBytes;
        this.isolationLevel = isolationLevel;
    }

    public GlobalSequenceFetchResult<K, V> fetch(
        String topic,
        long globalStartOffset,
        long globalEndOffsetExclusive,
        Duration timeout
    ) {
        validateRequest(topic, globalStartOffset, globalEndOffsetExclusive, timeout);
        Timer timer = time.timer(timeout.toMillis());
        long attempts = 0L;
        boolean forceMetadataUpdate = false;
        metadata.addTransientTopics(Collections.singleton(topic));
        try {
            while (timer.notExpired()) {
                Uuid topicId = resolveTopicId(topic, timer, forceMetadataUpdate, attempts);
                FetchAttempt<K, V> attempt = fetchOnce(
                    topic,
                    topicId,
                    globalStartOffset,
                    globalEndOffsetExclusive,
                    timer
                );
                if (attempt.result != null) {
                    return attempt.result;
                }
                forceMetadataUpdate = attempt.forceMetadataUpdate;
                sleep(timer, attempts++);
            }
            throw timeoutException(topic, globalStartOffset, globalEndOffsetExclusive);
        } finally {
            metadata.clearTransientTopics();
        }
    }

    private FetchAttempt<K, V> fetchOnce(
        String topic,
        Uuid topicId,
        long globalStartOffset,
        long globalEndOffsetExclusive,
        Timer timer
    ) {
        Node node = client.leastLoadedNode();
        if (node == null) {
            metadata.requestUpdate(false);
            return FetchAttempt.retry(false);
        }

        RequestFuture<ClientResponse> future = client.send(
            node,
            request(topicId, globalStartOffset, globalEndOffsetExclusive),
            requestTimeout(timer)
        );
        if (!future.isDone() && !client.poll(future, timer)) {
            throw timeoutException(topic, globalStartOffset, globalEndOffsetExclusive);
        }
        if (future.failed()) {
            if (future.isRetriable()) {
                metadata.requestUpdate(false);
                return FetchAttempt.retry(false);
            }
            throw future.exception();
        }

        FetchGlobalSequenceResponse response = (FetchGlobalSequenceResponse) future.value().responseBody();
        FetchGlobalSequenceResponseData responseData = response.data();
        Errors error = Errors.forCode(responseData.errorCode());
        if (error == Errors.NONE) {
            return FetchAttempt.completed(decoder.decode(
                topic,
                globalStartOffset,
                globalEndOffsetExclusive,
                responseData
            ));
        }
        if (error == Errors.UNKNOWN_TOPIC_ID) {
            metadata.requestUpdate(true);
            return FetchAttempt.retry(true);
        }
        if (error.exception() instanceof RetriableException) {
            metadata.requestUpdate(false);
            return FetchAttempt.retry(false);
        }
        throw error.exception(responseData.errorMessage());
    }

    private FetchGlobalSequenceRequest.Builder request(
        Uuid topicId,
        long globalStartOffset,
        long globalEndOffsetExclusive
    ) {
        return new FetchGlobalSequenceRequest.Builder(
            new FetchGlobalSequenceRequestData()
                .setTopicId(topicId)
                .setGlobalStartOffset(globalStartOffset)
                .setGlobalEndOffsetExclusive(globalEndOffsetExclusive)
                .setMaxBytes(maxBytes)
                .setIsolationLevel(isolationLevel.id())
        );
    }

    private void validateRequest(
        String topic,
        long globalStartOffset,
        long globalEndOffsetExclusive,
        Duration timeout
    ) {
        Topic.validate(topic);
        if (globalStartOffset < 0) {
            throw new IllegalArgumentException("globalStartOffset must be non-negative");
        }
        if (globalEndOffsetExclusive <= globalStartOffset) {
            throw new IllegalArgumentException("globalEndOffsetExclusive must be greater than globalStartOffset");
        }
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative");
        }
    }

    private Uuid resolveTopicId(String topic, Timer timer, boolean forceMetadataUpdate, long attempts) {
        Uuid topicId = metadata.topicIds().get(topic);
        if (!forceMetadataUpdate && topicId != null && !Uuid.ZERO_UUID.equals(topicId)) {
            return topicId;
        }

        do {
            if (!client.awaitMetadataUpdate(timer)) {
                throw timeoutException(topic, -1L, -1L);
            }
            metadata.maybeThrowExceptionForTopic(topic);
            topicId = metadata.topicIds().get(topic);
            if (topicId != null && !Uuid.ZERO_UUID.equals(topicId)) {
                return topicId;
            }
            sleep(timer, attempts++);
        } while (timer.notExpired());
        throw timeoutException(topic, -1L, -1L);
    }

    private int requestTimeout(Timer timer) {
        return (int) Math.max(1L, Math.min(requestTimeoutMs, timer.remainingMs()));
    }

    private void sleep(Timer timer, long attempts) {
        timer.sleep(retryBackoff.backoff(attempts));
    }

    private TimeoutException timeoutException(String topic, long startOffset, long endOffsetExclusive) {
        if (startOffset < 0) {
            return new TimeoutException("Timed out resolving topic ID for " + topic);
        }
        return new TimeoutException("Timed out fetching global sequence range [" + startOffset + ", " +
            endOffsetExclusive + ") for " + topic);
    }

    private static final class FetchAttempt<K, V> {
        private final GlobalSequenceFetchResult<K, V> result;
        private final boolean forceMetadataUpdate;

        private FetchAttempt(GlobalSequenceFetchResult<K, V> result, boolean forceMetadataUpdate) {
            this.result = result;
            this.forceMetadataUpdate = forceMetadataUpdate;
        }

        private static <K, V> FetchAttempt<K, V> retry(boolean forceMetadataUpdate) {
            return new FetchAttempt<>(null, forceMetadataUpdate);
        }

        private static <K, V> FetchAttempt<K, V> completed(GlobalSequenceFetchResult<K, V> result) {
            return new FetchAttempt<>(result, false);
        }
    }
}
