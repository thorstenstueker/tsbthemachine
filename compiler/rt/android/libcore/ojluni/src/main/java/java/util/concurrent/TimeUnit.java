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

package java.util.concurrent;

// tsb-added: for convert(Duration), toChronoUnit() and of(ChronoUnit) — see the block at the end.
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * A {@code TimeUnit} represents time durations at a given unit of
 * granularity and provides utility methods to convert across units,
 * and to perform timing and delay operations in these units.  A
 * {@code TimeUnit} does not maintain time information, but only
 * helps organize and use time representations that may be maintained
 * separately across various contexts.  A nanosecond is defined as one
 * thousandth of a microsecond, a microsecond as one thousandth of a
 * millisecond, a millisecond as one thousandth of a second, a minute
 * as sixty seconds, an hour as sixty minutes, and a day as twenty four
 * hours.
 *
 * <p>A {@code TimeUnit} is mainly used to inform time-based methods
 * how a given timing parameter should be interpreted. For example,
 * the following code will timeout in 50 milliseconds if the {@link
 * java.util.concurrent.locks.Lock lock} is not available:
 *
 * <pre> {@code
 * Lock lock = ...;
 * if (lock.tryLock(50L, TimeUnit.MILLISECONDS)) ...}</pre>
 *
 * while this code will timeout in 50 seconds:
 * <pre> {@code
 * Lock lock = ...;
 * if (lock.tryLock(50L, TimeUnit.SECONDS)) ...}</pre>
 *
 * Note however, that there is no guarantee that a particular timeout
 * implementation will be able to notice the passage of time at the
 * same granularity as the given {@code TimeUnit}.
 *
 * @since 1.5
 * @author Doug Lea
 */
public enum TimeUnit {
    /**
     * Time unit representing one thousandth of a microsecond.
     */
    NANOSECONDS {
        public long toNanos(long d)   { return d; }
        public long toMicros(long d)  { return d/(C1/C0); }
        public long toMillis(long d)  { return d/(C2/C0); }
        public long toSeconds(long d) { return d/(C3/C0); }
        public long toMinutes(long d) { return d/(C4/C0); }
        public long toHours(long d)   { return d/(C5/C0); }
        public long toDays(long d)    { return d/(C6/C0); }
        public long convert(long d, TimeUnit u) { return u.toNanos(d); }
        int excessNanos(long d, long m) { return (int)(d - (m*C2)); }
    },

    /**
     * Time unit representing one thousandth of a millisecond.
     */
    MICROSECONDS {
        public long toNanos(long d)   { return x(d, C1/C0, MAX/(C1/C0)); }
        public long toMicros(long d)  { return d; }
        public long toMillis(long d)  { return d/(C2/C1); }
        public long toSeconds(long d) { return d/(C3/C1); }
        public long toMinutes(long d) { return d/(C4/C1); }
        public long toHours(long d)   { return d/(C5/C1); }
        public long toDays(long d)    { return d/(C6/C1); }
        public long convert(long d, TimeUnit u) { return u.toMicros(d); }
        int excessNanos(long d, long m) { return (int)((d*C1) - (m*C2)); }
    },

    /**
     * Time unit representing one thousandth of a second.
     */
    MILLISECONDS {
        public long toNanos(long d)   { return x(d, C2/C0, MAX/(C2/C0)); }
        public long toMicros(long d)  { return x(d, C2/C1, MAX/(C2/C1)); }
        public long toMillis(long d)  { return d; }
        public long toSeconds(long d) { return d/(C3/C2); }
        public long toMinutes(long d) { return d/(C4/C2); }
        public long toHours(long d)   { return d/(C5/C2); }
        public long toDays(long d)    { return d/(C6/C2); }
        public long convert(long d, TimeUnit u) { return u.toMillis(d); }
        int excessNanos(long d, long m) { return 0; }
    },

    /**
     * Time unit representing one second.
     */
    SECONDS {
        public long toNanos(long d)   { return x(d, C3/C0, MAX/(C3/C0)); }
        public long toMicros(long d)  { return x(d, C3/C1, MAX/(C3/C1)); }
        public long toMillis(long d)  { return x(d, C3/C2, MAX/(C3/C2)); }
        public long toSeconds(long d) { return d; }
        public long toMinutes(long d) { return d/(C4/C3); }
        public long toHours(long d)   { return d/(C5/C3); }
        public long toDays(long d)    { return d/(C6/C3); }
        public long convert(long d, TimeUnit u) { return u.toSeconds(d); }
        int excessNanos(long d, long m) { return 0; }
    },

    /**
     * Time unit representing sixty seconds.
     * @since 1.6
     */
    MINUTES {
        public long toNanos(long d)   { return x(d, C4/C0, MAX/(C4/C0)); }
        public long toMicros(long d)  { return x(d, C4/C1, MAX/(C4/C1)); }
        public long toMillis(long d)  { return x(d, C4/C2, MAX/(C4/C2)); }
        public long toSeconds(long d) { return x(d, C4/C3, MAX/(C4/C3)); }
        public long toMinutes(long d) { return d; }
        public long toHours(long d)   { return d/(C5/C4); }
        public long toDays(long d)    { return d/(C6/C4); }
        public long convert(long d, TimeUnit u) { return u.toMinutes(d); }
        int excessNanos(long d, long m) { return 0; }
    },

    /**
     * Time unit representing sixty minutes.
     * @since 1.6
     */
    HOURS {
        public long toNanos(long d)   { return x(d, C5/C0, MAX/(C5/C0)); }
        public long toMicros(long d)  { return x(d, C5/C1, MAX/(C5/C1)); }
        public long toMillis(long d)  { return x(d, C5/C2, MAX/(C5/C2)); }
        public long toSeconds(long d) { return x(d, C5/C3, MAX/(C5/C3)); }
        public long toMinutes(long d) { return x(d, C5/C4, MAX/(C5/C4)); }
        public long toHours(long d)   { return d; }
        public long toDays(long d)    { return d/(C6/C5); }
        public long convert(long d, TimeUnit u) { return u.toHours(d); }
        int excessNanos(long d, long m) { return 0; }
    },

    /**
     * Time unit representing twenty four hours.
     * @since 1.6
     */
    DAYS {
        public long toNanos(long d)   { return x(d, C6/C0, MAX/(C6/C0)); }
        public long toMicros(long d)  { return x(d, C6/C1, MAX/(C6/C1)); }
        public long toMillis(long d)  { return x(d, C6/C2, MAX/(C6/C2)); }
        public long toSeconds(long d) { return x(d, C6/C3, MAX/(C6/C3)); }
        public long toMinutes(long d) { return x(d, C6/C4, MAX/(C6/C4)); }
        public long toHours(long d)   { return x(d, C6/C5, MAX/(C6/C5)); }
        public long toDays(long d)    { return d; }
        public long convert(long d, TimeUnit u) { return u.toDays(d); }
        int excessNanos(long d, long m) { return 0; }
    };

    // Handy constants for conversion methods
    static final long C0 = 1L;
    static final long C1 = C0 * 1000L;
    static final long C2 = C1 * 1000L;
    static final long C3 = C2 * 1000L;
    static final long C4 = C3 * 60L;
    static final long C5 = C4 * 60L;
    static final long C6 = C5 * 24L;

    static final long MAX = Long.MAX_VALUE;

    /**
     * Scale d by m, checking for overflow.
     * This has a short name to make above code more readable.
     */
    static long x(long d, long m, long over) {
        if (d > +over) return Long.MAX_VALUE;
        if (d < -over) return Long.MIN_VALUE;
        return d * m;
    }

    // To maintain full signature compatibility with 1.5, and to improve the
    // clarity of the generated javadoc (see 6287639: Abstract methods in
    // enum classes should not be listed as abstract), method convert
    // etc. are not declared abstract but otherwise act as abstract methods.

    /**
     * Converts the given time duration in the given unit to this unit.
     * Conversions from finer to coarser granularities truncate, so
     * lose precision. For example, converting {@code 999} milliseconds
     * to seconds results in {@code 0}. Conversions from coarser to
     * finer granularities with arguments that would numerically
     * overflow saturate to {@code Long.MIN_VALUE} if negative or
     * {@code Long.MAX_VALUE} if positive.
     *
     * <p>For example, to convert 10 minutes to milliseconds, use:
     * {@code TimeUnit.MILLISECONDS.convert(10L, TimeUnit.MINUTES)}
     *
     * @param sourceDuration the time duration in the given {@code sourceUnit}
     * @param sourceUnit the unit of the {@code sourceDuration} argument
     * @return the converted duration in this unit,
     * or {@code Long.MIN_VALUE} if conversion would negatively
     * overflow, or {@code Long.MAX_VALUE} if it would positively overflow.
     */
    public long convert(long sourceDuration, TimeUnit sourceUnit) {
        throw new AbstractMethodError();
    }

    /**
     * Equivalent to
     * {@link #convert(long, TimeUnit) NANOSECONDS.convert(duration, this)}.
     * @param duration the duration
     * @return the converted duration,
     * or {@code Long.MIN_VALUE} if conversion would negatively
     * overflow, or {@code Long.MAX_VALUE} if it would positively overflow.
     */
    public long toNanos(long duration) {
        throw new AbstractMethodError();
    }

    /**
     * Equivalent to
     * {@link #convert(long, TimeUnit) MICROSECONDS.convert(duration, this)}.
     * @param duration the duration
     * @return the converted duration,
     * or {@code Long.MIN_VALUE} if conversion would negatively
     * overflow, or {@code Long.MAX_VALUE} if it would positively overflow.
     */
    public long toMicros(long duration) {
        throw new AbstractMethodError();
    }

    /**
     * Equivalent to
     * {@link #convert(long, TimeUnit) MILLISECONDS.convert(duration, this)}.
     * @param duration the duration
     * @return the converted duration,
     * or {@code Long.MIN_VALUE} if conversion would negatively
     * overflow, or {@code Long.MAX_VALUE} if it would positively overflow.
     */
    public long toMillis(long duration) {
        throw new AbstractMethodError();
    }

    /**
     * Equivalent to
     * {@link #convert(long, TimeUnit) SECONDS.convert(duration, this)}.
     * @param duration the duration
     * @return the converted duration,
     * or {@code Long.MIN_VALUE} if conversion would negatively
     * overflow, or {@code Long.MAX_VALUE} if it would positively overflow.
     */
    public long toSeconds(long duration) {
        throw new AbstractMethodError();
    }

    /**
     * Equivalent to
     * {@link #convert(long, TimeUnit) MINUTES.convert(duration, this)}.
     * @param duration the duration
     * @return the converted duration,
     * or {@code Long.MIN_VALUE} if conversion would negatively
     * overflow, or {@code Long.MAX_VALUE} if it would positively overflow.
     * @since 1.6
     */
    public long toMinutes(long duration) {
        throw new AbstractMethodError();
    }

    /**
     * Equivalent to
     * {@link #convert(long, TimeUnit) HOURS.convert(duration, this)}.
     * @param duration the duration
     * @return the converted duration,
     * or {@code Long.MIN_VALUE} if conversion would negatively
     * overflow, or {@code Long.MAX_VALUE} if it would positively overflow.
     * @since 1.6
     */
    public long toHours(long duration) {
        throw new AbstractMethodError();
    }

    /**
     * Equivalent to
     * {@link #convert(long, TimeUnit) DAYS.convert(duration, this)}.
     * @param duration the duration
     * @return the converted duration
     * @since 1.6
     */
    public long toDays(long duration) {
        throw new AbstractMethodError();
    }

    /**
     * Utility to compute the excess-nanosecond argument to wait,
     * sleep, join.
     * @param d the duration
     * @param m the number of milliseconds
     * @return the number of nanoseconds
     */
    abstract int excessNanos(long d, long m);

    /**
     * Performs a timed {@link Object#wait(long, int) Object.wait}
     * using this time unit.
     * This is a convenience method that converts timeout arguments
     * into the form required by the {@code Object.wait} method.
     *
     * <p>For example, you could implement a blocking {@code poll}
     * method (see {@link BlockingQueue#poll BlockingQueue.poll})
     * using:
     *
     * <pre> {@code
     * public synchronized Object poll(long timeout, TimeUnit unit)
     *     throws InterruptedException {
     *   while (empty) {
     *     unit.timedWait(this, timeout);
     *     ...
     *   }
     * }}</pre>
     *
     * @param obj the object to wait on
     * @param timeout the maximum time to wait. If less than
     * or equal to zero, do not wait at all.
     * @throws InterruptedException if interrupted while waiting
     */
    public void timedWait(Object obj, long timeout)
            throws InterruptedException {
        if (timeout > 0) {
            long ms = toMillis(timeout);
            int ns = excessNanos(timeout, ms);
            obj.wait(ms, ns);
        }
    }

    /**
     * Performs a timed {@link Thread#join(long, int) Thread.join}
     * using this time unit.
     * This is a convenience method that converts time arguments into the
     * form required by the {@code Thread.join} method.
     *
     * @param thread the thread to wait for
     * @param timeout the maximum time to wait. If less than
     * or equal to zero, do not wait at all.
     * @throws InterruptedException if interrupted while waiting
     */
    public void timedJoin(Thread thread, long timeout)
            throws InterruptedException {
        if (timeout > 0) {
            long ms = toMillis(timeout);
            int ns = excessNanos(timeout, ms);
            thread.join(ms, ns);
        }
    }

    /**
     * Performs a {@link Thread#sleep(long, int) Thread.sleep} using
     * this time unit.
     * This is a convenience method that converts time arguments into the
     * form required by the {@code Thread.sleep} method.
     *
     * @param timeout the minimum time to sleep. If less than
     * or equal to zero, do not sleep at all.
     * @throws InterruptedException if interrupted while sleeping
     */
    public void sleep(long timeout) throws InterruptedException {
        if (timeout > 0) {
            long ms = toMillis(timeout);
            int ns = excessNanos(timeout, ms);
            Thread.sleep(ms, ns);
        }
    }

    // BEGIN tsb-added: the java.time bridge, 11.10.2026.
    //
    // The two ChronoUnit methods below were not written here — they were sitting in this file,
    // commented out, under "Android-removed: OpenJDK 9 ChronoUnit related code". Android removed
    // them because this library had no java.time to bridge to. B1 brought java.time in, and
    // nothing came back to collect them. They are OpenJDK's code, uncommented and otherwise
    // untouched.
    //
    // convert(Duration) could NOT be copied, and that is why this block has a comment at all.
    // OpenJDK 25's TimeUnit is a rewritten enum carrying scale, secRatio, maxSecs and
    // SECOND_SCALE as fields; this one is the older shape, where each constant overrides
    // convert(long, TimeUnit) and no such fields exist. So the body is written against what this
    // class has, and the saturation is the part to read twice: a Duration reaches eleven orders
    // of magnitude beyond what a long of nanoseconds holds, and the specification says the answer
    // saturates at Long.MIN_VALUE or Long.MAX_VALUE rather than wrapping.

    /**
     * Converts the given time duration to this unit.
     *
     * <p>For any TimeUnit {@code unit},
     * {@code unit.convert(Duration.ofNanos(n))}
     * is equivalent to
     * {@code unit.convert(n, NANOSECONDS)}, and
     * {@code unit.convert(Duration.of(n, unit.toChronoUnit()))}
     * is equivalent to {@code n} (in the absence of overflow).
     *
     * @param duration the time duration
     * @return the converted duration in this unit,
     *         or {@code Long.MIN_VALUE} if conversion would negatively overflow,
     *         or {@code Long.MAX_VALUE} if it would positively overflow
     * @throws NullPointerException if {@code duration} is null
     * @since 11
     */
    public long convert(Duration duration) {
        Objects.requireNonNull(duration, "duration");
        long secs = duration.getSeconds();
        int nano = duration.getNano();
        if (secs < 0 && nano > 0) {
            // A Duration keeps a non-negative nano adjustment to a possibly negative second
            // count, and integer division wants both parts pulling the same way, so the carry is
            // done by hand. OpenJDK's own comment on this line reads "use representation
            // compatible with integer division"; getting it wrong costs one unit of the result
            // for every negative duration with a fraction.
            secs++;
            nano -= 1000000000;
        }

        // The seconds and the fraction are converted separately and added. convert(long, TimeUnit)
        // saturates, so the first half already answers MIN_VALUE or MAX_VALUE for a duration out
        // of range — and in that case the fraction cannot change the answer.
        long fromSeconds = convert(secs, SECONDS);
        if (fromSeconds == Long.MAX_VALUE || fromSeconds == Long.MIN_VALUE) {
            return fromSeconds;
        }
        // Zero for every unit of a second or coarser, which is what OpenJDK's early returns for
        // SECONDS and the coarser units amount to: a fraction of a second is not expressible
        // there and is dropped rather than rounded.
        long fromNanos = convert(nano, NANOSECONDS);
        long total = fromSeconds + fromNanos;
        // The one case the saturation above cannot catch: both halves in range and the sum not.
        // After the carry both parts have the same sign, so a sum whose sign differs from both
        // operands overflowed.
        if (((fromSeconds ^ total) & (fromNanos ^ total)) < 0) {
            return fromSeconds > 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
        return total;
    }

    /**
     * Converts this {@code TimeUnit} to the equivalent {@code ChronoUnit}.
     *
     * @return the converted equivalent ChronoUnit
     * @since 9
     */
    public ChronoUnit toChronoUnit() {
        switch (this) {
        case NANOSECONDS:  return ChronoUnit.NANOS;
        case MICROSECONDS: return ChronoUnit.MICROS;
        case MILLISECONDS: return ChronoUnit.MILLIS;
        case SECONDS:      return ChronoUnit.SECONDS;
        case MINUTES:      return ChronoUnit.MINUTES;
        case HOURS:        return ChronoUnit.HOURS;
        case DAYS:         return ChronoUnit.DAYS;
        default: throw new AssertionError();
        }
    }

    /**
     * Converts a {@code ChronoUnit} to the equivalent {@code TimeUnit}.
     *
     * @param chronoUnit the ChronoUnit to convert
     * @return the converted equivalent TimeUnit
     * @throws IllegalArgumentException if {@code chronoUnit} has no
     *         equivalent TimeUnit
     * @throws NullPointerException if {@code chronoUnit} is null
     * @since 9
     */
    public static TimeUnit of(ChronoUnit chronoUnit) {
        switch (Objects.requireNonNull(chronoUnit, "chronoUnit")) {
        case NANOS:   return TimeUnit.NANOSECONDS;
        case MICROS:  return TimeUnit.MICROSECONDS;
        case MILLIS:  return TimeUnit.MILLISECONDS;
        case SECONDS: return TimeUnit.SECONDS;
        case MINUTES: return TimeUnit.MINUTES;
        case HOURS:   return TimeUnit.HOURS;
        case DAYS:    return TimeUnit.DAYS;
        default:
            throw new IllegalArgumentException(
                "No TimeUnit equivalent for " + chronoUnit);
        }
    }
    // END tsb-added.

}
