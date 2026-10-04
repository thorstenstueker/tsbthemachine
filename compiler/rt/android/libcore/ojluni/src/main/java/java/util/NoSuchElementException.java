/*
 * Copyright (c) 1994, 2012, Oracle and/or its affiliates. All rights reserved.
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

package java.util;

/**
 * Thrown by various accessor methods to indicate that the element being requested
 * does not exist.
 *
 * @author  unascribed
 * @see     java.util.Enumeration#nextElement()
 * @see     java.util.Iterator#next()
 * @since   JDK1.0
 */
public
class NoSuchElementException extends RuntimeException {
    private static final long serialVersionUID = 6769829250639411880L;

    /**
     * Constructs a <code>NoSuchElementException</code> with <tt>null</tt>
     * as its error message string.
     */
    public NoSuchElementException() {
        super();
    }

    /**
     * Constructs a <code>NoSuchElementException</code>, saving a reference
     * to the error message string <tt>s</tt> for later retrieval by the
     * <tt>getMessage</tt> method.
     *
     * @param   s   the detail message.
     */
    public NoSuchElementException(String s) {
        super(s);
    }

    // BEGIN tsb-added: the two causal constructors, Java 15.
    /**
     * Constructs a {@code NoSuchElementException} with the specified detail
     * message and cause.
     *
     * @param  s the detail message, which is saved for later retrieval by the
     *         {@link #getMessage()} method
     * @param  cause the cause, which is saved for later retrieval by the
     *         {@link #getCause()} method. A {@code null} value is permitted and
     *         indicates that the cause is nonexistent or unknown.
     *
     * @since 15
     */
    public NoSuchElementException(String s, Throwable cause) {
        super(s, cause);
    }

    /**
     * Constructs a {@code NoSuchElementException} with the specified cause.
     * The detail message is set to {@code cause.toString()} — the behaviour the
     * two-argument {@code RuntimeException} constructor has always had, and the
     * reason this cannot simply pass null for the message.
     *
     * @param  cause the cause, which is saved for later retrieval by the
     *         {@link #getCause()} method. A {@code null} value is permitted and
     *         indicates that the cause is nonexistent or unknown.
     *
     * @since 15
     */
    public NoSuchElementException(Throwable cause) {
        super(cause);
    }
    // END tsb-added.
}
