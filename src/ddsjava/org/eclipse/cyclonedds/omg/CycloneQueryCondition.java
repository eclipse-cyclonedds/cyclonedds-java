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

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import org.eclipse.cyclonedds.core.cdr.CdrReader;
import org.eclipse.cyclonedds.internal.Native;
import org.omg.dds.core.ServiceEnvironment;
import org.omg.dds.sub.DataReader;

/** A typed Java predicate evaluated on native DDS threads. Predicates must be
 * thread-safe, short and must never call DDS operations. Close the condition.
 * Use CycloneEntities.read/take to access its matching samples. */
public final class CycloneQueryCondition<T> extends Conditions.Read<T> {
    private final Filter<T> filter;
    private final int slot;
    CycloneQueryCondition(ServiceEnvironment env, DataReader<T> reader, int handle, DataStates state,
            CycloneTypeSupport<T> type, Predicate<? super T> predicate,
            java.util.function.Consumer<Conditions.Read<?>> onClose) {
        this(env, reader, state, new Filter<>(type, predicate), handle, onClose);
    }
    private CycloneQueryCondition(ServiceEnvironment env, DataReader<T> reader, DataStates state,
            Filter<T> filter, int handle, java.util.function.Consumer<Conditions.Read<?>> onClose) {
        this(env, reader, state, filter, Native.queryCreate(handle, state.mask(), filter), onClose);
    }
    private CycloneQueryCondition(ServiceEnvironment env, DataReader<T> reader, DataStates state,
            Filter<T> filter, long[] query, java.util.function.Consumer<Conditions.Read<?>> onClose) {
        super(env, reader, (int)query[0], state, onClose);
        this.filter = filter; slot = (int)query[1];
    }
    /** Rethrows a predicate failure observed during native filtering. */
    public void throwIfFilterFailed() {
        Throwable error = filter.failure.get();
        if (error != null) throw new IllegalStateException("Query predicate failed; predicates must not call DDS operations", error);
    }
    @Override public boolean getTriggerValue() { boolean result = super.getTriggerValue(); throwIfFilterFailed(); return result; }
    @Override void delete() {
        if (resource().handle == 0) return;
        try { Native.queryDelete(resource().handle, slot); }
        catch (org.eclipse.cyclonedds.core.DdsException error) { throw Errors.unchecked(getEnvironment(), error); }
        resource().invalidate(this);
    }
    private static final class Filter<T> {
        private final CycloneTypeSupport<T> type;
        private final Predicate<? super T> predicate;
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        Filter(CycloneTypeSupport<T> type, Predicate<? super T> predicate) {
            this.type = type; this.predicate = Objects.requireNonNull(predicate);
        }
        public boolean matches(byte[] cdr) {
            try { return predicate.test(type.codec().read(new CdrReader(cdr))); }
            catch (Throwable error) { failure.compareAndSet(null, error); return false; }
        }
    }
}
