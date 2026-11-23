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

package org.eclipse.cyclonedds.omg;

import java.lang.reflect.*;
import java.util.ArrayDeque;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;
import org.omg.dds.core.*;
import org.omg.dds.core.status.Status;
import org.omg.dds.domain.DomainParticipantListener;

/** Native ingress never acquires an entity monitor or runs application code.
 * Delivery is serial per endpoint; independent endpoints can dispatch concurrently. */
final class ListenerDispatch {
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "CycloneDDS-Java-listeners");
        thread.setDaemon(true); return thread;
    });
    private final ArrayDeque<Delivery> pending = new ArrayDeque<>();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final IntConsumer observer;
    private boolean running, closed;
    ListenerDispatch(IntConsumer observer) { this.observer = observer; }
    Throwable lastException() { return failure.get(); }
    void shutdown() { synchronized (pending) { closed = true; pending.clear(); } }
    record Target(Object listener, Object source, ServiceEnvironment env, int handle) {}
    private record Delivery(int kind, long[] values, Target target) {}
    private volatile Target[] targets = new Target[13];
    void configure(Target[] value) {
        Target[] replacement = value.clone(), previous = targets;
        for (int i = 0; i < replacement.length; i++) {
            Target old = previous[i], next = replacement[i];
            if (old != null && next != null && old.listener() == next.listener() && old.source() == next.source()
                    && old.env() == next.env() && old.handle() == next.handle()) replacement[i] = old;
        }
        targets = replacement;
    }
    public void onEvent(int kind, long[] values) {
        Target target = targets[kind];
        if (target == null && kind != 7 && kind != 8) return;
        synchronized (pending) {
            if (closed) return;
            // Availability is level-triggered. Keep at most one pending notification
            // for each target, even while application code is blocked in a callback.
            if ((kind == 7 || kind == 8) && pending.stream().anyMatch(d -> d.kind() == kind && d.target() == target)) return;
            pending.add(new Delivery(kind, values, target));
            if (!running) { running = true; EXECUTOR.execute(this::drain); }
        }
    }
    private void drain() {
        while (true) {
            Delivery next;
            synchronized (pending) {
                next = pending.poll();
                if (next == null) { running = false; return; }
            }
            try { deliver(next); }
            catch (Throwable error) { report(error); }
        }
    }
    private void deliver(Delivery delivery) throws ReflectiveOperationException {
        int kind = delivery.kind();
        if (kind == 7 || kind == 8) observer.accept(kind);
        Target target = delivery.target();
        if (target == null || targets[kind] != target) return;
        Status status = StatusValues.create(target.env(), kind, delivery.values());
        var event = Events.create(kind, target.source(), status);
        if (kind == 7) {
            try { org.eclipse.cyclonedds.internal.Native.takeStatus(target.handle(), 1 << 7); }
            catch (org.eclipse.cyclonedds.core.DdsException error) { if (error.code() == -9) return; throw error; }
        }
        String name = Conditions.STATUS_TYPES.get(kind).getSimpleName().replace("Status", "Event");
        Class<?> eventType = Class.forName("org.omg.dds.core.event." + name);
        for (Class<?> api : new Class<?>[]{DomainParticipantListener.class, org.omg.dds.pub.PublisherListener.class,
                org.omg.dds.sub.SubscriberListener.class, org.omg.dds.topic.TopicListener.class,
                org.omg.dds.pub.DataWriterListener.class, org.omg.dds.sub.DataReaderListener.class})
            if (api.isInstance(target.listener())) {
                api.getMethod("on" + name.replace("Event", ""), eventType).invoke(target.listener(), event); return;
            }
        throw new IllegalArgumentException("Invalid DDS listener");
    }
    private void report(Throwable error) {
        if (error instanceof InvocationTargetException invocation) error = invocation.getCause();
        failure.compareAndSet(null, error);
        Thread thread = Thread.currentThread();
        try { thread.getUncaughtExceptionHandler().uncaughtException(thread, error); }
        catch (Throwable ignored) { /* An application error handler cannot stop delivery. */ }
    }
}
