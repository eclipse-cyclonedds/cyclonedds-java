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

import BindingTest.PayloadEntry;
import BindingTest.Position;
import java.util.Arrays;
import org.eclipse.cyclonedds.core.DdsException;
import org.eclipse.cyclonedds.core.cdr.CdrReader;
import org.eclipse.cyclonedds.core.cdr.CdrWriter;
import org.eclipse.cyclonedds.internal.Native;
import org.eclipse.cyclonedds.internal.WireSample;
import org.eclipse.cyclonedds.type.seq.PackedByteSequence;

/** Valid lengths, buffer reuse and independent Java/native ownership at the CDR boundary. */
public final class CdrTransferTest {
    private CdrTransferTest() {}
    private static byte[] take(int reader) throws InterruptedException {
        byte[][] result = new byte[1][];
        Checks.await(() -> {
            var samples = Native.take(reader, 1);
            if (samples.length != 0) { assert samples[0].validData(); result[0] = samples[0].cdr(); }
            return result[0] != null;
        });
        return result[0];
    }
    private static byte[] encode(PayloadEntry value) {
        try (var writer = new CdrWriter()) {
            PayloadEntry.codec().write(writer, value); return writer.toByteArray();
        }
    }
    public static void main(String[] args) throws Exception {
        var info = new WireSample(new byte[1000], true, 11, 12, 16, 1, 4, 13, 14, 15, 17, 18, 19);
        var metadata = info.withoutCdr();
        assert metadata.cdr() == null && info.cdr().length == 1000;
        assert metadata.equals(new WireSample(null, true, 11, 12, 16, 1, 4, 13, 14, 15, 17, 18, 19));
        assert metadata.withoutCdr() == metadata;
        int participant = Native.participant(94);
        try {
            var support = PayloadEntry.typeSupport();
            int topic = Native.topic(participant, "CdrTransfer", support.typeName(), support.hasKey(),
                support.typeInformation(), support.typeMapping());
            int reader = Native.endpoint(participant, topic, false, true, 8, 10_000_000_000L);
            int writer = Native.endpoint(participant, topic, true, true, 8, 10_000_000_000L);
            Checks.await(() -> Native.matched(writer, true) == 1);
            byte[] input = new byte[65536]; for (int i = 0; i < input.length; i++) input[i] = (byte) i;
            var value = new PayloadEntry(7, new Position(1.25, -2.5), "large", new PackedByteSequence(input));
            byte[] expected = encode(value);
            byte[] padded = Arrays.copyOf(expected, expected.length + 4096);
            Arrays.fill(padded, expected.length, padded.length, (byte) 77);
            Native.writeCdr(writer, padded, expected.length, -1, false);
            Arrays.fill(padded, (byte) 99); // Native storage must already be independent.
            assert Arrays.equals(take(reader), expected);
            for (int length : new int[]{-1, 0, 3, padded.length + 1})
                Checks.rejects(DdsException.class, () -> Native.writeCdr(writer, padded, length, -1, false));
            Checks.rejects(DdsException.class, () -> Native.writeCdr(writer, null, 4, -1, false));
            Checks.rejects(DdsException.class, () -> Native.writeCdr(writer, new byte[16], 16, -1, false));

            var borrowed = CdrWriter.pooled(2);
            PayloadEntry.codec().write(borrowed, value);
            byte[] snapshot = borrowed.toByteArray();
            borrowed.writeTo(writer, -1, false);
            borrowed.close();
            Checks.rejects(IllegalStateException.class, () -> borrowed.writeTo(writer, -1, false));
            value.set_payload(new PackedByteSequence(new byte[]{9, 8, 7})); value.set_label("small");
            try (var reused = CdrWriter.pooled(2)) {
                PayloadEntry.codec().write(reused, value);
                reused.writeTo(writer, 123456, false); // Used length, not the reused buffer capacity.
            }
            byte[] first = take(reader);
            assert Arrays.equals(first, snapshot);
            var large = PayloadEntry.codec().read(new CdrReader(first));
            assert Arrays.equals(large.get_payload().toArray(new byte[0]), input);
            var small = PayloadEntry.codec().read(new CdrReader(take(reader)));
            assert small.get_id() == 7 && small.get_label().equals("small");
            assert small.get_payload().equals(value.get_payload());
            assert Arrays.equals(first, snapshot); // A later take cannot overwrite earlier output.
        } finally { Native.delete(participant); }
        System.out.println("CDR used lengths, synchronous ownership, pool reuse and metadata-only retention passed");
    }
}
