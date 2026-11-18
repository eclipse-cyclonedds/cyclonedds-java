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

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import org.eclipse.cyclonedds.core.cdr.*;
import org.eclipse.cyclonedds.type.seq.ByteSequence;
import org.eclipse.cyclonedds.type.seq.PackedByteSequence;
import org.omg.type.ByteSeq;

public final class PackedByteSequenceTest {
    private PackedByteSequenceTest() {}
    private static void same(List<Byte> actual, List<Byte> expected) {
        assert actual.equals(expected) && expected.equals(actual);
        assert actual.hashCode() == expected.hashCode();
        assert Arrays.equals(actual.toArray(), expected.toArray());
        assert Arrays.equals(actual.toArray(new Byte[0]), expected.toArray(new Byte[0]));
    }
    private static void lists() throws Exception {
        var unusedCapacity = new ByteArrayOutputStream();
        try (var output = new ObjectOutputStream(unusedCapacity)) {
            output.writeObject(new PackedByteSequence(1000000));
        }
        assert unusedCapacity.size() < 4096; // Unused capacity must not be serialized.
        for (boolean nullable : new boolean[]{false, true}) {
            var actual = new PackedByteSequence();
            var expected = new ArrayList<Byte>();
            var random = new Random(20261118);
            for (int i = 0; i < 2000; i++) {
                Byte value = nullable && i % 7 == 0 ? null : (byte) random.nextInt();
                int index = random.nextInt(actual.size() + 1);
                switch (random.nextInt(8)) {
                    case 0 -> { actual.add(index, value); expected.add(index, value); }
                    case 1 -> { actual.add(value); expected.add(value); }
                    case 2 -> {
                        if (!expected.isEmpty()) {
                            index %= expected.size();
                            assert Objects.equals(actual.set(index, value), expected.set(index, value));
                        }
                    }
                    case 3 -> {
                        if (!expected.isEmpty()) {
                            index %= expected.size();
                            assert Objects.equals(actual.remove(index), expected.remove(index));
                        }
                    }
                    case 4 -> { actual.addAll(index, Arrays.asList(value, (byte) 3)); expected.addAll(index, Arrays.asList(value, (byte) 3)); }
                    case 5 -> {
                        if (!expected.isEmpty()) {
                            int end = index + random.nextInt(expected.size() - index + 1);
                            actual.subList(index, end).clear(); expected.subList(index, end).clear();
                        }
                    }
                    case 6 -> { actual.remove(value); expected.remove(value); }
                    case 7 -> { if (i % 11 == 0) { actual.clear(); expected.clear(); } }
                }
                same(actual, expected);
            }
            actual.addAll(actual); expected.addAll(new ArrayList<>(expected)); same(actual, expected);
            actual.addAll(0, actual.subList(0, actual.size()));
            expected.addAll(0, new ArrayList<>(expected)); same(actual, expected);
            var iterator = actual.listIterator(); var reference = expected.listIterator();
            while (iterator.hasNext()) {
                assert Objects.equals(iterator.next(), reference.next());
                iterator.set((byte) 9); reference.set((byte) 9);
                if (iterator.nextIndex() % 2 == 0) { iterator.remove(); reference.remove(); }
            }
            same(actual, expected);
            var stale = actual.iterator(); actual.add((byte) 7);
            Checks.rejects(ConcurrentModificationException.class, stale::next);
            var bytes = new ByteArrayOutputStream();
            try (var out = new ObjectOutputStream(bytes)) { out.writeObject(actual); }
            try (var in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
                var copy = (PackedByteSequence) in.readObject();
                same(copy, actual); copy.add((byte) 1); assert copy.size() == actual.size() + 1;
            }
        }
        Checks.rejects(IllegalArgumentException.class, () -> new PackedByteSequence(-1));
        Checks.rejects(IndexOutOfBoundsException.class, () -> new PackedByteSequence().add(-1, (byte) 0));
    }
    private static void blocks() {
        byte[] input = {1, 2, 3, 4};
        ByteSeq values = new PackedByteSequence(input);
        input[0] = 99; assert values.get(0) == 1;
        values.add(new byte[]{5, 6, 7}, 1, 2);
        values.set(1, new byte[]{8, 9, 10}, 1, 2);
        assert values.equals(List.of((byte) 1, (byte) 9, (byte) 10, (byte) 4, (byte) 6, (byte) 7));
        byte[] output = new byte[8]; output[7] = 42;
        assert values.toArray(output) == output && output[7] == 42;
        output[0] = 0; assert values.get(0) == 1;
        assert !values.add(new byte[0]);
        var packed = (PackedByteSequence) values;
        var prefix = new PackedByteSequence(new byte[]{21, 22});
        packed.addAll(0, prefix);
        assert packed.get(0) == 21 && packed.get(1) == 22;
        packed.subList(0, 2).clear();
        var source = ByteBuffer.wrap(new byte[]{11, 12, 13}); source.position(1);
        packed.addFrom(source, 1); assert source.position() == 2 && packed.get(6) == 12;
        var destination = ByteBuffer.allocate(packed.size()); packed.copyTo(destination);
        assert Arrays.equals(destination.array(), packed.toArray(new byte[0]));
        packed.set(0, (Byte) null);
        Checks.rejects(NullPointerException.class, () -> packed.toArray(new byte[0]));
        packed.set(0, (byte) 1); // Nullable fallback also supports primitive bulk operations.
        packed.add(new byte[]{2, 3}); packed.set(1, new byte[]{4}, 0, 1);
        packed.addFrom(ByteBuffer.wrap(new byte[]{8, 9}), 2);
        assert packed.get(packed.size() - 1) == 9;
        destination = ByteBuffer.allocate(packed.size()); packed.copyTo(destination);
        assert Arrays.equals(destination.array(), packed.toArray(new byte[0]));
        Checks.rejects(IndexOutOfBoundsException.class, () -> packed.add(new byte[]{1}, 0, 2));
        Checks.rejects(IndexOutOfBoundsException.class, () -> packed.set(packed.size(), new byte[]{1}, 0, 1));
        var nullable = new PackedByteSequence();
        nullable.add((Byte) null); nullable.add((byte) 5);
        try {
            var serialized = new ByteArrayOutputStream();
            try (var out = new ObjectOutputStream(serialized)) { out.writeObject(nullable); }
            try (var in = new ObjectInputStream(new ByteArrayInputStream(serialized.toByteArray()))) {
                same((PackedByteSequence) in.readObject(), Arrays.asList(null, (byte) 5));
            }
        } catch (IOException | ClassNotFoundException e) { throw new AssertionError(e); }
    }
    private static void codecs() {
        var compact = CdrCodecs.<Byte, PackedByteSequence>primitiveSequence(CdrCodecs.BYTE, 0, PackedByteSequence::new);
        var legacy = CdrCodecs.<Byte, ByteSequence>primitiveSequence(CdrCodecs.BYTE, 0, ByteSequence::new);
        var custom = CdrCodecs.<Byte, LinkedList<Byte>>primitiveSequence(CdrCodecs.BYTE, 0, LinkedList::new);
        for (int version : new int[]{1, 2}) for (var order : new ByteOrder[]{ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN}) {
            byte[] input = new byte[65536]; for (int i = 0; i < input.length; i++) input[i] = (byte) i;
            var packed = new PackedByteSequence(input);
            var old = new ByteSequence(input);
            byte[] wire;
            try (var a = new CdrWriter(version, order); var b = new CdrWriter(version, order)) {
                compact.write(a, packed); legacy.write(b, old);
                assert Arrays.equals(a.toByteArray(), b.toByteArray()); wire = a.toByteArray();
            }
            assert legacy.read(new CdrReader(wire)) instanceof ByteSequence;
            assert custom.read(new CdrReader(wire)) instanceof LinkedList;
            var first = compact.read(new CdrReader(wire));
            var second = compact.read(new CdrReader(wire));
            same(first, old); second.set(0, (byte) 42); assert first.get(0) == 0 && wire[8] == 0;
            // A custom element codec must keep its behavior even with compact storage.
            var shifted = CdrCodecs.<Byte>of((w, v) -> w.writeByte((byte) (v + 1)),
                r -> (byte) (r.readByte() - 1));
            var adapted = CdrCodecs.<Byte, PackedByteSequence>primitiveSequence(shifted, 0, PackedByteSequence::new);
            try (var writer = new CdrWriter(version, order)) {
                adapted.write(writer, first);
                same(adapted.read(new CdrReader(writer.toByteArray())), old);
            }
            var existing = new PackedByteSequence(new byte[]{9});
            var factory = CdrCodecs.<Byte, PackedByteSequence>primitiveSequence(CdrCodecs.BYTE, 0, () -> existing);
            assert factory.read(new CdrReader(wire)) == existing && existing.size() == input.length + 1;
            packed.add((Byte) null);
            Checks.rejects(NullPointerException.class, () -> compact.write(new CdrWriter(version, order), packed));
            for (int count : new int[]{0, 2, 4}) for (int mode : new int[]{0, 1, 2}) {
                var bounded = CdrCodecs.<Byte, PackedByteSequence>primitiveSequence(CdrCodecs.BYTE, 2, PackedByteSequence::new);
                var codec = mode == 0 ? bounded : CdrCodecs.recover(bounded, mode, PackedByteSequence::new);
                try (var writer = new CdrWriter(version, order)) {
                    writer.writeSequenceLength(count, 0);
                    for (int i = 0; i < count; i++) writer.writeByte((byte) (i + 1));
                    writer.writeInt(123); byte[] bytes = writer.toByteArray();
                    if (mode == 0 && count > 2) {
                        Checks.rejects(IllegalArgumentException.class, () -> codec.read(new CdrReader(bytes))); continue;
                    }
                    var reader = new CdrReader(bytes); var result = codec.read(reader);
                    int retained = count > 2 && mode == 1 ? 0 : Math.min(count, 2);
                    assert result.size() == retained;
                    for (int i = 0; i < retained; i++) assert result.get(i) == (byte) (i + 1);
                    assert reader.readInt() == 123;
                }
            }
            for (int length : new int[]{-1, Integer.MAX_VALUE, 2}) {
                var writer = new CdrWriter(version, order); writer.writeInt(length); writer.writeByte((byte) 0);
                byte[] invalid = writer.toByteArray();
                var checked = CdrCodecs.<Byte, PackedByteSequence>primitiveSequence(CdrCodecs.BYTE, 1, () -> {
                    throw new AssertionError("Malformed length reached the factory");
                });
                var recovery = CdrCodecs.recover(checked, 2, PackedByteSequence::new);
                Checks.rejects(IllegalArgumentException.class, () -> recovery.read(new CdrReader(invalid)));
            }
        }
    }
    public static void main(String[] args) throws Exception {
        lists(); blocks(); codecs();
        System.out.println("Packed ByteSeq list semantics, owned bulk copies, serialization and CDR compatibility passed");
    }
}
