/*
 * Copyright 2026 Thorsten Stüker
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  This particular file is
 * subject to the "Classpath" exception as provided in the LICENSE file
 * that accompanied this code.
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
 */

package java.time.zone;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.Month;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The rule at the end of an Olson file: how daylight saving continues after the last transition.
 *
 * <h2>What this is for</h2>
 *
 * <p>A compiled time-zone file holds explicit transitions up to a cut-off — 2037 in the files
 * Apple's systems ship, because that is where a 32-bit {@code time_t} ends. After it, a version 2
 * file carries one line of text, the POSIX {@code TZ} string, which states the rule in general
 * rather than year by year:
 *
 * <pre>
 *   /usr/share/zoneinfo/Europe/Berlin   CET-1CEST,M3.5.0,M10.5.0/3
 *   /usr/share/zoneinfo/America/Chicago CST6CDT,M3.2.0,M11.1.0
 *   /usr/share/zoneinfo/Australia/Sydney AEST-10AEDT,M10.1.0,M4.1.0/3
 * </pre>
 *
 * <p>Without it the last offset in the table applies for ever, and a date in 2038 in a zone with
 * daylight saving is an hour out for half the year. {@link OlsonZoneRulesProvider} read the
 * transitions and not this, which is what this class adds.
 *
 * <h2>Why the file is read again rather than the data asked</h2>
 *
 * <p>{@code ZoneInfoData} parses the same file and does not keep the trailing string — it skips to
 * the 64-bit block and stops at the end of it. Exposing it there would mean changing a class shared
 * with Android, for the benefit of a provider Android never registers. So the last 256 bytes of the
 * file are read here, which is where the string is, and the result is cross-checked against the
 * data: <b>the standard offset the string gives must equal the standard offset the table ends
 * with.</b> If the two disagree, this is not the file the rest of the rules came from and nothing is
 * contributed.
 *
 * <h2>What is represented and what is refused</h2>
 *
 * <p>A {@link ZoneOffsetTransitionRule} says "the n-th or last weekday of a month, at a time of
 * day". The POSIX string is not bounded by a day — {@code M3.4.4/50} is the fourth Thursday of March
 * at hour <i>50</i>, and {@code M3.5.0/-1} is an hour <i>before</i> the last Sunday — and both forms
 * are in the files. They are carried over by moving the day rather than by refusing: two days after
 * the fourth Thursday is the first Saturday on or after the 24th, which is the same date in every
 * year and is a rule java.time can hold. {@link #ruleFor} is that arithmetic and is the part of this
 * class worth reading twice. OpenJDK's own tzdb compiler reaches the same rule for Gaza from the
 * same input — {@code SATURDAY on or after MARCH 24 at 02:00 WALL} — which is the best evidence the
 * transformation is right rather than merely plausible.
 *
 * <p>What is <b>refused</b> is a shifted day in February, where the month's length decides the date
 * and a leap year moves it, and the Julian-day forms {@code J59} and {@code 59}, which mean a day
 * number counted with or without 29 February and so fall on different dates in different years. No
 * file on a current macOS or iOS uses either. Where a string is refused nothing is contributed and
 * the behaviour is what it was before — the last offset continues — because a wrong rule is worse
 * than a missing one.
 *
 * <h2>How it was measured</h2>
 *
 * <p>{@code tools/timezone/check.sh}, and against two references. <b>Against each file itself:</b>
 * the rule, applied to a year the file still states transitions for, must land on transitions that
 * file states, to the second and with the same offsets either side — which needs no outside
 * authority, because the explicit half and the trailing half of a file describe the same zone.
 * <b>Against JDK 25,</b> every year from 2038 to 2060.
 *
 * <p>On a macOS 27 host with tzdata 2026c: <b>194 zones got a trailing rule, 192 agree with JDK 25
 * exactly, and none was refused.</b> The remaining two are Moldova, where the file says 03:00 and
 * the JDK says 02:00 — and the file's own transitions up to 2037 say 03:00, so the file is the newer
 * tzdb and the file is what a phone reads. Gaza and Hebron state four changes in a year, because
 * daylight saving is suspended for Ramadan; their explicit transitions run to 2085 and the two-rule
 * tail takes over after that, which is what the JDK does with the same zone.
 */
final class TrailingPosixRule {

    /** Where the files are. The same directory {@code TzDataRoboVM} reads. */
    private static final String ZONE_DIRECTORY = "/usr/share/zoneinfo/";

    /** Ample for the longest string in the files — Chatham's is 45 characters. */
    private static final int TAIL = 256;

    private final String text;
    private int at;

    private TrailingPosixRule(String text) {
        this.text = text;
    }

    /**
     * The trailing rules for one zone, or an empty list when there are none to be had.
     *
     * <p>Empty covers every reason equally on purpose — no file, an old file, a zone without
     * daylight saving, a rule that cannot be represented, a file that does not match the data. The
     * caller's answer is the same in all of them: the transitions it already has, and no rule after
     * them.
     *
     * @param zoneId         the zone, as it names a file under {@value #ZONE_DIRECTORY}
     * @param endsWithStandard the standard offset the transition table ends with, which the string
     *                       must agree with
     */
    static List<ZoneOffsetTransitionRule> forZone(String zoneId, ZoneOffset endsWithStandard) {
        String string = stringIn(zoneId);
        if (string == null) {
            return Collections.emptyList();
        }
        try {
            return new TrailingPosixRule(string).rules(endsWithStandard);
        } catch (RuntimeException cannotBeRepresented) {
            // Deliberately broad: a string this does not understand is a zone without a trailing
            // rule, not a process that should fail. Every refusal below arrives here.
            return Collections.emptyList();
        }
    }

    /**
     * The POSIX string out of the end of the file, or {@code null}.
     *
     * <p>Found by scanning back rather than by walking the header: the string is the last line of
     * the file, between the final newline and the one before it, and the bytes before that opening
     * newline are binary and may hold a {@code 0x0A} of their own — which is why the scan goes
     * backwards from the end and stops at the first one it meets, rather than forwards.
     */
    private static String stringIn(String zoneId) {
        if (zoneId.indexOf("..") >= 0 || zoneId.startsWith("/")) {
            return null;
        }
        try (RandomAccessFile file = new RandomAccessFile(ZONE_DIRECTORY + zoneId, "r")) {
            long length = file.length();
            if (length < 44) {
                return null;
            }

            byte[] magic = new byte[5];
            file.readFully(magic);
            if (magic[0] != 'T' || magic[1] != 'Z' || magic[2] != 'i' || magic[3] != 'f'
                    || magic[4] < '2') {
                // Version 1 has no second block and no trailing string.
                return null;
            }

            int window = (int) Math.min(TAIL, length);
            byte[] tail = new byte[window];
            file.seek(length - window);
            file.readFully(tail);

            if (tail[window - 1] != '\n') {
                return null;
            }
            int opens = -1;
            for (int i = window - 2; i >= 0; i--) {
                if (tail[i] == '\n') {
                    opens = i;
                    break;
                }
            }
            if (opens < 0 || opens == window - 2) {
                // Not found in the window, or an empty string: a zone with no rule after the table.
                return null;
            }
            return new String(tail, opens + 1, window - opens - 2, "US-ASCII");
        } catch (IOException | RuntimeException noFile) {
            return null;
        }
    }

    /**
     * {@code std offset [dst [offset] [,start[/time],end[/time]]]}, parsed.
     *
     * <p>Answered in the order the transitions happen within a year, which is not the order the
     * string gives them in: south of the equator daylight saving starts in October and ends in
     * April, and the string states start before end. {@link ZoneRules} walks the list in order and
     * takes the first rule the instant falls before, so an unsorted pair would answer the southern
     * summer with the winter offset.
     */
    private List<ZoneOffsetTransitionRule> rules(ZoneOffset endsWithStandard) {
        name();
        ZoneOffset standard = offset();
        if (!standard.equals(endsWithStandard)) {
            throw new IllegalArgumentException(
                    "the trailing rule says " + standard + " where the transitions end with "
                            + endsWithStandard + " — this is not that file");
        }

        if (at >= text.length()) {
            return Collections.emptyList();   // no daylight saving: a fixed offset for ever
        }

        name();
        ZoneOffset daylight = at < text.length() && text.charAt(at) != ','
                ? offset()
                // "EST5EDT" names the saving zone without an offset, which POSIX reads as one hour
                // further from UTC than the standard one.
                : ZoneOffset.ofTotalSeconds(standard.getTotalSeconds() + 3600);

        if (at >= text.length()) {
            // A saving name with no dates. POSIX leaves this to the implementation and the files do
            // not use it; nothing can be built from it.
            throw new IllegalArgumentException("a daylight name without dates: " + text);
        }

        expect(',');
        ZoneOffsetTransitionRule starts = ruleFor(standard, standard, daylight);
        expect(',');
        ZoneOffsetTransitionRule ends = ruleFor(standard, daylight, standard);
        if (at != text.length()) {
            throw new IllegalArgumentException("trailing characters in " + text);
        }

        List<ZoneOffsetTransitionRule> both = new ArrayList<>(2);
        both.add(starts);
        both.add(ends);
        if (starts.createTransition(2040).toEpochSecond()
                > ends.createTransition(2040).toEpochSecond()) {
            Collections.reverse(both);
        }
        return both;
    }

    /**
     * One {@code Mm.w.d[/time]}, as the rule java.time holds.
     *
     * <p>The day arithmetic is the part worth reading. A POSIX time of day is not bounded by a day
     * — {@code /50} and {@code /-1} are both in the files — so the time is split into whole days and
     * a remainder, and the days are added to <em>both</em> the day of the month and the day of the
     * week. The two have to move together: the rule java.time holds is "the first Saturday on or
     * after the 24th", and that is the same date as "two days after the fourth Thursday" only if the
     * weekday is shifted by the same two days as the date.
     *
     * <p>The forward form is bounded so that the date it seeks is in the month in every year, leap
     * or not: the window a weekday search covers is seven days, so the shifted day of the month plus
     * six must still fit in the month's shortest length. The backward form — the <em>last</em>
     * weekday of a month — is refused as soon as the shift is not zero, because it counts from the
     * end of the month and the shift cannot be carried through that.
     */
    private ZoneOffsetTransitionRule ruleFor(ZoneOffset standard,
                                             ZoneOffset before,
                                             ZoneOffset after) {
        if (text.charAt(at) != 'M') {
            // Jn and n are a day number counted through the year, which falls on a different date
            // in a leap year. No file on a current macOS or iOS uses one.
            throw new IllegalArgumentException("not a month rule: " + text.substring(at));
        }
        at++;
        int month = number(2);
        expect('.');
        int week = number(1);
        expect('.');
        int day = number(1);
        if (month < 1 || month > 12 || week < 1 || week > 5 || day < 0 || day > 6) {
            throw new IllegalArgumentException("out of range: " + text);
        }

        int seconds = 2 * 3600;
        if (at < text.length() && text.charAt(at) == '/') {
            at++;
            seconds = timeOfDay();
        }
        int days = Math.floorDiv(seconds, 86400);
        int withinTheDay = Math.floorMod(seconds, 86400);

        int dayOfMonth;
        if (week == 5 && days == 0) {
            // The plain backward form, and the only one that works in February too.
            dayOfMonth = -1;
        } else if (week == 5) {
            // The last weekday of a month is the first one on or after the seventh day from the
            // end, which for every month but February is the same date every year.
            int length = Month.of(month).length(false);
            if (Month.of(month) == Month.FEBRUARY) {
                throw new IllegalArgumentException(
                        "the last " + day + " of February shifted by " + days + " days falls on a"
                                + " different date in a leap year");
            }
            dayOfMonth = length - 6 + days;
            if (dayOfMonth < 1 || dayOfMonth > 31) {
                throw new IllegalArgumentException("day " + dayOfMonth + " of month " + month);
            }
        } else {
            dayOfMonth = 1 + (week - 1) * 7 + days;
            int shortest = Month.of(month).minLength();
            if (dayOfMonth < 1 || dayOfMonth + 6 > shortest) {
                throw new IllegalArgumentException(
                        "day " + dayOfMonth + " of month " + month + " plus a weekday search does"
                                + " not fit every year");
            }
        }

        // POSIX counts the days of the week from Sunday; java.time from Monday.
        DayOfWeek weekday = DayOfWeek.of(Math.floorMod(day + days - 1, 7) + 1);

        return ZoneOffsetTransitionRule.of(
                Month.of(month), dayOfMonth, weekday,
                LocalTime.ofSecondOfDay(withinTheDay), false,
                // POSIX states the time on the clock as it reads before the change, which is what
                // WALL means here.
                ZoneOffsetTransitionRule.TimeDefinition.WALL,
                standard, before, after);
    }

    /** A zone abbreviation: {@code CET}, or {@code <+0545>} where it is not letters. */
    private void name() {
        if (at < text.length() && text.charAt(at) == '<') {
            int closes = text.indexOf('>', at);
            if (closes < 0) {
                throw new IllegalArgumentException("unclosed name in " + text);
            }
            at = closes + 1;
            return;
        }
        int from = at;
        while (at < text.length() && Character.isLetter(text.charAt(at))) {
            at++;
        }
        if (at - from < 3) {
            throw new IllegalArgumentException("a name of " + (at - from) + " characters in " + text);
        }
    }

    /**
     * An offset from UTC, with POSIX's sign.
     *
     * <p>Inverted, and this is the one place it matters: the string states what is added to local
     * time to reach UTC, so {@code CET-1} is UTC+1. Reading it the other way puts central Europe in
     * the Atlantic.
     */
    private ZoneOffset offset() {
        return ZoneOffset.ofTotalSeconds(-signedSeconds(24));
    }

    /** A time of day, which POSIX allows to run past midnight or before it. */
    private int timeOfDay() {
        return signedSeconds(167);
    }

    private int signedSeconds(int hourLimit) {
        int sign = 1;
        if (at < text.length() && (text.charAt(at) == '+' || text.charAt(at) == '-')) {
            sign = text.charAt(at) == '-' ? -1 : 1;
            at++;
        }
        int hours = number(3);
        int minutes = 0;
        int secs = 0;
        if (at < text.length() && text.charAt(at) == ':') {
            at++;
            minutes = number(2);
            if (at < text.length() && text.charAt(at) == ':') {
                at++;
                secs = number(2);
            }
        }
        if (hours > hourLimit || minutes > 59 || secs > 59) {
            throw new IllegalArgumentException("out of range: " + text);
        }
        return sign * (hours * 3600 + minutes * 60 + secs);
    }

    /** Up to {@code digits} digits, at least one. */
    private int number(int digits) {
        int from = at;
        int value = 0;
        while (at < text.length() && at - from < digits
                && text.charAt(at) >= '0' && text.charAt(at) <= '9') {
            value = value * 10 + (text.charAt(at) - '0');
            at++;
        }
        if (at == from) {
            throw new IllegalArgumentException("a number was expected at " + at + " in " + text);
        }
        return value;
    }

    private void expect(char character) {
        if (at >= text.length() || text.charAt(at) != character) {
            throw new IllegalArgumentException("'" + character + "' was expected at " + at
                                                       + " in " + text);
        }
        at++;
    }
}
