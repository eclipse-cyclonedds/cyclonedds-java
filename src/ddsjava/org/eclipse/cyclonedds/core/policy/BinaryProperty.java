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

package org.eclipse.cyclonedds.core.policy;

import org.omg.dds.core.policy.QosPolicy;

/** Immutable Cyclone DDS BinaryProperty policy; create with CyclonePolicyFactory. */
public interface BinaryProperty extends QosPolicy.ForDomainParticipant, QosPolicy.ForDataReader, QosPolicy.ForDataWriter {
    /** A defensively copied binary property value. */
    record Value(byte[] value, boolean propagate) implements java.io.Serializable {
        public Value { value = java.util.Objects.requireNonNull(value).clone(); }
        @Override public byte[] value() { return value.clone(); }
        @Override public boolean equals(Object other) {
            return other instanceof Value v && propagate == v.propagate && java.util.Arrays.equals(value, v.value);
        }
        @Override public int hashCode() { return 31 * java.util.Arrays.hashCode(value) + Boolean.hashCode(propagate); }
    }
    java.util.Map<String, Value> getValue();
    BinaryProperty withValue(String name, byte[] value, boolean propagate);
    BinaryProperty without(String name);
}
