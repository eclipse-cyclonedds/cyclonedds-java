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

import org.omg.dds.core.policy.TypeConsistencyEnforcement;

/** Modern XTypes options extending the original OMG Java policy. */
public interface TypeConsistency extends TypeConsistencyEnforcement {
    boolean isIgnoreSequenceBounds();
    boolean isIgnoreStringBounds();
    boolean isIgnoreMemberNames();
    boolean isPreventTypeWidening();
    boolean isForceTypeValidation();
    TypeConsistency withIgnoreSequenceBounds(boolean value);
    TypeConsistency withIgnoreStringBounds(boolean value);
    TypeConsistency withIgnoreMemberNames(boolean value);
    TypeConsistency withPreventTypeWidening(boolean value);
    TypeConsistency withForceTypeValidation(boolean value);
    @Override TypeConsistency withKind(Kind kind);
    @Override TypeConsistency withExactTypeTypeConsistency();
    @Override TypeConsistency withExactNameTypeConsistency();
    @Override TypeConsistency withDeclaredTypeConsistency();
    @Override TypeConsistency withAssignableTypeConsistency();
}
