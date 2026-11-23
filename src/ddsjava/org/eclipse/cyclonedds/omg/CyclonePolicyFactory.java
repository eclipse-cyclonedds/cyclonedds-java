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

import org.eclipse.cyclonedds.core.policy.*;
import org.omg.dds.core.ServiceEnvironment;
import org.omg.dds.core.policy.DataRepresentation;
import org.omg.dds.core.policy.TypeConsistencyEnforcement;

/** Cyclone-specific policies usable with the standard entity QoS.withPolicy API. */
public final class CyclonePolicyFactory {
    private final ServiceEnvironment environment;
    private CyclonePolicyFactory(ServiceEnvironment environment) {
        if (!(environment instanceof CycloneServiceEnvironment)) throw new IllegalArgumentException("Foreign environment");
        this.environment = environment;
    }
    public static CyclonePolicyFactory getPolicyFactory(ServiceEnvironment environment) {
        return new CyclonePolicyFactory(environment);
    }
    public Property Property() { return ExtraPolicies.create(environment, Property.class); }
    public BinaryProperty BinaryProperty() { return ExtraPolicies.create(environment, BinaryProperty.class); }
    public EntityName EntityName() { return ExtraPolicies.create(environment, EntityName.class); }
    public IgnoreLocal IgnoreLocal() { return ExtraPolicies.create(environment, IgnoreLocal.class); }
    public WriterBatching WriterBatching() { return ExtraPolicies.create(environment, WriterBatching.class); }
    public PsmxInstances PsmxInstances() { return ExtraPolicies.create(environment, PsmxInstances.class); }
    public TypeConsistency TypeConsistency() {
        return (TypeConsistency) ExtraPolicies.create(environment, TypeConsistencyEnforcement.class);
    }
    /** Read Cyclone-specific policies from built-in or matched-endpoint discovery data. */
    public static <T extends org.omg.dds.core.policy.QosPolicy> T getDiscoveredPolicy(Object data, Class<T> type) {
        return Discovery.policy(data, type);
    }
    /** DDS writer selects one representation; the reader must accept it. */
    public static boolean isRepresentationCompatible(DataRepresentation offered, DataRepresentation requested) {
        java.util.Objects.requireNonNull(offered); java.util.Objects.requireNonNull(requested);
        if (offered.getValue().size() != 1) throw new IllegalArgumentException("Writer must offer exactly one representation");
        return requested.getValue().contains(offered.getValue().get(0));
    }
}
