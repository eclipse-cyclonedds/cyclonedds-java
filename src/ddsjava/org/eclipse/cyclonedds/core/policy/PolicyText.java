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

/** Shared validation for native UTF-8 strings. */
public final class PolicyText {
    private PolicyText() {}
    public static String require(String value) {
        java.util.Objects.requireNonNull(value);
        if (value.indexOf('\0') >= 0 || !java.nio.charset.StandardCharsets.UTF_8.newEncoder().canEncode(value))
            throw new IllegalArgumentException("QoS text must be valid UTF-8 without NUL characters");
        return value;
    }
}
