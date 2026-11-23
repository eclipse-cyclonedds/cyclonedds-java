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

import org.omg.dds.core.Entity;
import org.omg.dds.core.EntityQos;
import org.omg.dds.pub.DataWriter;

/** Additional operations on entities created by the Cyclone provider. */
public final class CycloneEntities {
    private CycloneEntities() {}
    /** Read the effective native QoS, including Cyclone extension policies. */
    public static EntityQos<?> getNativeQos(Entity<?, ?> entity) { return Entities.nativeQos(entity); }
    public static <T extends org.omg.dds.core.policy.QosPolicy> T getNativePolicy(Entity<?, ?> entity, Class<T> type) {
        return type.cast(getNativeQos(entity).get(type));
    }
    /** Send buffered RTPS messages from a writer using WriterBatching. */
    public static void flush(DataWriter<?> writer) { Entities.flush(writer); }
    /** Wait for unread data without occupying a waiting thread. Cancelling the future
     * unregisters the waiter. Reader closure completes it with false. Availability
     * neither consumes nor reserves samples against other readers of the cache. */
    public static java.util.concurrent.CompletableFuture<Boolean> waitDataAsync(org.omg.dds.sub.DataReader<?> reader) {
        return Entities.waitDataAsync(reader);
    }
    /** First callback failure observed on this endpoint, or null. */
    public static Throwable getLastListenerException(Entity<?, ?> entity) { return Entities.listenerException(entity); }
    /** Write a sample and mark its instance disposed in one DDS operation. */
    public static <T> void writeDispose(DataWriter<T> writer, T value) { Entities.writeDispose(writer, value, -1); }
    public static <T> void writeDispose(DataWriter<T> writer, T value, long timestamp, java.util.concurrent.TimeUnit unit) {
        if (timestamp < 0) throw new IllegalArgumentException("Negative timestamp");
        Entities.writeDispose(writer, value, Values.nanos(timestamp, java.util.Objects.requireNonNull(unit)));
    }
    /** Create a typed predicate condition. The predicate executes on DDS threads
     * and must not call DDS. The condition shares the bridge's documented query limit. */
    public static <T> CycloneQueryCondition<T> createQueryCondition(org.omg.dds.sub.DataReader<T> reader,
            java.util.function.Predicate<? super T> predicate) {
        return Entities.predicateCondition(reader, predicate, null);
    }
    public static <T> CycloneQueryCondition<T> createQueryCondition(org.omg.dds.sub.DataReader<T> reader,
            java.util.function.Predicate<? super T> predicate, org.omg.dds.sub.Subscriber.DataState state) {
        return Entities.predicateCondition(reader, predicate, java.util.Objects.requireNonNull(state));
    }
    public static <T> java.util.List<org.omg.dds.sub.Sample<T>> read(org.omg.dds.sub.DataReader<T> reader,
            CycloneQueryCondition<T> condition, int maximum) { return Entities.accessCondition(reader, condition, maximum, false); }
    public static <T> java.util.List<org.omg.dds.sub.Sample<T>> take(org.omg.dds.sub.DataReader<T> reader,
            CycloneQueryCondition<T> condition, int maximum) { return Entities.accessCondition(reader, condition, maximum, true); }
}
