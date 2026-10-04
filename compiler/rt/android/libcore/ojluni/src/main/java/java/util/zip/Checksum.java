/*
 * Copyright (c) 1996, 1999, Oracle and/or its affiliates. All rights reserved.
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

package java.util.zip;

import java.nio.ByteBuffer;

/**
 * An interface representing a data checksum.
 *
 * @author      David Connelly
 */
public
interface Checksum {
    /**
     * Updates the current checksum with the specified byte.
     *
     * @param b the byte to update the checksum with
     */
    public void update(int b);

    // BEGIN tsb-added: the two Java 9 defaults.
    //
    // On the interface, so CRC32, Adler32, CRC32C and anybody's own Checksum gain them at once
    // without a line written in any of them — the same leverage ExecutorService.close() had.
    /**
     * Updates the current checksum with the specified array of bytes.
     *
     * @implSpec This default implementation is equivalent to calling
     * {@code update(b, 0, b.length)}.
     *
     * @param b the array of bytes to update the checksum with
     * @throws NullPointerException if {@code b} is {@code null}
     *
     * @since 9
     */
    default public void update(byte[] b) {
        update(b, 0, b.length);
    }
    // END tsb-added.

    /**
     * Updates the current checksum with the specified array of bytes.
     * @param b the byte array to update the checksum with
     * @param off the start offset of the data
     * @param len the number of bytes to use for the update
     */
    public void update(byte[] b, int off, int len);

    // BEGIN tsb-added.
    /**
     * Updates the current checksum with the bytes from the specified buffer.
     *
     * <p> The checksum is updated with the remaining bytes in the buffer, starting at the
     * buffer's position. Upon return, the buffer's position will be updated to its limit; its
     * limit will not have been changed.
     *
     * @implSpec For a buffer with an accessible backing array this delegates to
     * {@code update(array, position + arrayOffset, remaining)} in one call. For a direct buffer —
     * which has no array — it copies through a scratch array of at most four kilobytes, because
     * the alternative is a per-byte loop across the JNI boundary and a direct buffer is usually
     * the large one.
     *
     * @param buffer the ByteBuffer to update the checksum with
     * @throws NullPointerException if {@code buffer} is {@code null}
     *
     * @since 9
     */
    default public void update(ByteBuffer buffer) {
        int pos = buffer.position();
        int limit = buffer.limit();
        int rem = limit - pos;
        if (rem <= 0) {
            return;
        }
        if (buffer.hasArray()) {
            update(buffer.array(), pos + buffer.arrayOffset(), rem);
        } else {
            byte[] b = new byte[Math.min(buffer.remaining(), 4096)];
            while (buffer.hasRemaining()) {
                int length = Math.min(buffer.remaining(), b.length);
                buffer.get(b, 0, length);
                update(b, 0, length);
            }
        }
        // Set rather than advanced: the array branch above read the buffer without moving its
        // position at all, and both branches have to leave it in the same place.
        buffer.position(limit);
    }
    // END tsb-added.

    /**
     * Returns the current checksum value.
     * @return the current checksum value
     */
    public long getValue();

    /**
     * Resets the checksum to its initial value.
     */
    public void reset();
}
