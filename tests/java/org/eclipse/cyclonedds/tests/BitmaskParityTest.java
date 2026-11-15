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

import Parity.*;
import java.nio.ByteOrder;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.eclipse.cyclonedds.core.cdr.*;
import org.eclipse.cyclonedds.omg.*;
import org.omg.dds.core.policy.PolicyFactory;
import org.omg.dds.domain.DomainParticipantFactory;
import org.omg.dds.sub.Sample;

public final class BitmaskParityTest {
    private BitmaskParityTest() {}
    private static Masks value() {
        var m = new Masks(); m.set_id(7); m.set_one(One.SET); m.set_small(Small.FIRST.or(Small.LAST));
        m.set_byteFlags(Flags8.LOW8.or(Flags8.HIGH8)); m.set_shortFlags(Flags16.LOW16.or(Flags16.HIGH16));
        m.set_intFlags(Flags32.LOW32.or(Flags32.HIGH32)); m.set_longFlags(Flags64.LOW64.or(Flags64.HIGH64));
        m.set_fixedFlags(new Flags8[]{Flags8.LOW8, Flags8.HIGH8}); m.set_flags(new ArrayList<>(List.of(Flags16.HIGH16, Flags16.LOW16)));
        var choice = new Choice(); choice.set_mask(Flags64.HIGH64); m.set_choice(choice);
        return m;
    }
    public static void main(String[] args) throws Exception {
        assert Flags64.HIGH64.getValue() == Long.MIN_VALUE;
        assert Flags8.LOW8.or(Flags8.HIGH8).contains(Flags8.HIGH8);
        assert Flags8.LOW8.and(Flags8.HIGH8).getValue() == 0;
        Checks.rejects(IllegalArgumentException.class, () -> new Small(32));
        Checks.rejects(IllegalArgumentException.class, () -> new Flags32(-1));
        for (int version : new int[]{1, 2}) for (ByteOrder order : new ByteOrder[]{ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN}) {
            var m = value();
            try (var w = new CdrWriter(version, order)) {
                Masks.typeSupport().codec().write(w, m);
                assert Masks.typeSupport().codec().read(new CdrReader(w.toByteArray())).equals(m);
            }
        }
        var optional = new OptionalMask(); optional.set_id(1); optional.set_extra(Flags64.HIGH64);
        try (var w = new CdrWriter(2)) {
            OptionalMask.typeSupport().codec().write(w, optional);
            assert OptionalMask.typeSupport().codec().read(new CdrReader(w.toByteArray())).equals(optional);
        }
        assert OptionalMask.typeSupport().allowedDataRepresentations() == 4 : "Core cannot encode optional bitmasks in XCDR1";
        // Independent fixed-width vectors exercise sign bits and unsigned storage.
        for (int bits : new int[]{1, 5, 8, 9, 16, 17, 32, 33, 64}) {
            var codec = CdrCodecs.<Long>bitmask(bits, Long::longValue, Long::valueOf);
            for (int version : new int[]{1, 2}) for (ByteOrder order : new ByteOrder[]{ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN}) {
                try (var w = new CdrWriter(version, order)) {
                    long n = 1L << (bits - 1); codec.write(w, n);
                    byte[] wire = w.toByteArray();
                    int width = bits <= 8 ? 1 : bits <= 16 ? 2 : bits <= 32 ? 4 : 8;
                    assert wire.length == 4 + width;
                    assert codec.read(new CdrReader(wire)) == n;
                    byte[] expected = java.nio.ByteBuffer.allocate(8).order(order).putLong(n).array();
                    assert Arrays.equals(Arrays.copyOfRange(wire, 4, wire.length),
                        Arrays.copyOfRange(expected, order == ByteOrder.LITTLE_ENDIAN ? 0 : 8 - width, order == ByteOrder.LITTLE_ENDIAN ? width : 8));
                }
            }
        }
        // A failed codec must not contaminate a subsequently borrowed CDR buffer.
        CdrWriter closed;
        try (var w = CdrWriter.pooled(2)) { closed = w; w.beginType(2); w.writeString("stale".repeat(1000)); }
        Checks.rejects(IllegalStateException.class, closed::toByteArray);
        try (var w = CdrWriter.pooled(1)) { w.writeInt(42); assert new CdrReader(w.toByteArray()).readInt() == 42; }
        var env = new CycloneServiceEnvironment(); var policies = PolicyFactory.getPolicyFactory(env);
        for (int version : new int[]{1, 2}) {
            int domain = 152 + version;
            Process peer = new ProcessBuilder(args[0], Integer.toString(domain), Integer.toString(version)).inheritIO().start();
            try (var p = DomainParticipantFactory.getInstance(env).createParticipant(domain);
                 var topic = p.createTopic("ParityMasks", Masks.class); var reply = p.createTopic("ParityReply", Masks.class);
                 var pub = p.createPublisher(); var sub = p.createSubscriber();
                 var writer = pub.createDataWriter(topic, pub.getDefaultDataWriterQos().withPolicies(
                     policies.Reliability().withReliable(), policies.Representation().withValue((short)(version == 1 ? 0 : 2))));
                 var reader = sub.createDataReader(reply, sub.getDefaultDataReaderQos().withPolicies(
                     policies.Reliability().withReliable(), policies.Representation().withValue((short)(version == 1 ? 0 : 2))))) {
                Checks.await(() -> writer.getPublicationMatchedStatus().getCurrentCount() == 1);
                var pending = CycloneEntities.waitDataAsync(reader); writer.write(value());
                assert pending.get(10, TimeUnit.SECONDS);
                var received = reader.take((List<Sample<Masks>>) null);
                assert received.size() == 1;
                var expected = value(); expected.set_id(8); assert expected.equals(received.get(0).getData());
                assert peer.waitFor(10, TimeUnit.SECONDS) && peer.exitValue() == 0;
            } finally { if (peer.isAlive()) { peer.destroyForcibly(); peer.waitFor(); } }
        }
        System.out.println("Bitmask widths, arrays/sequences/unions/optionals, pooled buffers and C interoperability passed");
    }
}
