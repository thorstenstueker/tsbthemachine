/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

/*
 * This file is available under and governed by the GNU General Public
 * License version 2 only, as published by the Free Software Foundation.
 * However, the following notice accompanied the original version of this
 * file:
 *
 * Written by Doug Lea with assistance from members of JCP JSR-166
 * Expert Group and released to the public domain, as explained at
 * http://creativecommons.org/publicdomain/zero/1.0/
 */

package java.util.concurrent.atomic;

import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.function.BinaryOperator;
import java.util.function.UnaryOperator;

/**
 * An array of object references in which elements may be updated
 * atomically.  See the {@link java.util.concurrent.atomic} package
 * specification for description of the properties of atomic
 * variables.
 * @since 1.5
 * @author Doug Lea
 * @param <E> The base class of elements held in this array
 */
public class AtomicReferenceArray<E> implements java.io.Serializable {
    private static final long serialVersionUID = -6209656149925076980L;

    private static final sun.misc.Unsafe U = sun.misc.Unsafe.getUnsafe();
    private static final long ARRAY;
    private static final int ABASE;
    private static final int ASHIFT;
    private final Object[] array; // must have exact type Object[]

    static {
        try {
            ARRAY = U.objectFieldOffset
                (AtomicReferenceArray.class.getDeclaredField("array"));
            ABASE = U.arrayBaseOffset(Object[].class);
            int scale = U.arrayIndexScale(Object[].class);
            if ((scale & (scale - 1)) != 0)
                throw new Error("array index scale not a power of two");
            ASHIFT = 31 - Integer.numberOfLeadingZeros(scale);
        } catch (ReflectiveOperationException e) {
            throw new Error(e);
        }
    }

    private long checkedByteOffset(int i) {
        if (i < 0 || i >= array.length)
            throw new IndexOutOfBoundsException("index " + i);

        return byteOffset(i);
    }

    private static long byteOffset(int i) {
        return ((long) i << ASHIFT) + ABASE;
    }

    /**
     * Creates a new AtomicReferenceArray of the given length, with all
     * elements initially null.
     *
     * @param length the length of the array
     */
    public AtomicReferenceArray(int length) {
        array = new Object[length];
    }

    /**
     * Creates a new AtomicReferenceArray with the same length as, and
     * all elements copied from, the given array.
     *
     * @param array the array to copy elements from
     * @throws NullPointerException if array is null
     */
    public AtomicReferenceArray(E[] array) {
        // Visibility guaranteed by final field guarantees
        this.array = Arrays.copyOf(array, array.length, Object[].class);
    }

    /**
     * Returns the length of the array.
     *
     * @return the length of the array
     */
    public final int length() {
        return array.length;
    }

    /**
     * Gets the current value at position {@code i}.
     *
     * @param i the index
     * @return the current value
     */
    public final E get(int i) {
        return getRaw(checkedByteOffset(i));
    }

    @SuppressWarnings("unchecked")
    private E getRaw(long offset) {
        return (E) U.getObjectVolatile(array, offset);
    }

    /**
     * Sets the element at position {@code i} to the given value.
     *
     * @param i the index
     * @param newValue the new value
     */
    public final void set(int i, E newValue) {
        U.putObjectVolatile(array, checkedByteOffset(i), newValue);
    }

    /**
     * Eventually sets the element at position {@code i} to the given value.
     *
     * @param i the index
     * @param newValue the new value
     * @since 1.6
     */
    public final void lazySet(int i, E newValue) {
        U.putOrderedObject(array, checkedByteOffset(i), newValue);
    }

    /**
     * Atomically sets the element at position {@code i} to the given
     * value and returns the old value.
     *
     * @param i the index
     * @param newValue the new value
     * @return the previous value
     */
    @SuppressWarnings("unchecked")
    public final E getAndSet(int i, E newValue) {
        return (E)U.getAndSetObject(array, checkedByteOffset(i), newValue);
    }

    /**
     * Atomically sets the element at position {@code i} to the given
     * updated value if the current value {@code ==} the expected value.
     *
     * @param i the index
     * @param expect the expected value
     * @param update the new value
     * @return {@code true} if successful. False return indicates that
     * the actual value was not equal to the expected value.
     */
    public final boolean compareAndSet(int i, E expect, E update) {
        return compareAndSetRaw(checkedByteOffset(i), expect, update);
    }

    private boolean compareAndSetRaw(long offset, E expect, E update) {
        return U.compareAndSwapObject(array, offset, expect, update);
    }

    /**
     * Atomically sets the element at position {@code i} to the given
     * updated value if the current value {@code ==} the expected value.
     *
     * <p><a href="package-summary.html#weakCompareAndSet">May fail
     * spuriously and does not provide ordering guarantees</a>, so is
     * only rarely an appropriate alternative to {@code compareAndSet}.
     *
     * @param i the index
     * @param expect the expected value
     * @param update the new value
     * @return {@code true} if successful
     */
    public final boolean weakCompareAndSet(int i, E expect, E update) {
        return compareAndSet(i, expect, update);
    }

    /**
     * Atomically updates the element at index {@code i} with the results
     * of applying the given function, returning the previous value. The
     * function should be side-effect-free, since it may be re-applied
     * when attempted updates fail due to contention among threads.
     *
     * @param i the index
     * @param updateFunction a side-effect-free function
     * @return the previous value
     * @since 1.8
     */
    public final E getAndUpdate(int i, UnaryOperator<E> updateFunction) {
        long offset = checkedByteOffset(i);
        E prev, next;
        do {
            prev = getRaw(offset);
            next = updateFunction.apply(prev);
        } while (!compareAndSetRaw(offset, prev, next));
        return prev;
    }

    /**
     * Atomically updates the element at index {@code i} with the results
     * of applying the given function, returning the updated value. The
     * function should be side-effect-free, since it may be re-applied
     * when attempted updates fail due to contention among threads.
     *
     * @param i the index
     * @param updateFunction a side-effect-free function
     * @return the updated value
     * @since 1.8
     */
    public final E updateAndGet(int i, UnaryOperator<E> updateFunction) {
        long offset = checkedByteOffset(i);
        E prev, next;
        do {
            prev = getRaw(offset);
            next = updateFunction.apply(prev);
        } while (!compareAndSetRaw(offset, prev, next));
        return next;
    }

    /**
     * Atomically updates the element at index {@code i} with the
     * results of applying the given function to the current and
     * given values, returning the previous value. The function should
     * be side-effect-free, since it may be re-applied when attempted
     * updates fail due to contention among threads.  The function is
     * applied with the current value at index {@code i} as its first
     * argument, and the given update as the second argument.
     *
     * @param i the index
     * @param x the update value
     * @param accumulatorFunction a side-effect-free function of two arguments
     * @return the previous value
     * @since 1.8
     */
    public final E getAndAccumulate(int i, E x,
                                    BinaryOperator<E> accumulatorFunction) {
        long offset = checkedByteOffset(i);
        E prev, next;
        do {
            prev = getRaw(offset);
            next = accumulatorFunction.apply(prev, x);
        } while (!compareAndSetRaw(offset, prev, next));
        return prev;
    }

    /**
     * Atomically updates the element at index {@code i} with the
     * results of applying the given function to the current and
     * given values, returning the updated value. The function should
     * be side-effect-free, since it may be re-applied when attempted
     * updates fail due to contention among threads.  The function is
     * applied with the current value at index {@code i} as its first
     * argument, and the given update as the second argument.
     *
     * @param i the index
     * @param x the update value
     * @param accumulatorFunction a side-effect-free function of two arguments
     * @return the updated value
     * @since 1.8
     */
    public final E accumulateAndGet(int i, E x,
                                    BinaryOperator<E> accumulatorFunction) {
        long offset = checkedByteOffset(i);
        E prev, next;
        do {
            prev = getRaw(offset);
            next = accumulatorFunction.apply(prev, x);
        } while (!compareAndSetRaw(offset, prev, next));
        return next;
    }

    /**
     * Returns the String representation of the current values of array.
     * @return the String representation of the current values of array
     */
    public String toString() {
        int iMax = array.length - 1;
        if (iMax == -1)
            return "[]";

        StringBuilder b = new StringBuilder();
        b.append('[');
        for (int i = 0; ; i++) {
            b.append(getRaw(byteOffset(i)));
            if (i == iMax)
                return b.append(']').toString();
            b.append(',').append(' ');
        }
    }

    /**
     * Reconstitutes the instance from a stream (that is, deserializes it).
     * @param s the stream
     * @throws ClassNotFoundException if the class of a serialized object
     *         could not be found
     * @throws java.io.IOException if an I/O error occurs
     */
    private void readObject(java.io.ObjectInputStream s)
        throws java.io.IOException, ClassNotFoundException {
        // Note: This must be changed if any additional fields are defined
        Object a = s.readFields().get("array", null);
        if (a == null || !a.getClass().isArray())
            throw new java.io.InvalidObjectException("Not array type");
        if (a.getClass() != Object[].class)
            a = Arrays.copyOf((Object[])a, Array.getLength(a), Object[].class);
        U.putObjectVolatile(this, ARRAY, a);
    }

    // The memory-order family Java 9 added alongside VarHandle. Added 29.09.2026 (tsb).
    //
    // Every one of them is implemented with a *stronger* ordering than its name asks for: plain,
    // opaque and acquire reads all go through getObjectVolatile, and plain and opaque writes through
    // putObjectVolatile. That is allowed -- the specification states a minimum each mode must provide, and
    // providing more cannot break a caller -- and it is what this library can honestly offer,
    // because sun.misc.Unsafe here has volatile, ordered and compare-and-swap and nothing between
    // them. setRelease is the one exception: putOrderedObject *is* a release store, so that one is exact.
    //
    // The cost is on the weakest end. getPlain in a tight loop pays a volatile load per iteration
    // where the JDK pays none, so code written to exploit plain access will run slower here rather
    // than wrong. Correct and not literal, and better named than silently absent.

    /**
     * Returns the current value of the element at index {@code i}, with memory semantics of
     * reading as if the variable was declared non-{@code volatile}.
     *
     * @param i the index
     * @return the value
     * @since 9
     */
    public final E getPlain(int i) {
        return getRaw(checkedByteOffset(i));
    }

    /**
     * Sets the element at index {@code i} to {@code newValue}, with memory semantics of setting
     * as if the variable was declared non-{@code volatile} and non-{@code final}.
     *
     * @param i the index
     * @param newValue the new value
     * @since 9
     */
    public final void setPlain(int i, E newValue) {
        U.putObjectVolatile(array, checkedByteOffset(i), newValue);
    }

    /**
     * Returns the current value of the element at index {@code i}, with memory effects as
     * specified by {@code VarHandle#getOpaque}.
     *
     * @param i the index
     * @return the value
     * @since 9
     */
    public final E getOpaque(int i) {
        return getRaw(checkedByteOffset(i));
    }

    /**
     * Sets the element at index {@code i} to {@code newValue}, with memory effects as specified
     * by {@code VarHandle#setOpaque}.
     *
     * @param i the index
     * @param newValue the new value
     * @since 9
     */
    public final void setOpaque(int i, E newValue) {
        U.putObjectVolatile(array, checkedByteOffset(i), newValue);
    }

    /**
     * Returns the current value of the element at index {@code i}, with memory effects as
     * specified by {@code VarHandle#getAcquire}.
     *
     * @param i the index
     * @return the value
     * @since 9
     */
    public final E getAcquire(int i) {
        return getRaw(checkedByteOffset(i));
    }

    /**
     * Sets the element at index {@code i} to {@code newValue}, with memory effects as specified
     * by {@code VarHandle#setRelease}.
     *
     * @param i the index
     * @param newValue the new value
     * @since 9
     */
    public final void setRelease(int i, E newValue) {
        U.putOrderedObject(array, checkedByteOffset(i), newValue);
    }

    /**
     * Atomically sets the element at index {@code i} to {@code newValue} if the element's
     * current value, referred to as the <em>witness value</em>, {@code ==} the expected value,
     * with memory effects as specified by {@code VarHandle#compareAndExchange}.
     *
     * @param i the index
     * @param expectedValue the expected value
     * @param newValue the new value
     * @return the witness value, which will be the same as the expected value if successful
     * @since 9
     */
    public final E compareAndExchange(int i, E expectedValue, E newValue) {
        // Unsafe gives back only whether the swap happened, not what stood in the way, so the
        // witness has to be read. Re-reading after a failed swap rather than trusting the first
        // read: between the two, another thread may have put the expected value there after all,
        // and returning the stale witness would report a failure that did not happen.
        long offset = checkedByteOffset(i);
        E witness = getRaw(offset);
        while (witness == expectedValue) {
            if (compareAndSetRaw(offset, expectedValue, newValue)) {
                return expectedValue;
            }
            witness = getRaw(offset);
        }
        return witness;
    }

    /**
     * Atomically sets the element at index {@code i} to {@code newValue} if the element's
     * current value, referred to as the <em>witness value</em>, {@code ==} the expected value,
     * with memory effects as specified by {@code VarHandle#compareAndExchangeAcquire}.
     *
     * @param i the index
     * @param expectedValue the expected value
     * @param newValue the new value
     * @return the witness value, which will be the same as the expected value if successful
     * @since 9
     */
    public final E compareAndExchangeAcquire(int i, E expectedValue, E newValue) {
        return compareAndExchange(i, expectedValue, newValue);
    }

    /**
     * Atomically sets the element at index {@code i} to {@code newValue} if the element's
     * current value, referred to as the <em>witness value</em>, {@code ==} the expected value,
     * with memory effects as specified by {@code VarHandle#compareAndExchangeRelease}.
     *
     * @param i the index
     * @param expectedValue the expected value
     * @param newValue the new value
     * @return the witness value, which will be the same as the expected value if successful
     * @since 9
     */
    public final E compareAndExchangeRelease(int i, E expectedValue, E newValue) {
        return compareAndExchange(i, expectedValue, newValue);
    }

    /**
     * Possibly atomically sets the element at index {@code i} to {@code newValue} if the
     * element's current value {@code ==} the expected value, with memory effects as specified by
     * {@code VarHandle#weakCompareAndSetPlain}.
     *
     * @param i the index
     * @param expectedValue the expected value
     * @param newValue the new value
     * @return {@code true} if successful
     * @since 9
     */
    public final boolean weakCompareAndSetPlain(int i, E expectedValue, E newValue) {
        return compareAndSet(i, expectedValue, newValue);
    }

    /**
     * Possibly atomically sets the element at index {@code i} to {@code newValue} if the
     * element's current value {@code ==} the expected value, with memory effects as specified by
     * {@code VarHandle#weakCompareAndSet}.
     *
     * @param i the index
     * @param expectedValue the expected value
     * @param newValue the new value
     * @return {@code true} if successful
     * @since 9
     */
    public final boolean weakCompareAndSetVolatile(int i, E expectedValue, E newValue) {
        return compareAndSet(i, expectedValue, newValue);
    }

    /**
     * Possibly atomically sets the element at index {@code i} to {@code newValue} if the
     * element's current value {@code ==} the expected value, with memory effects as specified by
     * {@code VarHandle#weakCompareAndSetAcquire}.
     *
     * @param i the index
     * @param expectedValue the expected value
     * @param newValue the new value
     * @return {@code true} if successful
     * @since 9
     */
    public final boolean weakCompareAndSetAcquire(int i, E expectedValue, E newValue) {
        return compareAndSet(i, expectedValue, newValue);
    }

    /**
     * Possibly atomically sets the element at index {@code i} to {@code newValue} if the
     * element's current value {@code ==} the expected value, with memory effects as specified by
     * {@code VarHandle#weakCompareAndSetRelease}.
     *
     * @param i the index
     * @param expectedValue the expected value
     * @param newValue the new value
     * @return {@code true} if successful
     * @since 9
     */
    public final boolean weakCompareAndSetRelease(int i, E expectedValue, E newValue) {
        return compareAndSet(i, expectedValue, newValue);
    }

}
