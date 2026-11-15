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
import java.lang.management.ManagementFactory;
import java.lang.ref.WeakReference;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.eclipse.cyclonedds.internal.Native;
import org.eclipse.cyclonedds.omg.*;
import org.omg.dds.core.AlreadyClosedException;
import org.omg.dds.core.policy.PolicyFactory;
import org.omg.dds.core.status.DataAvailableStatus;
import org.omg.dds.domain.DomainParticipantFactory;
import org.omg.dds.sub.*;

@SuppressWarnings("try")
public final class ParityStressTest {
    private ParityStressTest() {}
    private static int nativeHandle(Object entity) throws ReflectiveOperationException {
        Object state = Proxy.getInvocationHandler(entity);
        var field = state.getClass().getDeclaredField("handle"); field.setAccessible(true);
        return field.getInt(state);
    }
    private static void capture(int entity, List<Integer> handles) {
        handles.add(entity);
        for (int child : Native.entityChildren(entity)) capture(child, handles);
    }
    private static void collected(List<WeakReference<Object>> references) throws InterruptedException {
        Checks.await(() -> {
            System.gc();
            return references.stream().allMatch(ref -> ref.get() == null);
        });
        references.clear();
    }
    private static List<WeakReference<Object>> cycle(DomainParticipantFactory factory, int iteration) throws Exception {
        var env = factory.getEnvironment(); var policies = PolicyFactory.getPolicyFactory(env);
        var participant = factory.createParticipant(155);
        var topic = participant.createTopic("ParityStress", Message.class);
        var publisher = participant.createPublisher(); var subscriber = participant.createSubscriber();
        var representation = policies.Representation().withValue((short)(iteration % 2 == 0 ? 0 : 2));
        var writer = publisher.createDataWriter(topic, publisher.getDefaultDataWriterQos().withPolicies(
            policies.Reliability().withReliable(), representation, policies.History().withKeepAll()));
        var reader = subscriber.createDataReader(topic, subscriber.getDefaultDataReaderQos().withPolicies(
            policies.Reliability().withReliable(), representation, policies.History().withKeepAll()));
        var backgroundFailure = new AtomicReference<Throwable>();
        try {
            Checks.await(() -> writer.getPublicationMatchedStatus().getCurrentCount() == 1);
            int[] sizes = {0, 1, 63, 1024, 8192, 65536};
            for (int i = 0; i < sizes.length; i++) {
                var pending = CycloneEntities.waitDataAsync(reader);
                assert !pending.isDone();
                String text = Character.toString((char)('a' + i)).repeat(sizes[i]);
                writer.write(new Message(i, text));
                assert pending.get(5, TimeUnit.SECONDS);
                var batch = reader.take((List<Sample<Message>>) null);
                assert batch.size() == 1 && batch.get(0).getData().get_id() == i && batch.get(0).getData().get_text().equals(text);
                var cancelled = CycloneEntities.waitDataAsync(reader);
                assert cancelled.cancel(false) && cancelled.isCancelled();
            }
            // Replacing a listener and then reading in its replacement must remain safe.
            var callback = new CountDownLatch(1);
            reader.setListener(AsyncParityTest.listener(source -> {}), Set.of(DataAvailableStatus.class));
            reader.setListener(AsyncParityTest.listener(source -> {
                try {
                    for (var sample : source.take((List<Sample<Message>>) null))
                        if (sample.getData() != null) {
                            assert sample.getData().get_id() == 10;
                            assert sample.getData().get_text().equals("listener");
                            callback.countDown();
                        }
                } catch (Throwable error) { backgroundFailure.set(error); callback.countDown(); }
            }), Set.of(DataAvailableStatus.class));
            writer.write(new Message(10, "listener")); AsyncParityTest.await(callback);
            reader.setListener(null);
            assert backgroundFailure.get() == null : backgroundFailure.get();
            assert CycloneEntities.getLastListenerException(reader) == null;
            var waiter = CycloneEntities.waitDataAsync(reader);
            var cancelled = CycloneEntities.waitDataAsync(reader);
            List<Integer> nativeHandles = new ArrayList<>(); capture(nativeHandle(participant), nativeHandles);
            assert nativeHandles.contains(nativeHandle(reader));
            var start = new CountDownLatch(1);
            CompletableFuture<Void> writing = CompletableFuture.runAsync(() -> {
                AsyncParityTest.await(start);
                try { for (int i = 0; i < 16; i++) writer.write(new Message(11, "race")); }
                catch (AlreadyClosedException expected) { /* Parent deletion can win. */ }
                catch (java.util.concurrent.TimeoutException error) { throw new CompletionException(error); }
            });
            CompletableFuture<Void> cancelling = CompletableFuture.runAsync(() -> { AsyncParityTest.await(start); cancelled.cancel(false); });
            CompletableFuture<Void> parent = CompletableFuture.runAsync(() -> { AsyncParityTest.await(start); participant.close(); });
            CompletableFuture<Void> child = CompletableFuture.runAsync(() -> { AsyncParityTest.await(start); reader.close(); writer.close(); });
            start.countDown();
            CompletableFuture.allOf(writing, cancelling, parent, child).get(10, TimeUnit.SECONDS);
            waiter.get(5, TimeUnit.SECONDS); // true if writing won, false if closure won.
            assert cancelled.isDone();
            for (int handle : nativeHandles) assert Native.entityParent(handle) < 0 : "Retained native entity " + handle;
            return new ArrayList<>(List.of(new WeakReference<>(participant), new WeakReference<>(reader), new WeakReference<>(writer),
                new WeakReference<>(waiter), new WeakReference<>(cancelled)));
        } finally { participant.close(); }
    }
    public static void main(String[] args) throws Exception {
        int seconds = Integer.parseInt(System.getenv().getOrDefault("CYCLONEDDS_JAVA_STRESS_SECONDS", "20"));
        if (seconds < 1 || seconds > 3600) throw new IllegalArgumentException("Stress duration must be 1..3600 seconds");
        var factory = DomainParticipantFactory.getInstance(new CycloneServiceEnvironment());
        var references = new ArrayList<WeakReference<Object>>();
        for (int i = 0; i < 4; i++) references.addAll(cycle(factory, i));
        collected(references);
        var memory = ManagementFactory.getMemoryMXBean();
        var os = ManagementFactory.getOperatingSystemMXBean();
        var unix = os instanceof com.sun.management.UnixOperatingSystemMXBean u ? u : null;
        long handlesBefore = unix == null ? 0 : unix.getOpenFileDescriptorCount();
        long before = memory.getHeapMemoryUsage().getUsed();
        long started = System.nanoTime(); int cycles = 0;
        do {
            references.addAll(cycle(factory, cycles++));
            if (cycles % 16 == 0) collected(references);
        } while (System.nanoTime() - started < TimeUnit.SECONDS.toNanos(seconds) || cycles < 16);
        collected(references);
        long growth = memory.getHeapMemoryUsage().getUsed() - before;
        long handles = unix == null ? 0 : unix.getOpenFileDescriptorCount() - handlesBefore;
        assert growth < 32L * 1024 * 1024 : "Retained Java heap grew by " + growth;
        assert handles < 32 : "File descriptors grew by " + handles;
        System.out.println("STRESS cycles=" + cycles + ", validated payloads=" + cycles * 6 + ", retained heap delta=" + growth
            + ", descriptor delta=" + handles + ", native entity deletion and weak references verified");
    }
}
