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
import org.apache.kafka.clients.consumer.GlobalSequenceFetchResult;
import org.apache.kafka.common.IsolationLevel;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.Uuid;
import org.apache.kafka.common.compress.Compression;
import org.apache.kafka.common.errors.DisconnectException;
import org.apache.kafka.common.errors.TopicAuthorizationException;
import org.apache.kafka.common.message.FetchGlobalSequenceResponseData;
import org.apache.kafka.common.protocol.Errors;
import org.apache.kafka.common.record.MemoryRecords;
import org.apache.kafka.common.record.SimpleRecord;
import org.apache.kafka.common.requests.AbstractRequest;
import org.apache.kafka.common.requests.FetchGlobalSequenceRequest;
import org.apache.kafka.common.requests.FetchGlobalSequenceResponse;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.utils.MockTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GlobalSequenceFetcherTest {
    private static final String TOPIC = "global-topic";
    private static final Uuid TOPIC_ID = Uuid.randomUuid();
    private static final Node NODE = new Node(1, "localhost", 9092);

    private final ConsumerNetworkClient client = mock(ConsumerNetworkClient.class);
    private final ConsumerMetadata metadata = mock(ConsumerMetadata.class);
    private final MockTime time = new MockTime();
    private GlobalSequenceResponseDecoder<String, String> decoder;
    private GlobalSequenceFetcher<String, String> fetcher;

    @BeforeEach
    void setUp() {
        decoder = new GlobalSequenceResponseDecoder<>(
            new StringDeserializer(),
            new StringDeserializer(),
            IsolationLevel.READ_COMMITTED,
            true
        );
        fetcher = new GlobalSequenceFetcher<>(
            client,
            metadata,
            decoder,
            time,
            10L,
            100L,
            1_000,
            4_096,
            IsolationLevel.READ_COMMITTED
        );
        when(metadata.topicIds()).thenReturn(Map.of(TOPIC, TOPIC_ID));
        when(client.leastLoadedNode()).thenReturn(NODE);
    }

    @Test
    void testBuildsRequestAndDecodesResponse() {
        RequestFuture<ClientResponse> future = successfulFuture(successResponse());
        when(client.send(eq(NODE), any(), anyInt())).thenReturn(future);

        GlobalSequenceFetchResult<String, String> result =
            fetcher.fetch(TOPIC, 2L, 4L, Duration.ofSeconds(1));

        assertEquals(List.of("c", "d"), List.of(
            result.records().get(0).value(),
            result.records().get(1).value()
        ));
        assertEquals(4L, result.nextGlobalOffset());

        ArgumentCaptor<AbstractRequest.Builder<?>> requestCaptor =
            ArgumentCaptor.forClass(AbstractRequest.Builder.class);
        verify(client).send(eq(NODE), requestCaptor.capture(), anyInt());
        FetchGlobalSequenceRequest request =
            (FetchGlobalSequenceRequest) requestCaptor.getValue().build((short) 0);
        assertEquals(TOPIC_ID, request.data().topicId());
        assertEquals(2L, request.data().globalStartOffset());
        assertEquals(4L, request.data().globalEndOffsetExclusive());
        assertEquals(4_096, request.data().maxBytes());
        assertEquals(IsolationLevel.READ_COMMITTED.id(), request.data().isolationLevel());
    }

    @Test
    void testRetriesRetriableNetworkFailure() {
        RequestFuture<ClientResponse> failed = RequestFuture.failure(DisconnectException.INSTANCE);
        RequestFuture<ClientResponse> successful = successfulFuture(successResponse());
        when(client.send(eq(NODE), any(), anyInt())).thenReturn(failed, successful);

        GlobalSequenceFetchResult<String, String> result =
            fetcher.fetch(TOPIC, 2L, 4L, Duration.ofSeconds(1));

        assertEquals(2, result.count());
        verify(metadata).requestUpdate(false);
    }

    @Test
    void testPropagatesNonRetriableResponseError() {
        FetchGlobalSequenceResponse response = new FetchGlobalSequenceResponse(
            new FetchGlobalSequenceResponseData()
                .setErrorCode(Errors.TOPIC_AUTHORIZATION_FAILED.code())
                .setErrorMessage("denied")
        );
        RequestFuture<ClientResponse> future = successfulFuture(response);
        when(client.send(eq(NODE), any(), anyInt())).thenReturn(future);

        assertThrows(TopicAuthorizationException.class,
            () -> fetcher.fetch(TOPIC, 2L, 4L, Duration.ofSeconds(1)));
    }

    @Test
    void testValidatesRangeBeforeSending() {
        assertThrows(IllegalArgumentException.class,
            () -> fetcher.fetch(TOPIC, 2L, 2L, Duration.ofSeconds(1)));
    }

    private FetchGlobalSequenceResponse successResponse() {
        MemoryRecords records = MemoryRecords.withRecords(
            40L,
            Compression.NONE,
            new SimpleRecord(bytes("a")),
            new SimpleRecord(bytes("b")),
            new SimpleRecord(bytes("c")),
            new SimpleRecord(bytes("d"))
        );
        return new FetchGlobalSequenceResponse(new FetchGlobalSequenceResponseData()
            .setNextGlobalOffset(4L)
            .setBatches(List.of(
                new FetchGlobalSequenceResponseData.GlobalSequenceFetchBatch()
                    .setGlobalBaseOffset(0L)
                    .setRecordCount(4)
                    .setFirstRecordIndex(2)
                    .setLastRecordIndexExclusive(4)
                    .setPhysicalPartition(3)
                    .setPhysicalBaseOffset(40L)
                    .setRecords(records)
            )));
    }

    private RequestFuture<ClientResponse> successfulFuture(FetchGlobalSequenceResponse response) {
        ClientResponse clientResponse = mock(ClientResponse.class);
        when(clientResponse.responseBody()).thenReturn(response);
        RequestFuture<ClientResponse> future = new RequestFuture<>();
        future.complete(clientResponse);
        return future;
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
