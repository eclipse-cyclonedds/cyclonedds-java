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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import org.eclipse.cyclonedds.core.cdr.CdrCodecs;
import org.eclipse.cyclonedds.core.cdr.CdrReader;
import org.eclipse.cyclonedds.core.cdr.CdrWriter;

public final class CdrTest {
    private CdrTest() {}
    private static void primitiveSequences() {
        var unbounded = CdrCodecs.primitiveSequence(CdrCodecs.INT, 0, ArrayList<Integer>::new);
        var bounded = CdrCodecs.primitiveSequence(CdrCodecs.INT, 2, ArrayList<Integer>::new);
        for (int version : new int[]{1, 2}) for (var order : new ByteOrder[]{ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN}) {
            var expected = new ArrayList<Integer>();
            for (int i = 0; i < 10000; i++) expected.add(i);
            var writer = new CdrWriter(version, order);
            unbounded.write(writer, expected);
            writer.writeInt(42);
            var reader = new CdrReader(writer.toByteArray());
            assert unbounded.read(reader).equals(expected);
            assert reader.readInt() == 42;

            writer = new CdrWriter(version, order);
            unbounded.write(writer, new ArrayList<>(List.of(1, 2, 3, 4)));
            writer.writeInt(42);
            byte[] oversized = writer.toByteArray();
            Checks.rejects(IllegalArgumentException.class, () -> bounded.read(new CdrReader(oversized)));
            for (int mode : new int[]{1, 2}) {
                var recovery = CdrCodecs.recover(bounded, mode, ArrayList<Integer>::new);
                reader = new CdrReader(oversized);
                assert recovery.read(reader).equals(mode == 1 ? List.of() : List.of(1, 2));
                assert reader.readInt() == 42; // Discarded elements must still be consumed.
            }

            writer = new CdrWriter(version, order);
            writer.writeSequenceLength(0, 0);
            writer.writeInt(42);
            reader = new CdrReader(writer.toByteArray());
            assert bounded.read(reader).isEmpty();
            assert reader.readInt() == 42;

            // A custom non-ArrayList factory must retain its type and instance.
            var linked = new LinkedList<Integer>();
            var custom = CdrCodecs.primitiveSequence(CdrCodecs.INT, 0, () -> linked);
            assert custom.read(new CdrReader(oversized)) == linked;
            assert linked.equals(List.of(1, 2, 3, 4));

            for (int length : new int[]{-1, Integer.MAX_VALUE, 2}) {
                writer = new CdrWriter(version, order);
                writer.writeInt(length);
                writer.writeByte((byte) 1); // Insufficient input, even with TRIM recovery.
                byte[] invalid = writer.toByteArray();
                var checked = CdrCodecs.<Integer, ArrayList<Integer>>primitiveSequence(CdrCodecs.INT, 1, () -> {
                    throw new AssertionError("Malformed length must fail before constructing a list");
                });
                var recovery = CdrCodecs.recover(checked, 2, ArrayList<Integer>::new);
                Checks.rejects(IllegalArgumentException.class, () -> recovery.read(new CdrReader(invalid)));
            }
        }
    }
    public static void main(String[] args) {
        primitiveSequences();
        // Independent XCDR2 byte vector: octet, long long aligned to 4, short, string.
        var writer = new CdrWriter();
        writer.writeByte((byte) 0x42);
        writer.writeLong(0x0102030405060708L);
        writer.writeShort((short) 0x1234);
        writer.writeString("Hi");
        byte[] expected = {0,7,0,0, 0x42,0,0,0, 8,7,6,5,4,3,2,1, 0x34,0x12,0,0, 3,0,0,0, 72,105,0};
        assert Arrays.equals(writer.toByteArray(), expected);
        var reader = new CdrReader(expected);
        assert reader.readByte() == 0x42;
        assert reader.readLong() == 0x0102030405060708L;
        assert reader.readShort() == 0x1234;
        assert reader.readString().equals("Hi");
        for (int id : new int[]{0, 1, 6, 7}) {
            var b = ByteBuffer.allocate(24).order((id & 1) == 1 ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
            b.put(new byte[]{0, (byte) id, 0, 0}).putInt(42);
            if (id < 2) b.putInt(0); // XCDR1 aligns long long to 8 relative to payload.
            b.putLong(0x1020304050607080L);
            var r = new CdrReader(Arrays.copyOf(b.array(), b.position()));
            assert r.readInt() == 42;
            assert r.readLong() == 0x1020304050607080L;
        }
        writer = new CdrWriter();
        writer.writeBoolean(true);
        writer.writeFloat(1.25f);
        writer.writeDouble(-2.5);
        writer.writeString("Greetings 🌍".repeat(100)); // buffer growth and UTF-8
        writer.writeOctetSequence(new byte[]{0, -1, 12});
        reader = new CdrReader(writer.toByteArray());
        assert reader.readBoolean();
        assert reader.readFloat() == 1.25f;
        assert reader.readDouble() == -2.5;
        assert reader.readString().equals("Greetings 🌍".repeat(100));
        assert Arrays.equals(reader.readOctetSequence(), new byte[]{0, -1, 12});
        Checks.rejects(IllegalArgumentException.class, () -> new CdrReader(new byte[3]));
        Checks.rejects(IllegalArgumentException.class, () -> new CdrReader(new byte[]{0, 13, 0, 0}));
        Checks.rejects(IllegalArgumentException.class, () -> new CdrReader(new byte[]{0,7,0,0, -1,-1,-1,127}).readOctetSequence());
        Checks.rejects(IllegalArgumentException.class, () -> new CdrReader(new byte[]{0,7,0,0, 2,0,0,0, 65,66}).readString());
        Checks.rejects(IllegalArgumentException.class, () -> new CdrReader(new byte[]{0,7,0,0, 2,0,0,0, (byte)0xff,0}).readString());
        Checks.rejects(IllegalArgumentException.class, () -> new CdrReader(new byte[]{0,7,0,0, 2}).readBoolean());
        Checks.rejects(IllegalArgumentException.class, () -> new CdrWriter().writeString("a\0b"));
        Checks.rejects(IllegalArgumentException.class, () -> new CdrWriter().writeString("\ud800"));
        System.out.println("CDR vectors, endianness, alignment, UTF-8 and malformed inputs passed");
    }
}
