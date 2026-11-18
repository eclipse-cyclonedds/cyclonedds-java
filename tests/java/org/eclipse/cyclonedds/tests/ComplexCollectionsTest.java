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

import BindingTest.ComplexCollections;
import BindingTest.PayloadEntry;
import BindingTest.PayloadGroup;
import BindingTest.Position;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import org.eclipse.cyclonedds.core.cdr.CdrReader;
import org.eclipse.cyclonedds.core.cdr.CdrWriter;
import org.eclipse.cyclonedds.domain.DomainParticipant;
import org.eclipse.cyclonedds.sub.Sample;
import org.eclipse.cyclonedds.type.seq.ByteSequence;
import org.eclipse.cyclonedds.type.seq.PackedByteSequence;

/** Fixed struct arrays and bounded struct sequences containing large primitive sequences. */
public final class ComplexCollectionsTest {
    private ComplexCollectionsTest() {}

    private static PayloadEntry entry(int id) {
        byte[] payload = new byte[id % 2 == 0 ? 41984 : 167936];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) ((i + id) % 251);
        return new PayloadEntry(id, new Position(id + .25, -id - .5),
            "entry " + id + " €", id % 2 == 0 ? new PackedByteSequence(payload) : new ByteSequence(payload));
    }
    private static PayloadGroup group(int id) {
        var value = new PayloadGroup();
        for (int i = 0; i < value.get_entries().length; i++) value.get_entries()[i] = entry(id + i);
        value.get_history().add(entry(id + 10));
        value.get_history().add(entry(id + 11));
        return value;
    }
    private static ComplexCollections message() {
        var value = new ComplexCollections();
        value.set_groups(new PayloadGroup[]{group(0), group(20)});
        value.get_batches().add(group(40));
        value.get_batches().add(group(60));
        value.set_after(0x12345678);
        return value;
    }
    private static void sameEntry(PayloadEntry actual, PayloadEntry expected) {
        assert actual.get_id() == expected.get_id();
        assert actual.get_position().equals(expected.get_position());
        assert actual.get_label().equals(expected.get_label());
        assert actual.get_payload().equals(expected.get_payload()); // Checks every byte.
    }
    private static void sameGroup(PayloadGroup actual, PayloadGroup expected) {
        assert actual.get_entries().length == expected.get_entries().length;
        for (int i = 0; i < actual.get_entries().length; i++)
            sameEntry(actual.get_entries()[i], expected.get_entries()[i]);
        assert actual.get_history().size() == expected.get_history().size();
        for (int i = 0; i < actual.get_history().size(); i++)
            sameEntry(actual.get_history().get(i), expected.get_history().get(i));
    }
    private static void same(ComplexCollections actual, ComplexCollections expected) {
        assert actual.get_after() == expected.get_after();
        assert actual.get_groups().length == expected.get_groups().length;
        for (int i = 0; i < actual.get_groups().length; i++)
            sameGroup(actual.get_groups()[i], expected.get_groups()[i]);
        assert actual.get_batches().size() == expected.get_batches().size();
        for (int i = 0; i < actual.get_batches().size(); i++)
            sameGroup(actual.get_batches().get(i), expected.get_batches().get(i));
    }
    private static ComplexCollections take(org.eclipse.cyclonedds.sub.DataReader<ComplexCollections> reader)
            throws InterruptedException {
        var received = new ArrayList<Sample<ComplexCollections>>();
        Checks.await(() -> { received.addAll(reader.take(1)); return received.size() == 1; });
        assert received.get(0).validData();
        return received.get(0).data();
    }
    public static void main(String[] args) throws Exception {
        assert new PayloadEntry().get_payload() instanceof PackedByteSequence;
        var expected = message();
        for (int version : new int[]{1, 2}) for (var order : new ByteOrder[]{ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN}) {
            byte[] wire;
            try (var writer = new CdrWriter(version, order)) {
                ComplexCollections.codec().write(writer, expected);
                wire = writer.toByteArray();
            }
            var first = ComplexCollections.codec().read(new CdrReader(wire));
            assert first.get_groups()[0].get_entries()[0].get_payload() instanceof PackedByteSequence;
            var second = ComplexCollections.codec().read(new CdrReader(wire));
            same(first, expected);
            same(second, expected);
            second.get_groups()[0].get_entries()[0].get_payload().set(0, (byte) 127);
            second.get_batches().get(0).get_history().get(0).get_position().set_x(-999);
            same(first, expected); // Independent graphs, including nested mutable fields.
            Checks.rejects(IllegalArgumentException.class, () ->
                ComplexCollections.codec().read(new CdrReader(Arrays.copyOf(wire, wire.length - 1))));
        }
        Checks.rejects(IndexOutOfBoundsException.class, () -> expected.set_groups(new PayloadGroup[1]));
        Checks.rejects(IndexOutOfBoundsException.class, () -> expected.get_groups()[0].set_entries(new PayloadEntry[2]));
        var snapshot = message();
        try (var participant = new DomainParticipant(93);
             var topic = participant.createTopic("ComplexCollections", ComplexCollections.typeSupport());
             var writer = topic.createWriter(); var reader = topic.createReader()) {
            Checks.await(() -> writer.matchedSubscriptions() == 1);
            writer.write(expected);
            var first = take(reader);
            same(first, snapshot);
            expected.get_groups()[0].get_entries()[0].get_payload().set(0, (byte) 127);
            expected.get_batches().get(0).get_history().get(0).get_position().set_x(-999);
            writer.write(expected);
            same(take(reader), expected);
            same(first, snapshot); // A later write/take must not overwrite received data.
        }
        System.out.println("Nested struct arrays/sequences, large byte payloads and owned DDS delivery passed");
    }
}
