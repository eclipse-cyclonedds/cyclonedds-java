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

package org.eclipse.cyclonedds.type.seq;

import java.io.IOException;
import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.ByteBuffer;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Objects;
import java.util.RandomAccess;
import org.omg.type.ByteSeq;

/** Owned, compact byte storage with the List/ByteSeq API.
 * Inserting null switches to boxed storage to preserve ordinary List semantics. */
public final class PackedByteSequence extends AbstractList<Byte> implements ByteSeq, RandomAccess {
    private static final long serialVersionUID = 1L;
    private byte[] elements;
    private int size;
    private ArrayList<Byte> boxed;

    public PackedByteSequence() { this(0); }
    public PackedByteSequence(int initialCapacity) {
        if (initialCapacity < 0) throw new IllegalArgumentException("Negative capacity");
        elements = new byte[initialCapacity];
    }
    public PackedByteSequence(byte[] values) { elements = values.clone(); size = values.length; }

    @Override public int size() { return boxed == null ? size : boxed.size(); }
    public void ensureCapacity(int capacity) {
        if (boxed != null) { boxed.ensureCapacity(capacity); return; }
        if (capacity > elements.length) {
            int grown = (int) Math.min(Integer.MAX_VALUE,
                Math.max((long) capacity, elements.length + elements.length / 2L + 1));
            elements = Arrays.copyOf(elements, grown);
        }
    }
    private void box() {
        if (boxed != null) return;
        boxed = new ArrayList<>(size);
        for (int i = 0; i < size; i++) boxed.add(elements[i]);
        elements = new byte[0]; size = 0;
    }
    private void insertionIndex(int index) {
        if (index < 0 || index > size()) throw new IndexOutOfBoundsException(index);
    }
    @Override public Byte get(int index) {
        if (boxed != null) return boxed.get(index);
        Objects.checkIndex(index, size); return elements[index];
    }
    @Override public Byte set(int index, Byte value) {
        Objects.checkIndex(index, size());
        if (value == null) box();
        if (boxed != null) return boxed.set(index, value);
        byte previous = elements[index]; elements[index] = value; return previous;
    }
    @Override public void set(int index, byte value) {
        Objects.checkIndex(index, size());
        if (boxed != null) boxed.set(index, value); else elements[index] = value;
    }
    @Override public boolean add(Byte value) {
        if (value == null) { box(); boxed.add(null); modCount++; return true; }
        return add(value.byteValue());
    }
    @Override public boolean add(byte value) {
        if (boxed != null) boxed.add(value);
        else { ensureCapacity(Math.addExact(size, 1)); elements[size++] = value; }
        modCount++; return true;
    }
    @Override public void add(int index, Byte value) {
        insertionIndex(index);
        if (value == null) box();
        if (boxed != null) boxed.add(index, value);
        else {
            ensureCapacity(Math.addExact(size, 1));
            System.arraycopy(elements, index, elements, index + 1, size - index);
            elements[index] = value; size++;
        }
        modCount++;
    }
    @Override public void add(int index, byte value) { add(index, Byte.valueOf(value)); }
    @Override public Byte remove(int index) {
        Objects.checkIndex(index, size());
        Byte previous;
        if (boxed != null) previous = boxed.remove(index);
        else {
            previous = elements[index];
            System.arraycopy(elements, index + 1, elements, index, size - index - 1);
            elements[--size] = 0;
        }
        modCount++; return previous;
    }
    @Override protected void removeRange(int from, int to) {
        Objects.checkFromToIndex(from, to, size());
        if (from == to) return;
        if (boxed != null) boxed.subList(from, to).clear();
        else {
            System.arraycopy(elements, to, elements, from, size - to);
            Arrays.fill(elements, size - (to - from), size, (byte) 0);
            size -= to - from;
        }
        modCount++;
    }
    @Override public void clear() {
        if (boxed != null) boxed.clear(); else Arrays.fill(elements, 0, size, (byte) 0);
        size = 0; modCount++;
    }
    @Override public boolean addAll(Collection<? extends Byte> values) { return addAll(size(), values); }
    @Override public boolean addAll(int index, Collection<? extends Byte> values) {
        insertionIndex(index);
        if (boxed == null && values instanceof PackedByteSequence other && other.boxed == null) {
            int count = other.size;
            if (count == 0) return false;
            byte[] input = other == this ? Arrays.copyOf(elements, size) : other.elements;
            ensureCapacity(Math.addExact(size, count));
            System.arraycopy(elements, index, elements, index + count, size - index);
            System.arraycopy(input, 0, elements, index, count);
            size += count; modCount++; return true;
        }
        Object[] input = values.toArray(); // Snapshot also handles self-add and sublists.
        if (input.length == 0) return false;
        for (Object value : input) if (value == null) { box(); break; }
        if (boxed != null) {
            var copy = new ArrayList<Byte>(input.length);
            for (Object value : input) copy.add((Byte) value);
            boxed.addAll(index, copy);
        } else {
            ensureCapacity(Math.addExact(size, input.length));
            System.arraycopy(elements, index, elements, index + input.length, size - index);
            for (int i = 0; i < input.length; i++) elements[index + i] = (Byte) input[i];
            size += input.length;
        }
        modCount++; return true;
    }
    @Override public boolean add(byte[] values, int offset, int count) {
        Objects.checkFromIndexSize(offset, count, values.length);
        if (count == 0) return false;
        if (boxed != null) {
            boxed.ensureCapacity(Math.addExact(boxed.size(), count));
            for (int i = 0; i < count; i++) boxed.add(values[offset + i]);
        } else {
            ensureCapacity(Math.addExact(size, count));
            System.arraycopy(values, offset, elements, size, count); size += count;
        }
        modCount++; return true;
    }
    @Override public void set(int destination, byte[] values, int source, int count) {
        Objects.checkFromIndexSize(source, count, values.length);
        Objects.checkFromIndexSize(destination, count, size());
        if (boxed != null) for (int i = 0; i < count; i++) boxed.set(destination + i, values[source + i]);
        else System.arraycopy(values, source, elements, destination, count);
    }
    @Override public byte[] toArray(byte[] destination) {
        int count = size();
        if (destination.length < count) destination = new byte[count];
        if (boxed != null) for (int i = 0; i < count; i++) destination[i] = boxed.get(i);
        else System.arraycopy(elements, 0, destination, 0, count);
        return destination;
    }
    /** Copies values without exposing the owned backing array. Null values are rejected. */
    public void copyTo(ByteBuffer destination) {
        if (destination.remaining() < size()) throw new java.nio.BufferOverflowException();
        if (boxed != null) for (Byte value : boxed) destination.put(Objects.requireNonNull(value));
        else destination.put(elements, 0, size);
    }
    /** Appends an owned copy and consumes exactly count bytes from source. */
    public void addFrom(ByteBuffer source, int count) {
        if (count < 0 || count > source.remaining()) throw new IndexOutOfBoundsException(count);
        if (count == 0) return;
        ensureCapacity(Math.addExact(size(), count));
        if (boxed != null) for (int i = 0; i < count; i++) boxed.add(source.get());
        else { source.get(elements, size, count); size += count; }
        modCount++;
    }
    private void writeObject(ObjectOutputStream output) throws IOException {
        var fields = output.putFields();
        fields.put("elements", elements.length == size ? elements : Arrays.copyOf(elements, size));
        fields.put("size", size);
        fields.put("boxed", boxed);
        output.writeFields();
    }
    private void readObject(ObjectInputStream input) throws IOException, ClassNotFoundException {
        input.defaultReadObject();
        if (elements == null || size < 0 || size > elements.length
                || boxed != null && (size != 0 || elements.length != 0))
            throw new InvalidObjectException("Invalid packed byte sequence storage");
    }
}
