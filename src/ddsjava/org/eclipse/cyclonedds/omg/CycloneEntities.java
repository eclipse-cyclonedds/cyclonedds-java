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
}
