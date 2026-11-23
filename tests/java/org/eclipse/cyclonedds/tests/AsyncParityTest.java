/*
 * Copyright(c) 2026 Sven Trittler
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0, or the Eclipse Distribution License
 * v. 1.0 which is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause
*/

package org.eclipse.cyclonedds.tests;

import HelloWorld.Message;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import org.eclipse.cyclonedds.omg.*;
import org.omg.dds.core.*;
import org.omg.dds.core.policy.PolicyFactory;
import org.omg.dds.core.status.*;
import org.omg.dds.domain.DomainParticipantFactory;
import org.omg.dds.sub.*;

@SuppressWarnings({"unchecked", "try"})
public final class AsyncParityTest {
    private AsyncParityTest() {}
    static DataReaderListener<Message> listener(Consumer<DataReader<Message>> callback) {
        return (DataReaderListener<Message>) Proxy.newProxyInstance(DataReaderListener.class.getClassLoader(),
            new Class<?>[]{DataReaderListener.class}, (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) return method.getName().equals("equals") ? proxy == args[0]
                    : method.getName().equals("hashCode") ? System.identityHashCode(proxy) : "parity listener";
                callback.accept((DataReader<Message>) ((org.omg.dds.core.event.StatusChangedEvent<?>) args[0]).getSource());
                return null;
            });
    }
    static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Callback timed out"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
    public static void main(String[] args) throws Exception {
        var env = new CycloneServiceEnvironment();
        var factory = DomainParticipantFactory.getInstance(env);
        var policy = PolicyFactory.getPolicyFactory(env);
        var uncaught = new AtomicReference<Throwable>();
        var previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> uncaught.compareAndSet(null, e));
        try (var participant = factory.createParticipant(151);
             var topic = participant.createTopic("AsyncParity", Message.class);
             var publisher = participant.createPublisher(); var subscriber = participant.createSubscriber();
             var writer = publisher.createDataWriter(topic);
             var reader = subscriber.createDataReader(topic)) {
            Checks.await(() -> writer.getPublicationMatchedStatus().getCurrentCount() == 1);
            var first = CycloneEntities.waitDataAsync(reader);
            var second = CycloneEntities.waitDataAsync(reader);
            var cancelled = CycloneEntities.waitDataAsync(reader);
            assert !first.isDone() && !second.isDone();
            assert cancelled.cancel(false);
            var timed = CycloneEntities.waitDataAsync(reader).orTimeout(1, TimeUnit.MILLISECONDS);
            try { timed.get(5, TimeUnit.SECONDS); throw new AssertionError("Timeout expected"); }
            catch (ExecutionException error) { assert error.getCause() instanceof TimeoutException; }
            writer.write(new Message(1, "available"));
            assert first.get(5, TimeUnit.SECONDS) && second.get(5, TimeUnit.SECONDS);
            assert cancelled.isCancelled();
            assert CycloneEntities.waitDataAsync(reader).get(5, TimeUnit.SECONDS);
            assert reader.read((List<Sample<Message>>) null).size() == 1;
            var unread = CycloneEntities.waitDataAsync(reader);
            assert !unread.isDone() : "Already-read history is not unread data";
            writer.write(new Message(2, "new"));
            assert unread.get(5, TimeUnit.SECONDS);
            reader.take((List<Sample<Message>>) null);
            // DataOnReaders on an ancestor must not starve async reader waiters.
            SubscriberListener inherited = (SubscriberListener) Proxy.newProxyInstance(SubscriberListener.class.getClassLoader(),
                new Class<?>[]{SubscriberListener.class}, (p, m, a) -> null);
            subscriber.setListener(inherited, Set.of(DataOnReadersStatus.class));
            var ancestor = CycloneEntities.waitDataAsync(reader);
            writer.write(new Message(3, "ancestor"));
            assert ancestor.get(5, TimeUnit.SECONDS);
            subscriber.setListener(null); reader.take((List<Sample<Message>>) null);
            // One blocked reader callback cannot block a second endpoint on the same participant.
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var drained = new CountDownLatch(1);
            var calls = new AtomicInteger();
            try (var otherTopic = participant.createTopic("AsyncIndependent", Message.class);
                 var otherReader = subscriber.createDataReader(otherTopic); var otherWriter = publisher.createDataWriter(otherTopic)) {
                reader.setListener(listener(source -> {
                    int call = calls.incrementAndGet();
                    if (call == 1) { entered.countDown(); await(release); }
                    source.take((List<Sample<Message>>) null); drained.countDown();
                }), Set.of(DataAvailableStatus.class));
                try {
                    writer.write(new Message(4, "block")); await(entered);
                    for (int i = 0; i < 500; i++) writer.write(new Message(4, "burst"));
                    Checks.await(() -> otherWriter.getPublicationMatchedStatus().getCurrentCount() == 1);
                    var independent = CycloneEntities.waitDataAsync(otherReader);
                    otherWriter.write(new Message(5, "independent"));
                    assert independent.get(5, TimeUnit.SECONDS);
                } finally { release.countDown(); }
                await(drained);
                reader.setListener(null);
                // Barrier on another endpoint does not guarantee this endpoint's drain;
                // wait until the pending coalesced callback has finished before proceeding.
                Thread.sleep(100);
                assert calls.get() <= 2 : "DataAvailable burst was not coalesced: " + calls;
            }
            reader.take((List<Sample<Message>>) null);
            RuntimeException expected = new IllegalStateException("expected callback failure");
            reader.setListener(listener(source -> { source.take((List<Sample<Message>>) null); throw expected; }), Set.of(DataAvailableStatus.class));
            writer.write(new Message(6, "failure"));
            Checks.await(() -> CycloneEntities.getLastListenerException(reader) != null);
            assert CycloneEntities.getLastListenerException(reader) == expected;
            Checks.await(() -> uncaught.get() != null);
            assert uncaught.get() == expected;
            reader.setListener(null);
            reader.take((List<Sample<Message>>) null);
            // Typed predicates support cached data, selective read/take and waitsets.
            try (var query = CycloneEntities.createQueryCondition(reader, message -> message.get_id() == 9)) {
                var waitset = WaitSet.newWaitSet(env);
                try {
                    waitset.attachCondition(query);
                    writer.write(new Message(8, "excluded")); writer.write(new Message(9, "selected"));
                    waitset.waitForConditions(5, TimeUnit.SECONDS);
                    var selected = CycloneEntities.read(reader, query, 8);
                    assert selected.size() == 1 && selected.get(0).getData().get_id() == 9;
                    assert CycloneEntities.take(reader, query, 8).size() == 1;
                    assert CycloneEntities.take(reader, query, 8).isEmpty();
                    assert reader.take((List<Sample<Message>>) null).stream().anyMatch(sample -> sample.getData().get_id() == 8);
                } finally { ((AutoCloseable)waitset).close(); }
            }
            var notRead = subscriber.createDataState().with(SampleState.NOT_READ).withAnyViewState().withAnyInstanceState();
            try (var query = CycloneEntities.createQueryCondition(reader, message -> true, notRead)) {
                writer.write(new Message(12, "state filter"));
                assert CycloneEntities.read(reader, query, 8).size() == 1;
                assert CycloneEntities.read(reader, query, 8).isEmpty() : "Read condition state must not be widened";
                assert reader.take((List<Sample<Message>>) null).size() == 1;
            }
            try (var query = CycloneEntities.createQueryCondition(reader, message -> { throw new IllegalStateException("bad predicate"); })) {
                writer.write(new Message(9, "predicate error"));
                Checks.rejects(IllegalStateException.class, query::throwIfFilterFailed);
                Checks.rejects(IllegalStateException.class, () -> CycloneEntities.take(reader, query, 8));
            }
            reader.take((List<Sample<Message>>) null);
            // Combined write/dispose preserves the sample, timestamp and disposed state.
            CycloneEntities.writeDispose(writer, new Message(7, "disposed"), 123456789L, TimeUnit.NANOSECONDS);
            List<Sample<Message>> disposed = new ArrayList<>();
            Checks.await(() -> { disposed.addAll(reader.take((List<Sample<Message>>) null)); return !disposed.isEmpty(); });
            assert disposed.stream().anyMatch(sample -> sample.getData() != null && sample.getData().get_id() == 7
                && sample.getInstanceState() == InstanceState.NOT_ALIVE_DISPOSED
                && sample.getSourceTimestamp().getTime(TimeUnit.NANOSECONDS) == 123456789L);
            Checks.rejects(IllegalArgumentException.class, () -> CycloneEntities.writeDispose(writer, new Message(), -1, TimeUnit.SECONDS));
            var onClose = CycloneEntities.waitDataAsync(reader);
            reader.close();
            assert !onClose.get(5, TimeUnit.SECONDS);
            Checks.rejects(AlreadyClosedException.class, () -> CycloneEntities.waitDataAsync(reader));
        } finally { Thread.setDefaultUncaughtExceptionHandler(previous); }
        System.out.println("Async waits, cancellation, listener isolation/coalescing/errors and writeDispose passed");
    }
}
