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

import org.apache.kafka.clients.Metadata.LeaderAndEpoch;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerGroupMetadata;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.GroupProtocol;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.consumer.OffsetAndTimestamp;
import org.apache.kafka.clients.consumer.OffsetCommitCallback;
import org.apache.kafka.clients.consumer.RetriableCommitFailedException;
import org.apache.kafka.clients.consumer.internals.events.ApplicationEventHandler;
import org.apache.kafka.clients.consumer.internals.events.AssignmentChangeApplicationEvent;
import org.apache.kafka.clients.consumer.internals.events.BackgroundEvent;
import org.apache.kafka.clients.consumer.internals.events.CommitApplicationEvent;
import org.apache.kafka.clients.consumer.internals.events.CompletableApplicationEvent;
import org.apache.kafka.clients.consumer.internals.events.CompletableBackgroundEvent;
import org.apache.kafka.clients.consumer.internals.events.ConsumerRebalanceListenerCallbackNeededEvent;
import org.apache.kafka.clients.consumer.internals.events.ErrorBackgroundEvent;
import org.apache.kafka.clients.consumer.internals.events.EventProcessor;
import org.apache.kafka.clients.consumer.internals.events.FetchCommittedOffsetsApplicationEvent;
import org.apache.kafka.clients.consumer.internals.events.GroupMetadataUpdateEvent;
import org.apache.kafka.clients.consumer.internals.events.ListOffsetsApplicationEvent;
import org.apache.kafka.clients.consumer.internals.events.NewTopicsMetadataUpdateRequestEvent;
import org.apache.kafka.clients.consumer.internals.events.ResetPositionsApplicationEvent;
import org.apache.kafka.clients.consumer.internals.events.SubscriptionChangeApplicationEvent;
import org.apache.kafka.clients.consumer.internals.events.UnsubscribeApplicationEvent;
import org.apache.kafka.clients.consumer.internals.events.ValidatePositionsApplicationEvent;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.GroupAuthorizationException;
import org.apache.kafka.common.errors.InvalidGroupIdException;
import org.apache.kafka.common.errors.NetworkException;
import org.apache.kafka.common.errors.RetriableException;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.protocol.Errors;
import org.apache.kafka.common.requests.JoinGroupRequest;
import org.apache.kafka.common.requests.ListOffsetsRequest;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.utils.MockTime;
import org.apache.kafka.common.utils.Time;
import org.apache.kafka.common.utils.Timer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.SortedSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Arrays.asList;
import static java.util.Collections.singleton;
import static java.util.Collections.singletonList;
import static org.apache.kafka.clients.consumer.internals.ConsumerRebalanceListenerMethodName.ON_PARTITIONS_ASSIGNED;
import static org.apache.kafka.clients.consumer.internals.ConsumerRebalanceListenerMethodName.ON_PARTITIONS_LOST;
import static org.apache.kafka.clients.consumer.internals.ConsumerRebalanceListenerMethodName.ON_PARTITIONS_REVOKED;
import static org.apache.kafka.clients.consumer.internals.ConsumerUtils.THROW_ON_FETCH_STABLE_OFFSET_UNSUPPORTED;
import static org.apache.kafka.common.utils.Utils.mkEntry;
import static org.apache.kafka.common.utils.Utils.mkMap;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
public class AsyncKafkaConsumerTest {

    private AsyncKafkaConsumer<String, String> consumer = null;

    private final Time time = new MockTime(1);
    private final FetchCollector<String, String> fetchCollector = mock(FetchCollector.class);
    private final ApplicationEventHandler applicationEventHandler = mock(ApplicationEventHandler.class);
    private final ConsumerMetadata metadata = mock(ConsumerMetadata.class);
    private final LinkedBlockingQueue<BackgroundEvent> backgroundEventQueue = new LinkedBlockingQueue<>();

    @AfterEach
    public void resetAll() {
        backgroundEventQueue.clear();
        if (consumer != null) {
            consumer.close();
        }
        consumer = null;
        Mockito.framework().clearInlineMocks();
    }

    private AsyncKafkaConsumer<String, String> newConsumer() {
        final Properties props = requiredConsumerProperties();
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "group-id");
        final ConsumerConfig config = new ConsumerConfig(props);
        return newConsumer(config);
    }

    private AsyncKafkaConsumer<String, String> newConsumerWithoutGroupId() {
        final Properties props = requiredConsumerProperties();
        final ConsumerConfig config = new ConsumerConfig(props);
        return newConsumer(config);
    }

    @SuppressWarnings("UnusedReturnValue")
    private AsyncKafkaConsumer<String, String> newConsumerWithEmptyGroupId() {
        final Properties props = requiredConsumerPropertiesAndGroupId("");
        final ConsumerConfig config = new ConsumerConfig(props);
        return newConsumer(config);
    }

    private AsyncKafkaConsumer<String, String> newConsumer(ConsumerConfig config) {
        return new AsyncKafkaConsumer<>(
            config,
            new StringDeserializer(),
            new StringDeserializer(),
            time,
            (a, b, c, d, e, f) -> applicationEventHandler,
            (a, b, c, d, e, f, g) -> fetchCollector,
            (a, b, c, d) -> metadata,
            backgroundEventQueue
        );
    }

    @Test
    public void testSuccessfulStartupShutdown() {
        consumer = newConsumer();
        assertDoesNotThrow(() -> consumer.close());
    }

    @Test
    public void testInvalidGroupId() {
        KafkaException e = assertThrows(KafkaException.class, this::newConsumerWithEmptyGroupId);
        assertInstanceOf(InvalidGroupIdException.class, e.getCause());
    }

    @Test
    public void testFailOnClosedConsumer() {
        consumer = newConsumer();
        consumer.close();
        final IllegalStateException res = assertThrows(IllegalStateException.class, consumer::assignment);
        assertEquals("This consumer has already been closed.", res.getMessage());
    }

    @Test
    public void testCommitAsyncWithNullCallback() {
        consumer = newConsumer();
        final TopicPartition t0 = new TopicPartition("t0", 2);
        final TopicPartition t1 = new TopicPartition("t0", 3);
        HashMap<TopicPartition, OffsetAndMetadata> offsets = new HashMap<>();
        offsets.put(t0, new OffsetAndMetadata(10L));
        offsets.put(t1, new OffsetAndMetadata(20L));

        consumer.commitAsync(offsets, null);

        final ArgumentCaptor<CommitApplicationEvent> commitEventCaptor = ArgumentCaptor.forClass(CommitApplicationEvent.class);
        verify(applicationEventHandler).add(commitEventCaptor.capture());
        final CommitApplicationEvent commitEvent = commitEventCaptor.getValue();
        assertEquals(offsets, commitEvent.offsets());
        assertDoesNotThrow(() -> commitEvent.future().complete(null));
        assertDoesNotThrow(() -> consumer.commitAsync(offsets, null));
    }

    @Test
    public void testCommitAsyncUserSuppliedCallbackNoException() {
        consumer = newConsumer();

        Map<TopicPartition, OffsetAndMetadata> offsets = new HashMap<>();
        offsets.put(new TopicPartition("my-topic", 1), new OffsetAndMetadata(200L));
        completeCommitApplicationEventExceptionally();

        MockCommitCallback callback = new MockCommitCallback();
        assertDoesNotThrow(() -> consumer.commitAsync(offsets, callback));
        forceCommitCallbackInvocation();

        assertNull(callback.exception);
    }

    @ParameterizedTest
    @MethodSource("commitExceptionSupplier")
    public void testCommitAsyncUserSuppliedCallbackWithException(Exception exception) {
        consumer = newConsumer();

        Map<TopicPartition, OffsetAndMetadata> offsets = new HashMap<>();
        offsets.put(new TopicPartition("my-topic", 1), new OffsetAndMetadata(200L));
        completeCommitApplicationEventExceptionally(exception);

        MockCommitCallback callback = new MockCommitCallback();
        assertDoesNotThrow(() -> consumer.commitAsync(offsets, callback));
        forceCommitCallbackInvocation();

        assertSame(exception.getClass(), callback.exception.getClass());
    }

    private static Stream<Exception> commitExceptionSupplier() {
        return Stream.of(
                new KafkaException("Test exception"),
                new GroupAuthorizationException("Group authorization exception"));
    }

    @Test
    public void testCommitAsyncWithFencedException() {
        consumer = newConsumer();
        final HashMap<TopicPartition, OffsetAndMetadata> offsets = mockTopicPartitionOffset();
        MockCommitCallback callback = new MockCommitCallback();

        assertDoesNotThrow(() -> consumer.commitAsync(offsets, callback));

        final ArgumentCaptor<CommitApplicationEvent> commitEventCaptor = ArgumentCaptor.forClass(CommitApplicationEvent.class);
        verify(applicationEventHandler).add(commitEventCaptor.capture());
        final CommitApplicationEvent commitEvent = commitEventCaptor.getValue();
        commitEvent.future().completeExceptionally(Errors.FENCED_INSTANCE_ID.exception());

        assertThrows(Errors.FENCED_INSTANCE_ID.exception().getClass(), () -> consumer.commitAsync());
    }

    @Test
    public void testCommitted() {
        consumer = newConsumer();
        Map<TopicPartition, OffsetAndMetadata> topicPartitionOffsets = mockTopicPartitionOffset();
        completeFetchedCommittedOffsetApplicationEventSuccessfully(topicPartitionOffsets);

        assertEquals(topicPartitionOffsets, consumer.committed(topicPartitionOffsets.keySet(), Duration.ofMillis(1000)));
        verify(applicationEventHandler).addAndGet(ArgumentMatchers.isA(FetchCommittedOffsetsApplicationEvent.class), any());
    }

    @Test
    public void testCommittedLeaderEpochUpdate() {
        consumer = newConsumer();
        final TopicPartition t0 = new TopicPartition("t0", 2);
        final TopicPartition t1 = new TopicPartition("t0", 3);
        final TopicPartition t2 = new TopicPartition("t0", 4);
        HashMap<TopicPartition, OffsetAndMetadata> topicPartitionOffsets = new HashMap<>();
        topicPartitionOffsets.put(t0, new OffsetAndMetadata(10L, Optional.of(2), ""));
        topicPartitionOffsets.put(t1, null);
        topicPartitionOffsets.put(t2, new OffsetAndMetadata(20L, Optional.of(3), ""));
        completeFetchedCommittedOffsetApplicationEventSuccessfully(topicPartitionOffsets);

        assertDoesNotThrow(() -> consumer.committed(topicPartitionOffsets.keySet(), Duration.ofMillis(1000)));

        verify(metadata).updateLastSeenEpochIfNewer(t0, 2);
        verify(metadata).updateLastSeenEpochIfNewer(t2, 3);
        verify(applicationEventHandler).addAndGet(ArgumentMatchers.isA(FetchCommittedOffsetsApplicationEvent.class), any());
    }

    @Test
    public void testCommittedExceptionThrown() {
        consumer = newConsumer();
        Map<TopicPartition, OffsetAndMetadata> offsets = mockTopicPartitionOffset();
        when(applicationEventHandler.addAndGet(any(), any())).thenAnswer(invocation -> {
            CompletableApplicationEvent<?> event = invocation.getArgument(0);
            assertInstanceOf(FetchCommittedOffsetsApplicationEvent.class, event);
            throw new KafkaException("Test exception");
        });

        assertThrows(KafkaException.class, () -> consumer.committed(offsets.keySet(), Duration.ofMillis(1000)));
    }

    @Test
    public void testWakeupBeforeCallingPoll() {
        consumer = newConsumer();
        final String topicName = "foo";
        final int partition = 3;
        final TopicPartition tp = new TopicPartition(topicName, partition);
        doReturn(Fetch.empty()).when(fetchCollector).collectFetch(any(FetchBuffer.class));
        Map<TopicPartition, OffsetAndMetadata> offsets = mkMap(mkEntry(tp, new OffsetAndMetadata(1)));
        completeFetchedCommittedOffsetApplicationEventSuccessfully(offsets);
        doReturn(LeaderAndEpoch.noLeaderOrEpoch()).when(metadata).currentLeader(any());
        consumer.assign(singleton(tp));

        consumer.wakeup();

        assertThrows(WakeupException.class, () -> consumer.poll(Duration.ZERO));
        assertDoesNotThrow(() -> consumer.poll(Duration.ZERO));
    }

    @Test
    public void testWakeupAfterEmptyFetch() {
        consumer = newConsumer();
        final String topicName = "foo";
        final int partition = 3;
        final TopicPartition tp = new TopicPartition(topicName, partition);
        doAnswer(invocation -> {
            consumer.wakeup();
            return Fetch.empty();
        }).when(fetchCollector).collectFetch(any(FetchBuffer.class));
        Map<TopicPartition, OffsetAndMetadata> offsets = mkMap(mkEntry(tp, new OffsetAndMetadata(1)));
        completeFetchedCommittedOffsetApplicationEventSuccessfully(offsets);
        doReturn(LeaderAndEpoch.noLeaderOrEpoch()).when(metadata).currentLeader(any());
        consumer.assign(singleton(tp));

        assertThrows(WakeupException.class, () -> consumer.poll(Duration.ofMinutes(1)));
        assertDoesNotThrow(() -> consumer.poll(Duration.ZERO));
    }

    @Test
    public void testWakeupAfterNonEmptyFetch() {
        consumer = newConsumer();
        final String topicName = "foo";
        final int partition = 3;
        final TopicPartition tp = new TopicPartition(topicName, partition);
        final List<ConsumerRecord<String, String>> records = asList(
            new ConsumerRecord<>(topicName, partition, 2, "key1", "value1"),
            new ConsumerRecord<>(topicName, partition, 3, "key2", "value2")
        );
        doAnswer(invocation -> {
            consumer.wakeup();
            return Fetch.forPartition(tp, records, true);
        }).when(fetchCollector).collectFetch(Mockito.any(FetchBuffer.class));
        Map<TopicPartition, OffsetAndMetadata> offsets = mkMap(mkEntry(tp, new OffsetAndMetadata(1)));
        completeFetchedCommittedOffsetApplicationEventSuccessfully(offsets);
        doReturn(LeaderAndEpoch.noLeaderOrEpoch()).when(metadata).currentLeader(any());
        consumer.assign(singleton(tp));

        // since wakeup() is called when the non-empty fetch is returned the wakeup should be ignored
        assertDoesNotThrow(() -> consumer.poll(Duration.ofMinutes(1)));
        // the previously ignored wake-up should not be ignored in the next call
        assertThrows(WakeupException.class, () -> consumer.poll(Duration.ZERO));
    }

    @Test
    public void testClearWakeupTriggerAfterPoll() {
        consumer = newConsumer();
        final String topicName = "foo";
        final int partition = 3;
        final TopicPartition tp = new TopicPartition(topicName, partition);
        final List<ConsumerRecord<String, String>> records = asList(
            new ConsumerRecord<>(topicName, partition, 2, "key1", "value1"),
            new ConsumerRecord<>(topicName, partition, 3, "key2", "value2")
        );
        doReturn(Fetch.forPartition(tp, records, true))
            .when(fetchCollector).collectFetch(any(FetchBuffer.class));
        Map<TopicPartition, OffsetAndMetadata> offsets = mkMap(mkEntry(tp, new OffsetAndMetadata(1)));
        completeFetchedCommittedOffsetApplicationEventSuccessfully(offsets);
        doReturn(LeaderAndEpoch.noLeaderOrEpoch()).when(metadata).currentLeader(any());
        consumer.assign(singleton(tp));

        consumer.poll(Duration.ZERO);

        assertDoesNotThrow(() -> consumer.poll(Duration.ZERO));
    }

    @Test
    public void testEnsureCallbackExecutedByApplicationThread() {
        consumer = newConsumer();
        final String currentThread = Thread.currentThread().getName();
        MockCommitCallback callback = new MockCommitCallback();
        completeCommitApplicationEventExceptionally();

        assertDoesNotThrow(() -> consumer.commitAsync(new HashMap<>(), callback));
        assertEquals(1, consumer.callbacks());
        forceCommitCallbackInvocation();
        assertEquals(currentThread, callback.completionThread);
    }

    @Test
    public void testEnsureCommitSyncExecutedCommitAsyncCallbacks() {
        consumer = newConsumer();
        final HashMap<TopicPartition, OffsetAndMetadata> offsets = mockTopicPartitionOffset();
        MockCommitCallback callback = new MockCommitCallback();
        assertDoesNotThrow(() -> consumer.commitAsync(offsets, callback));

        final ArgumentCaptor<CommitApplicationEvent> commitEventCaptor = ArgumentCaptor.forClass(CommitApplicationEvent.class);
        verify(applicationEventHandler).add(commitEventCaptor.capture());
        final CommitApplicationEvent commitEvent = commitEventCaptor.getValue();
        commitEvent.future().completeExceptionally(new NetworkException("Test exception"));

        assertMockCommitCallbackInvoked(() -> consumer.commitSync(),
            callback,
            Errors.NETWORK_EXCEPTION);
    }

    @Test
    @SuppressWarnings("deprecation")
    public void testPollLongThrowsException() {
        consumer = newConsumer();
        Exception e = assertThrows(UnsupportedOperationException.class, () -> consumer.poll(0L));
        assertEquals("Consumer.poll(long) is not supported when \"group.protocol\" is \"consumer\". " +
            "This method is deprecated and will be removed in the next major release.", e.getMessage());
    }

    @Test
    public void testCommitSyncLeaderEpochUpdate() {
        consumer = newConsumer();
        final TopicPartition t0 = new TopicPartition("t0", 2);
        final TopicPartition t1 = new TopicPartition("t0", 3);
        HashMap<TopicPartition, OffsetAndMetadata> topicPartitionOffsets = new HashMap<>();
        topicPartitionOffsets.put(t0, new OffsetAndMetadata(10L, Optional.of(2), ""));
        topicPartitionOffsets.put(t1, new OffsetAndMetadata(20L, Optional.of(1), ""));
        completeCommitApplicationEventExceptionally();

        consumer.assign(Arrays.asList(t0, t1));

        assertDoesNotThrow(() -> consumer.commitSync(topicPartitionOffsets));

        verify(metadata).updateLastSeenEpochIfNewer(t0, 2);
        verify(metadata).updateLastSeenEpochIfNewer(t1, 1);
        verify(applicationEventHandler).add(ArgumentMatchers.isA(CommitApplicationEvent.class));
    }

    @Test
    public void testCommitAsyncLeaderEpochUpdate() {
        consumer = newConsumer();
        MockCommitCallback callback = new MockCommitCallback();
        final TopicPartition t0 = new TopicPartition("t0", 2);
        final TopicPartition t1 = new TopicPartition("t0", 3);
        HashMap<TopicPartition, OffsetAndMetadata> topicPartitionOffsets = new HashMap<>();
        topicPartitionOffsets.put(t0, new OffsetAndMetadata(10L, Optional.of(2), ""));
        topicPartitionOffsets.put(t1, new OffsetAndMetadata(20L, Optional.of(1), ""));

        consumer.assign(Arrays.asList(t0, t1));

        assertDoesNotThrow(() -> consumer.commitAsync(topicPartitionOffsets, callback));

        verify(metadata).updateLastSeenEpochIfNewer(t0, 2);
        verify(metadata).updateLastSeenEpochIfNewer(t1, 1);
        verify(applicationEventHandler).add(ArgumentMatchers.isA(CommitApplicationEvent.class));
    }

    @Test
    public void testEnsurePollExecutedCommitAsyncCallbacks() {
        consumer = newConsumer();
        MockCommitCallback callback = new MockCommitCallback();
        completeCommitApplicationEventExceptionally();
        doReturn(Fetch.empty()).when(fetchCollector).collectFetch(any(FetchBuffer.class));
        completeFetchedCommittedOffsetApplicationEventSuccessfully(mkMap());

        consumer.assign(Collections.singleton(new TopicPartition("foo", 0)));
        assertDoesNotThrow(() -> consumer.commitAsync(new HashMap<>(), callback));
        assertMockCommitCallbackInvoked(() -> consumer.poll(Duration.ZERO),
            callback,
            null);
    }

    @Test
    public void testEnsureShutdownExecutedCommitAsyncCallbacks() {
        consumer = newConsumer();
        MockCommitCallback callback = new MockCommitCallback();
        completeCommitApplicationEventExceptionally();
        assertDoesNotThrow(() -> consumer.commitAsync(new HashMap<>(), callback));
        assertMockCommitCallbackInvoked(() -> consumer.close(),
            callback,
            null);
    }

    private void assertMockCommitCallbackInvoked(final Executable task,
                                                 final MockCommitCallback callback,
                                                 final Errors errors) {
        assertDoesNotThrow(task);
        assertEquals(1, callback.invoked);
        if (errors == null)
            assertNull(callback.exception);
        else if (errors.exception() instanceof RetriableException)
            assertInstanceOf(RetriableCommitFailedException.class, callback.exception);
    }

    private static class MockCommitCallback implements OffsetCommitCallback {
        public int invoked = 0;
        public Exception exception = null;
        public String completionThread;

        @Override
        public void onComplete(Map<TopicPartition, OffsetAndMetadata> offsets, Exception exception) {
            invoked++;
            this.completionThread = Thread.currentThread().getName();
            this.exception = exception;
        }
    }

    @Test
    public void testAssign() {
        consumer = newConsumer();
        final TopicPartition tp = new TopicPartition("foo", 3);
        consumer.assign(singleton(tp));
        assertTrue(consumer.subscription().isEmpty());
        assertTrue(consumer.assignment().contains(tp));
        verify(applicationEventHandler).add(any(AssignmentChangeApplicationEvent.class));
        verify(applicationEventHandler).add(any(NewTopicsMetadataUpdateRequestEvent.class));
    }

    @Test
    public void testAssignOnNullTopicPartition() {
        consumer = newConsumer();
        assertThrows(IllegalArgumentException.class, () -> consumer.assign(null));
    }

    @Test
    public void testAssignOnEmptyTopicPartition() {
        consumer = newConsumer();
        completeUnsubscribeApplicationEventSuccessfully();

        consumer.assign(Collections.emptyList());
        assertTrue(consumer.subscription().isEmpty());
        assertTrue(consumer.assignment().isEmpty());
    }

    @Test
    public void testAssignOnNullTopicInPartition() {
        consumer = newConsumer();
        assertThrows(IllegalArgumentException.class, () -> consumer.assign(singleton(new TopicPartition(null, 0))));
    }

    @Test
    public void testAssignOnEmptyTopicInPartition() {
        consumer = newConsumer();
        assertThrows(IllegalArgumentException.class, () -> consumer.assign(singleton(new TopicPartition("  ", 0))));
    }

    @Test
    public void testBeginningOffsetsFailsIfNullPartitions() {
        consumer = newConsumer();
        assertThrows(NullPointerException.class, () -> consumer.beginningOffsets(null,
            Duration.ofMillis(1)));
    }

    @Test
    public void testBeginningOffsets() {
        consumer = newConsumer();
        Map<TopicPartition, OffsetAndTimestamp> expectedOffsetsAndTimestamp =
            mockOffsetAndTimestamp();
        Set<TopicPartition> partitions = expectedOffsetsAndTimestamp.keySet();
        doReturn(expectedOffsetsAndTimestamp).when(applicationEventHandler).addAndGet(any(), any());
        Map<TopicPartition, Long> result =
            assertDoesNotThrow(() -> consumer.beginningOffsets(partitions,
                Duration.ofMillis(1)));
        Map<TopicPartition, Long> expectedOffsets = expectedOffsetsAndTimestamp.entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().offset()));
        assertEquals(expectedOffsets, result);
        verify(applicationEventHandler).addAndGet(ArgumentMatchers.isA(ListOffsetsApplicationEvent.class),
            ArgumentMatchers.isA(Timer.class));
    }

    @Test
    public void testBeginningOffsetsThrowsKafkaExceptionForUnderlyingExecutionFailure() {
        consumer = newConsumer();
        Set<TopicPartition> partitions = mockTopicPartitionOffset().keySet();
        Throwable eventProcessingFailure = new KafkaException("Unexpected failure " +
            "processing List Offsets event");
        doThrow(eventProcessingFailure).when(applicationEventHandler).addAndGet(any(), any());
        Throwable consumerError = assertThrows(KafkaException.class,
            () -> consumer.beginningOffsets(partitions,
                Duration.ofMillis(1)));
        assertEquals(eventProcessingFailure, consumerError);
        verify(applicationEventHandler).addAndGet(ArgumentMatchers.isA(ListOffsetsApplicationEvent.class),
            ArgumentMatchers.isA(Timer.class));
    }

    @Test
    public void testBeginningOffsetsTimeoutOnEventProcessingTimeout() {
        consumer = newConsumer();
        doThrow(new TimeoutException()).when(applicationEventHandler).addAndGet(any(), any());
        assertThrows(TimeoutException.class,
            () -> consumer.beginningOffsets(
                Collections.singletonList(new TopicPartition("t1", 0)),
                Duration.ofMillis(1)));
        verify(applicationEventHandler).addAndGet(ArgumentMatchers.isA(ListOffsetsApplicationEvent.class),
            ArgumentMatchers.isA(Timer.class));
    }

    @Test
    public void testOffsetsForTimesOnNullPartitions() {
        consumer = newConsumer();
        assertThrows(NullPointerException.class, () -> consumer.offsetsForTimes(null,
            Duration.ofMillis(1)));
    }

    @Test
    public void testOffsetsForTimesFailsOnNegativeTargetTimes() {
        consumer = newConsumer();
        assertThrows(IllegalArgumentException.class,
                () -> consumer.offsetsForTimes(Collections.singletonMap(new TopicPartition(
                                "topic1", 1), ListOffsetsRequest.EARLIEST_TIMESTAMP),
                        Duration.ofMillis(1)));

        assertThrows(IllegalArgumentException.class,
                () -> consumer.offsetsForTimes(Collections.singletonMap(new TopicPartition(
                                "topic1", 1), ListOffsetsRequest.LATEST_TIMESTAMP),
                        Duration.ofMillis(1)));

        assertThrows(IllegalArgumentException.class,
                () -> consumer.offsetsForTimes(Collections.singletonMap(new TopicPartition(
                                "topic1", 1), ListOffsetsRequest.MAX_TIMESTAMP),
                        Duration.ofMillis(1)));
    }

    @Test
    public void testOffsetsForTimes() {
        consumer = newConsumer();
        Map<TopicPartition, OffsetAndTimestamp> expectedResult = mockOffsetAndTimestamp();
        Map<TopicPartition, Long> timestampToSearch = mockTimestampToSearch();

        doReturn(expectedResult).when(applicationEventHandler).addAndGet(any(), any());
        Map<TopicPartition, OffsetAndTimestamp> result =
                assertDoesNotThrow(() -> consumer.offsetsForTimes(timestampToSearch, Duration.ofMillis(1)));
        assertEquals(expectedResult, result);
        verify(applicationEventHandler).addAndGet(ArgumentMatchers.isA(ListOffsetsApplicationEvent.class),
                ArgumentMatchers.isA(Timer.class));
    }

    // This test ensures same behaviour as the current consumer when offsetsForTimes is called
    // with 0 timeout. It should return map with all requested partitions as keys, with null
    // OffsetAndTimestamp as value.
    @Test
    public void testOffsetsForTimesWithZeroTimeout() {
        consumer = newConsumer();
        TopicPartition tp = new TopicPartition("topic1", 0);
        Map<TopicPartition, OffsetAndTimestamp> expectedResult =
                Collections.singletonMap(tp, null);
        Map<TopicPartition, Long> timestampToSearch = Collections.singletonMap(tp, 5L);

        Map<TopicPartition, OffsetAndTimestamp> result =
                assertDoesNotThrow(() -> consumer.offsetsForTimes(timestampToSearch,
                        Duration.ofMillis(0)));
        assertEquals(expectedResult, result);
        verify(applicationEventHandler, never()).addAndGet(ArgumentMatchers.isA(ListOffsetsApplicationEvent.class),
            ArgumentMatchers.isA(Timer.class));
    }

    @Test
    public void testWakeupCommitted() {
        consumer = newConsumer();
        final HashMap<TopicPartition, OffsetAndMetadata> offsets = mockTopicPartitionOffset();
        doAnswer(invocation -> {
            CompletableApplicationEvent<?> event = invocation.getArgument(0);
            Timer timer = invocation.getArgument(1);
            assertInstanceOf(FetchCommittedOffsetsApplicationEvent.class, event);
            assertTrue(event.future().isCompletedExceptionally());
            return ConsumerUtils.getResult(event.future(), timer);
        })
            .when(applicationEventHandler)
            .addAndGet(any(FetchCommittedOffsetsApplicationEvent.class), any(Timer.class));

        consumer.wakeup();
        assertThrows(WakeupException.class, () -> consumer.committed(offsets.keySet()));
        assertNull(consumer.wakeupTrigger().getPendingTask());
    }


    @Test
    public void testRefreshCommittedOffsetsSuccess() {
        consumer = newConsumer();
        TopicPartition partition = new TopicPartition("t1", 1);
        Set<TopicPartition> partitions = Collections.singleton(partition);
        Map<TopicPartition, OffsetAndMetadata> committedOffsets = Collections.singletonMap(partition, new OffsetAndMetadata(10L));
        testRefreshCommittedOffsetsSuccess(partitions, committedOffsets);
    }

    @Test
    public void testRefreshCommittedOffsetsSuccessButNoCommittedOffsetsFound() {
        consumer = newConsumer();
        TopicPartition partition = new TopicPartition("t1", 1);
        Set<TopicPartition> partitions = Collections.singleton(partition);
        Map<TopicPartition, OffsetAndMetadata> committedOffsets = Collections.emptyMap();
        testRefreshCommittedOffsetsSuccess(partitions, committedOffsets);
    }

    @Test
    public void testRefreshCommittedOffsetsShouldNotResetIfFailedWithTimeout() {
        consumer = newConsumer();
        testUpdateFetchPositionsWithFetchCommittedOffsetsTimeout(true);
    }

    @Test
    public void testRefreshCommittedOffsetsNotCalledIfNoGroupId() {
        // Create consumer without group id so committed offsets are not used for updating positions
        consumer = newConsumerWithoutGroupId();
        testUpdateFetchPositionsWithFetchCommittedOffsetsTimeout(false);
    }

    @Test
    public void testSubscribeGeneratesEvent() {
        consumer = newConsumer();
        String topic = "topic1";
        consumer.subscribe(singletonList(topic));
        assertEquals(singleton(topic), consumer.subscription());
        assertTrue(consumer.assignment().isEmpty());
        verify(applicationEventHandler).add(ArgumentMatchers.isA(SubscriptionChangeApplicationEvent.class));
    }

    @Test
    public void testUnsubscribeGeneratesUnsubscribeEvent() {
        consumer = newConsumer();
        completeUnsubscribeApplicationEventSuccessfully();

        consumer.unsubscribe();

        assertTrue(consumer.subscription().isEmpty());
        assertTrue(consumer.assignment().isEmpty());
        verify(applicationEventHandler).add(ArgumentMatchers.isA(UnsubscribeApplicationEvent.class));
    }

    @Test
    public void testSubscribeToEmptyListActsAsUnsubscribe() {
        consumer = newConsumer();
        completeUnsubscribeApplicationEventSuccessfully();

        consumer.subscribe(Collections.emptyList());
        assertTrue(consumer.subscription().isEmpty());
        assertTrue(consumer.assignment().isEmpty());
        verify(applicationEventHandler).add(ArgumentMatchers.isA(UnsubscribeApplicationEvent.class));
    }

    @Test
    public void testSubscribeToNullTopicCollection() {
        consumer = newConsumer();
        assertThrows(IllegalArgumentException.class, () -> consumer.subscribe((List<String>) null));
    }

    @Test
    public void testSubscriptionOnNullTopic() {
        consumer = newConsumer();
        assertThrows(IllegalArgumentException.class, () -> consumer.subscribe(singletonList(null)));
    }

    @Test
    public void testSubscriptionOnEmptyTopic() {
        consumer = newConsumer();
        String emptyTopic = "  ";
        assertThrows(IllegalArgumentException.class, () -> consumer.subscribe(singletonList(emptyTopic)));
    }

    @Test
    public void testGroupMetadataAfterCreationWithGroupIdIsNull() {
        final Properties props = requiredConsumerProperties();
        final ConsumerConfig config = new ConsumerConfig(props);
        consumer = newConsumer(config);

        assertFalse(config.unused().contains(ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG));
        assertFalse(config.unused().contains(THROW_ON_FETCH_STABLE_OFFSET_UNSUPPORTED));
        final Throwable exception = assertThrows(InvalidGroupIdException.class, consumer::groupMetadata);
        assertEquals(
            "To use the group management or offset commit APIs, you must " +
                "provide a valid " + ConsumerConfig.GROUP_ID_CONFIG + " in the consumer configuration.",
            exception.getMessage()
        );
    }

    @Test
    public void testGroupMetadataAfterCreationWithGroupIdIsNotNull() {
        final String groupId = "consumerGroupA";
        final ConsumerConfig config = new ConsumerConfig(requiredConsumerPropertiesAndGroupId(groupId));
        consumer = newConsumer(config);

        final ConsumerGroupMetadata groupMetadata = consumer.groupMetadata();

        assertEquals(groupId, groupMetadata.groupId());
        assertEquals(Optional.empty(), groupMetadata.groupInstanceId());
        assertEquals(JoinGroupRequest.UNKNOWN_GENERATION_ID, groupMetadata.generationId());
        assertEquals(JoinGroupRequest.UNKNOWN_MEMBER_ID, groupMetadata.memberId());
    }

    @Test
    public void testGroupMetadataAfterCreationWithGroupIdIsNotNullAndGroupInstanceIdSet() {
        final String groupId = "consumerGroupA";
        final String groupInstanceId = "groupInstanceId1";
        final Properties props = requiredConsumerPropertiesAndGroupId(groupId);
        props.put(ConsumerConfig.GROUP_INSTANCE_ID_CONFIG, groupInstanceId);
        final ConsumerConfig config = new ConsumerConfig(props);
        consumer = newConsumer(config);

        final ConsumerGroupMetadata groupMetadata = consumer.groupMetadata();

        assertEquals(groupId, groupMetadata.groupId());
        assertEquals(Optional.of(groupInstanceId), groupMetadata.groupInstanceId());
        assertEquals(JoinGroupRequest.UNKNOWN_GENERATION_ID, groupMetadata.generationId());
        assertEquals(JoinGroupRequest.UNKNOWN_MEMBER_ID, groupMetadata.memberId());
    }

    @Test
    public void testGroupMetadataUpdateSingleCall() {
        final String groupId = "consumerGroupA";
        final ConsumerConfig config = new ConsumerConfig(requiredConsumerPropertiesAndGroupId(groupId));
        consumer = newConsumer(config);

        doReturn(Fetch.empty()).when(fetchCollector).collectFetch(any(FetchBuffer.class));
        completeFetchedCommittedOffsetApplicationEventSuccessfully(mkMap());

        final int generation = 1;
        final String memberId = "newMemberId";
        final ConsumerGroupMetadata expectedGroupMetadata = new ConsumerGroupMetadata(
            groupId,
            generation,
            memberId,
            Optional.empty()
        );
        final GroupMetadataUpdateEvent groupMetadataUpdateEvent = new GroupMetadataUpdateEvent(
            generation,
            memberId
        );
        backgroundEventQueue.add(groupMetadataUpdateEvent);
        consumer.assign(singletonList(new TopicPartition("topic", 0)));
        consumer.poll(Duration.ZERO);

        final ConsumerGroupMetadata actualGroupMetadata = consumer.groupMetadata();

        assertEquals(expectedGroupMetadata, actualGroupMetadata);

        final ConsumerGroupMetadata secondActualGroupMetadataWithoutUpdate = consumer.groupMetadata();

        assertEquals(expectedGroupMetadata, secondActualGroupMetadataWithoutUpdate);
    }

    /**
     * Tests that the consumer correctly invokes the callbacks for {@link ConsumerRebalanceListener} that was
     * specified. We don't go through the full effort to emulate heartbeats and correct group management here. We're
     * simply exercising the background {@link EventProcessor} does the correct thing when
     * {@link AsyncKafkaConsumer#poll(Duration)} is called.
     *
     * Note that we test {@link ConsumerRebalanceListener} that throws errors in its different callbacks. Failed
     * callback execution does <em>not</em> immediately errors. Instead, those errors are forwarded to the
     * application event thread for the {@link MembershipManagerImpl} to handle.
     */
    @ParameterizedTest
    @MethodSource("listenerCallbacksInvokeSource")
    public void testListenerCallbacksInvoke(List<ConsumerRebalanceListenerMethodName> methodNames,
                                            Optional<RuntimeException> revokedError,
                                            Optional<RuntimeException> assignedError,
                                            Optional<RuntimeException> lostError,
                                            int expectedRevokedCount,
                                            int expectedAssignedCount,
                                            int expectedLostCount) {
        consumer = newConsumer();
        CounterConsumerRebalanceListener consumerRebalanceListener = new CounterConsumerRebalanceListener(
                revokedError,
                assignedError,
                lostError
        );
        doReturn(Fetch.empty()).when(fetchCollector).collectFetch(any(FetchBuffer.class));
        consumer.subscribe(Collections.singletonList("topic"), consumerRebalanceListener);
        SortedSet<TopicPartition> partitions = Collections.emptySortedSet();

        for (ConsumerRebalanceListenerMethodName methodName : methodNames) {
            CompletableBackgroundEvent<Void> e = new ConsumerRebalanceListenerCallbackNeededEvent(methodName, partitions);
            backgroundEventQueue.add(e);

            // This will trigger the background event queue to process our background event message.
            consumer.poll(Duration.ZERO);
        }

        assertEquals(expectedRevokedCount, consumerRebalanceListener.revokedCount());
        assertEquals(expectedAssignedCount, consumerRebalanceListener.assignedCount());
        assertEquals(expectedLostCount, consumerRebalanceListener.lostCount());
    }

    private static Stream<Arguments> listenerCallbacksInvokeSource() {
        Optional<RuntimeException> empty = Optional.empty();
        Optional<RuntimeException> error = Optional.of(new RuntimeException("Intentional error"));

        return Stream.of(
            // Tests if we don't have an event, the listener doesn't get called.
            Arguments.of(Collections.emptyList(), empty, empty, empty, 0, 0, 0),

            // Tests if we get an event for a revocation, that we invoke our listener.
            Arguments.of(Collections.singletonList(ON_PARTITIONS_REVOKED), empty, empty, empty, 1, 0, 0),

            // Tests if we get an event for an assignment, that we invoke our listener.
            Arguments.of(Collections.singletonList(ON_PARTITIONS_ASSIGNED), empty, empty, empty, 0, 1, 0),

            // Tests that we invoke our listener even if it encounters an exception.
            Arguments.of(Collections.singletonList(ON_PARTITIONS_LOST), empty, empty, empty, 0, 0, 1),

            // Tests that we invoke our listener even if it encounters an exception.
            Arguments.of(Collections.singletonList(ON_PARTITIONS_REVOKED), error, empty, empty, 1, 0, 0),

            // Tests that we invoke our listener even if it encounters an exception.
            Arguments.of(Collections.singletonList(ON_PARTITIONS_ASSIGNED), empty, error, empty, 0, 1, 0),

            // Tests that we invoke our listener even if it encounters an exception.
            Arguments.of(Collections.singletonList(ON_PARTITIONS_LOST), empty, empty, error, 0, 0, 1),

            // Tests if we get separate events for revocation and then assignment--AND our revocation throws an error--
            // we still invoke the listeners correctly without throwing the error at the user.
            Arguments.of(Arrays.asList(ON_PARTITIONS_REVOKED, ON_PARTITIONS_ASSIGNED), error, empty, empty, 1, 1, 0)
        );
    }

    @Test
    public void testBackgroundError() {
        final String groupId = "consumerGroupA";
        final ConsumerConfig config = new ConsumerConfig(requiredConsumerPropertiesAndGroupId(groupId));
        consumer = newConsumer(config);

        final KafkaException expectedException = new KafkaException("Nobody expects the Spanish Inquisition");
        final ErrorBackgroundEvent errorBackgroundEvent = new ErrorBackgroundEvent(expectedException);
        backgroundEventQueue.add(errorBackgroundEvent);
        consumer.assign(singletonList(new TopicPartition("topic", 0)));

        final KafkaException exception = assertThrows(KafkaException.class, () -> consumer.poll(Duration.ZERO));

        assertEquals(expectedException.getMessage(), exception.getMessage());
    }

    @Test
    public void testMultipleBackgroundErrors() {
        final String groupId = "consumerGroupA";
        final ConsumerConfig config = new ConsumerConfig(requiredConsumerPropertiesAndGroupId(groupId));
        consumer = newConsumer(config);

        final KafkaException expectedException1 = new KafkaException("Nobody expects the Spanish Inquisition");
        final ErrorBackgroundEvent errorBackgroundEvent1 = new ErrorBackgroundEvent(expectedException1);
        backgroundEventQueue.add(errorBackgroundEvent1);
        final KafkaException expectedException2 = new KafkaException("Spam, Spam, Spam");
        final ErrorBackgroundEvent errorBackgroundEvent2 = new ErrorBackgroundEvent(expectedException2);
        backgroundEventQueue.add(errorBackgroundEvent2);
        consumer.assign(singletonList(new TopicPartition("topic", 0)));

        final KafkaException exception = assertThrows(KafkaException.class, () -> consumer.poll(Duration.ZERO));

        assertEquals(expectedException1.getMessage(), exception.getMessage());
        assertTrue(backgroundEventQueue.isEmpty());
    }

    @Test
    public void testGroupRemoteAssignorUnusedIfGroupIdUndefined() {
        final Properties props = requiredConsumerProperties();
        props.put(ConsumerConfig.GROUP_REMOTE_ASSIGNOR_CONFIG, "someAssignor");
        final ConsumerConfig config = new ConsumerConfig(props);
        consumer = newConsumer(config);

        assertTrue(config.unused().contains(ConsumerConfig.GROUP_REMOTE_ASSIGNOR_CONFIG));
    }

    @Test
    public void testGroupRemoteAssignorUnusedInGenericProtocol() {
        final Properties props = requiredConsumerProperties();
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "consumerGroupA");
        props.put(ConsumerConfig.GROUP_PROTOCOL_CONFIG, GroupProtocol.GENERIC.name().toLowerCase(Locale.ROOT));
        props.put(ConsumerConfig.GROUP_REMOTE_ASSIGNOR_CONFIG, "someAssignor");
        final ConsumerConfig config = new ConsumerConfig(props);
        consumer = newConsumer(config);

        assertTrue(config.unused().contains(ConsumerConfig.GROUP_REMOTE_ASSIGNOR_CONFIG));
    }

    @Test
    public void testGroupRemoteAssignorUsedInConsumerProtocol() {
        final Properties props = requiredConsumerProperties();
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "consumerGroupA");
        props.put(ConsumerConfig.GROUP_PROTOCOL_CONFIG, GroupProtocol.CONSUMER.name().toLowerCase(Locale.ROOT));
        props.put(ConsumerConfig.GROUP_REMOTE_ASSIGNOR_CONFIG, "someAssignor");
        final ConsumerConfig config = new ConsumerConfig(props);
        consumer = newConsumer(config);

        assertFalse(config.unused().contains(ConsumerConfig.GROUP_REMOTE_ASSIGNOR_CONFIG));
    }

    @Test
    public void testGroupIdNull() {
        final Properties props = requiredConsumerProperties();
        props.put(ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG, 10000);
        props.put(THROW_ON_FETCH_STABLE_OFFSET_UNSUPPORTED, true);
        final ConsumerConfig config = new ConsumerConfig(props);
        consumer = newConsumer(config);

        assertFalse(config.unused().contains(ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG));
        assertFalse(config.unused().contains(THROW_ON_FETCH_STABLE_OFFSET_UNSUPPORTED));
    }

    @Test
    public void testGroupIdNotNullAndValid() {
        final Properties props = requiredConsumerPropertiesAndGroupId("consumerGroupA");
        props.put(ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG, 10000);
        props.put(THROW_ON_FETCH_STABLE_OFFSET_UNSUPPORTED, true);
        final ConsumerConfig config = new ConsumerConfig(props);
        consumer = newConsumer(config);

        assertTrue(config.unused().contains(ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG));
        assertTrue(config.unused().contains(THROW_ON_FETCH_STABLE_OFFSET_UNSUPPORTED));
    }

    @Test
    public void testGroupIdEmpty() {
        testInvalidGroupId("");
    }

    @Test
    public void testGroupIdOnlyWhitespaces() {
        testInvalidGroupId("       ");
    }

    private void testInvalidGroupId(final String groupId) {
        final Properties props = requiredConsumerPropertiesAndGroupId(groupId);
        final ConsumerConfig config = new ConsumerConfig(props);

        final Exception exception = assertThrows(
            KafkaException.class,
            () -> consumer = newConsumer(config)
        );

        assertEquals("Failed to construct kafka consumer", exception.getMessage());
    }

    private Properties requiredConsumerPropertiesAndGroupId(final String groupId) {
        final Properties props = requiredConsumerProperties();
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        return props;
    }

    private Properties requiredConsumerProperties() {
        final Properties props = new Properties();
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9091");
        return props;
    }

    private void testUpdateFetchPositionsWithFetchCommittedOffsetsTimeout(boolean committedOffsetsEnabled) {
        completeFetchedCommittedOffsetApplicationEventExceptionally(new TimeoutException());
        doReturn(Fetch.empty()).when(fetchCollector).collectFetch(any(FetchBuffer.class));

        consumer.assign(singleton(new TopicPartition("t1", 1)));

        consumer.poll(Duration.ZERO);

        verify(applicationEventHandler, atLeast(1))
            .addAndGet(ArgumentMatchers.isA(ValidatePositionsApplicationEvent.class), ArgumentMatchers.isA(Timer.class));

        if (committedOffsetsEnabled) {
            // Verify there was an FetchCommittedOffsets event and no ResetPositions event
            verify(applicationEventHandler, atLeast(1))
                .addAndGet(ArgumentMatchers.isA(FetchCommittedOffsetsApplicationEvent.class), ArgumentMatchers.isA(Timer.class));
            verify(applicationEventHandler, never())
                .addAndGet(ArgumentMatchers.isA(ResetPositionsApplicationEvent.class), ArgumentMatchers.isA(Timer.class));
        } else {
            // Verify there was not any FetchCommittedOffsets event but there should be a ResetPositions
            verify(applicationEventHandler, never())
                .addAndGet(ArgumentMatchers.isA(FetchCommittedOffsetsApplicationEvent.class), ArgumentMatchers.isA(Timer.class));
            verify(applicationEventHandler, atLeast(1))
                .addAndGet(ArgumentMatchers.isA(ResetPositionsApplicationEvent.class), ArgumentMatchers.isA(Timer.class));
        }
    }

    private void testRefreshCommittedOffsetsSuccess(Set<TopicPartition> partitions,
                                                    Map<TopicPartition, OffsetAndMetadata> committedOffsets) {
        completeFetchedCommittedOffsetApplicationEventSuccessfully(committedOffsets);
        doReturn(Fetch.empty()).when(fetchCollector).collectFetch(any(FetchBuffer.class));
        doReturn(LeaderAndEpoch.noLeaderOrEpoch()).when(metadata).currentLeader(any());

        consumer.assign(partitions);

        consumer.poll(Duration.ZERO);

        verify(applicationEventHandler, atLeast(1))
            .addAndGet(ArgumentMatchers.isA(ValidatePositionsApplicationEvent.class), ArgumentMatchers.isA(Timer.class));
        verify(applicationEventHandler, atLeast(1))
            .addAndGet(ArgumentMatchers.isA(FetchCommittedOffsetsApplicationEvent.class), ArgumentMatchers.isA(Timer.class));
        verify(applicationEventHandler, atLeast(1))
            .addAndGet(ArgumentMatchers.isA(ResetPositionsApplicationEvent.class), ArgumentMatchers.isA(Timer.class));
    }

    @Test
    public void testLongPollWaitIsLimited() {
        consumer = newConsumer();
        String topicName = "topic1";
        consumer.subscribe(singletonList(topicName));

        assertEquals(singleton(topicName), consumer.subscription());
        assertTrue(consumer.assignment().isEmpty());

        final int partition = 3;
        final TopicPartition tp = new TopicPartition(topicName, partition);
        final List<ConsumerRecord<String, String>> records = asList(
            new ConsumerRecord<>(topicName, partition, 2, "key1", "value1"),
            new ConsumerRecord<>(topicName, partition, 3, "key2", "value2")
        );

        // On the first iteration, return no data; on the second, return two records
        doAnswer(invocation -> {
            // Mock the subscription being assigned as the first fetch is collected
            consumer.subscriptions().assignFromSubscribed(Collections.singleton(tp));
            return Fetch.empty();
        }).doAnswer(invocation -> {
            return Fetch.forPartition(tp, records, true);
        }).when(fetchCollector).collectFetch(any(FetchBuffer.class));

        // And then poll for up to 10000ms, which should return 2 records without timing out
        ConsumerRecords<?, ?> returnedRecords = consumer.poll(Duration.ofMillis(10000));
        assertEquals(2, returnedRecords.count());

        assertEquals(singleton(topicName), consumer.subscription());
        assertEquals(singleton(tp), consumer.assignment());
    }

    /**
     * Tests {@link AsyncKafkaConsumer#processBackgroundEvents(EventProcessor, Future, Timer) processBackgroundEvents}
     * handles the case where the {@link Future} takes a bit of time to complete, but does within the timeout.
     */
    @Test
    public void testProcessBackgroundEventsWithInitialDelay() throws Exception {
        consumer = newConsumer();
        Time time = new MockTime();
        Timer timer = time.timer(1000);
        CompletableFuture<?> future = mock(CompletableFuture.class);
        CountDownLatch latch = new CountDownLatch(3);

        // Mock our call to Future.get(timeout) so that it mimics a delay of 200 milliseconds. Keep in mind that
        // the incremental timeout inside processBackgroundEvents is 100 seconds for each pass. Our first two passes
        // will exceed the incremental timeout, but the third will return.
        doAnswer(invocation -> {
            latch.countDown();

            if (latch.getCount() > 0) {
                long timeout = invocation.getArgument(0, Long.class);
                timer.sleep(timeout);
                throw new java.util.concurrent.TimeoutException("Intentional timeout");
            }

            future.complete(null);
            return null;
        }).when(future).get(any(Long.class), any(TimeUnit.class));

        try (EventProcessor<?> processor = mock(EventProcessor.class)) {
            consumer.processBackgroundEvents(processor, future, timer);

            // 800 is the 1000 ms timeout (above) minus the 200 ms delay for the two incremental timeouts/retries.
            assertEquals(800, timer.remainingMs());
        }
    }

    /**
     * Tests {@link AsyncKafkaConsumer#processBackgroundEvents(EventProcessor, Future, Timer) processBackgroundEvents}
     * handles the case where the {@link Future} is already complete when invoked, so it doesn't have to wait.
     */
    @Test
    public void testProcessBackgroundEventsWithoutDelay() {
        consumer = newConsumer();
        Time time = new MockTime();
        Timer timer = time.timer(1000);

        // Create a future that is already completed.
        CompletableFuture<?> future = CompletableFuture.completedFuture(null);

        try (EventProcessor<?> processor = mock(EventProcessor.class)) {
            consumer.processBackgroundEvents(processor, future, timer);

            // Because we didn't need to perform a timed get, we should still have every last millisecond
            // of our initial timeout.
            assertEquals(1000, timer.remainingMs());
        }
    }

    /**
     * Tests {@link AsyncKafkaConsumer#processBackgroundEvents(EventProcessor, Future, Timer) processBackgroundEvents}
     * handles the case where the {@link Future} does not complete within the timeout.
     */
    @Test
    public void testProcessBackgroundEventsTimesOut() throws Exception {
        consumer = newConsumer();
        Time time = new MockTime();
        Timer timer = time.timer(1000);
        CompletableFuture<?> future = mock(CompletableFuture.class);

        doAnswer(invocation -> {
            long timeout = invocation.getArgument(0, Long.class);
            timer.sleep(timeout);
            throw new java.util.concurrent.TimeoutException("Intentional timeout");
        }).when(future).get(any(Long.class), any(TimeUnit.class));

        try (EventProcessor<?> processor = mock(EventProcessor.class)) {
            assertThrows(TimeoutException.class, () -> consumer.processBackgroundEvents(processor, future, timer));

            // Because we forced our mocked future to continuously time out, we should have no time remaining.
            assertEquals(0, timer.remainingMs());
        }
    }

    private HashMap<TopicPartition, OffsetAndMetadata> mockTopicPartitionOffset() {
        final TopicPartition t0 = new TopicPartition("t0", 2);
        final TopicPartition t1 = new TopicPartition("t0", 3);
        HashMap<TopicPartition, OffsetAndMetadata> topicPartitionOffsets = new HashMap<>();
        topicPartitionOffsets.put(t0, new OffsetAndMetadata(10L));
        topicPartitionOffsets.put(t1, new OffsetAndMetadata(20L));
        return topicPartitionOffsets;
    }

    private HashMap<TopicPartition, OffsetAndTimestamp> mockOffsetAndTimestamp() {
        final TopicPartition t0 = new TopicPartition("t0", 2);
        final TopicPartition t1 = new TopicPartition("t0", 3);
        HashMap<TopicPartition, OffsetAndTimestamp> offsetAndTimestamp = new HashMap<>();
        offsetAndTimestamp.put(t0, new OffsetAndTimestamp(5L, 1L));
        offsetAndTimestamp.put(t1, new OffsetAndTimestamp(6L, 3L));
        return offsetAndTimestamp;
    }

    private HashMap<TopicPartition, Long> mockTimestampToSearch() {
        final TopicPartition t0 = new TopicPartition("t0", 2);
        final TopicPartition t1 = new TopicPartition("t0", 3);
        HashMap<TopicPartition, Long> timestampToSearch = new HashMap<>();
        timestampToSearch.put(t0, 1L);
        timestampToSearch.put(t1, 2L);
        return timestampToSearch;
    }

    private void completeCommitApplicationEventExceptionally(Exception ex) {
        doAnswer(invocation -> {
            CommitApplicationEvent event = invocation.getArgument(0);
            event.future().completeExceptionally(ex);
            return null;
        }).when(applicationEventHandler).add(ArgumentMatchers.isA(CommitApplicationEvent.class));
    }

    private void completeCommitApplicationEventExceptionally() {
        doAnswer(invocation -> {
            CommitApplicationEvent event = invocation.getArgument(0);
            event.future().complete(null);
            return null;
        }).when(applicationEventHandler).add(ArgumentMatchers.isA(CommitApplicationEvent.class));
    }

    private void completeFetchedCommittedOffsetApplicationEventSuccessfully(final Map<TopicPartition, OffsetAndMetadata> committedOffsets) {
        doReturn(committedOffsets)
            .when(applicationEventHandler)
            .addAndGet(any(FetchCommittedOffsetsApplicationEvent.class), any(Timer.class));
    }

    private void completeFetchedCommittedOffsetApplicationEventExceptionally(Exception ex) {
        doThrow(ex)
            .when(applicationEventHandler)
            .addAndGet(any(FetchCommittedOffsetsApplicationEvent.class), any(Timer.class));
    }

    private void completeUnsubscribeApplicationEventSuccessfully() {
        doAnswer(invocation -> {
            UnsubscribeApplicationEvent event = invocation.getArgument(0);
            event.future().complete(null);
            return null;
        }).when(applicationEventHandler).add(ArgumentMatchers.isA(UnsubscribeApplicationEvent.class));
    }

    private void forceCommitCallbackInvocation() {
        // Invokes callback
        consumer.commitAsync();
    }
}
