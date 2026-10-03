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

import com.android.i18n.timezone.ZoneInfoData;
import com.android.i18n.timezone.ZoneInfoDb;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

import libcore.util.BasicLruCache;

/**
 * Time-zone rules read from the Olson files the platform already has.
 *
 * <h2>Why this exists beside {@link IcuZoneRulesProvider}</h2>
 *
 * <p>Because on iOS that one answers with nothing. Measured 03.10.2026 on an iPhone simulator,
 * with a form that asked each source separately:
 *
 * <pre>
 *   TimeZone.getDefault()         Europe/Berlin
 *   TimeZone.getAvailableIDs()    624
 *   ZoneId.getAvailableZoneIds()  0
 *   ZoneId.systemDefault()        ZoneRulesException: No time-zone data files registered
 *   LocalDateTime.now()           ZoneRulesException: No time-zone data files registered
 * </pre>
 *
 * <p>The runtime has two time-zone sources and they are unrelated. {@code java.util.TimeZone} goes
 * through {@link ZoneInfoDb}, which falls back to {@code TzDataRoboVM} and reads
 * {@code /usr/share/zoneinfo} — the standard Olson layout, which macOS and iOS both have, and
 * which is why 624 zones are there. {@code java.time} goes through ICU, and ICU's data is not in
 * an iOS image.
 *
 * <p>So this is not a database to ship. The data is on the device and one of the two front doors
 * could not see it; this is the second door onto the same data.
 *
 * <h2>What is faithful and what is approximate</h2>
 *
 * <p>Every transition in the file becomes a {@link ZoneOffsetTransition}, so historical dates are
 * exact — which matters more than it sounds, because a date in 1978 in Berlin was an hour off from
 * what a naive conversion gives.
 *
 * <p>What is <b>not</b> carried over is the trailing rule: a POSIX {@code TZ} string at the end of
 * an Olson file states how daylight saving continues after the last explicit transition.
 * {@link ZoneInfoData} does not expose it, so the rules here end where the file's transitions end
 * — 2037 in the files shipped with Apple's systems. Beyond that the last offset applies for ever,
 * which means a date in 2038 in a zone with daylight saving can be an hour out.
 *
 * <p>That is stated rather than hidden, and it is the right trade for now: the alternative is to
 * parse the POSIX string ourselves out of a file {@code ZoneInfoData} has already read and thrown
 * away, and until a form is doing arithmetic on dates past 2037 it buys nothing. A business form
 * computing a delivery date is not that form.
 */
final class OlsonZoneRulesProvider extends ZoneRulesProvider {

    /**
     * Rules are built on demand and kept.
     *
     * <p>Building one means walking a few hundred transitions, and a form asking the time repeatedly
     * asks for the same zone every time. Eight is what {@link IcuZoneRulesProvider} keeps and there
     * is no reason to differ: an application uses one zone, two if it shows a customer's.
     */
    private final BasicLruCache<String, ZoneRules> cache = new RulesCache(8);

    /** Whether there is anything to provide — asked before this provider is registered at all. */
    static boolean hasData() {
        try {
            String[] ids = ZoneInfoDb.getInstance().getAvailableIDs();
            return ids != null && ids.length > 0;
        } catch (Throwable noDatabase) {
            return false;
        }
    }

    @Override
    protected Set<String> provideZoneIds() {
        try {
            Set<String> ids = new HashSet<>(Arrays.asList(ZoneInfoDb.getInstance()
                    .getAvailableIDs()));
            // java.time reads an id beginning with "GMT" as the pattern "GMT+HH:mm:ss", and these
            // do not fit it. They mean GMT, which java.time has of its own. Same removal as in
            // IcuZoneRulesProvider, and for the same reason.
            ids.remove("GMT+0");
            ids.remove("GMT-0");
            return ids;
        } catch (Throwable noDatabase) {
            return Collections.emptySet();
        }
    }

    @Override
    protected ZoneRules provideRules(String zoneId, boolean forCaching) {
        // forCaching is ignored: the data is static for the life of the process, so there is no
        // such thing as a stale answer here.
        return cache.get(zoneId);
    }

    @Override
    protected NavigableMap<String, ZoneRules> provideVersions(String zoneId) {
        return new TreeMap<>(Collections.singletonMap(
                ZoneInfoDb.getInstance().getVersion(), getRules(zoneId, false)));
    }

    /**
     * One zone's rules, built out of its transition table.
     *
     * <p>The table is read through {@code getOffsetsByUtcTime}, which separates the standard offset
     * from the daylight part — and that separation is what {@link ZoneRules} wants: two lists, one
     * for when the <em>standard</em> offset moved (a country changing zone) and one for when the
     * <em>wall</em> offset moved (that, and every spring and autumn besides).
     */
    private static ZoneRules rulesFor(String zoneId) {
        ZoneInfoData data = ZoneInfoDb.getInstance().makeZoneInfoData(zoneId);
        if (data == null) {
            throw new ZoneRulesException("Unknown time-zone ID: " + zoneId);
        }

        long[] transitions = data.getTransitions();
        int[] parts = new int[2];

        // Before the first transition. getOffsetsByUtcTime answers for a time before the table
        // with the earliest raw offset, which is exactly what the base offsets are.
        long beforeEverything = transitions == null || transitions.length == 0
                ? 0L : (transitions[0] - 1) * 1000L;
        data.getOffsetsByUtcTime(beforeEverything, parts);
        ZoneOffset baseStandard = offsetOf(parts[0]);
        ZoneOffset baseWall = offsetOf(parts[0] + parts[1]);

        if (transitions == null || transitions.length == 0) {
            return ZoneRules.of(baseStandard, baseWall,
                                Collections.<ZoneOffsetTransition>emptyList(),
                                Collections.<ZoneOffsetTransition>emptyList(),
                                Collections.<ZoneOffsetTransitionRule>emptyList());
        }

        List<ZoneOffsetTransition> standardMoves = new ArrayList<>();
        List<ZoneOffsetTransition> wallMoves = new ArrayList<>();

        ZoneOffset previousStandard = baseStandard;
        ZoneOffset previousWall = baseWall;

        for (long second : transitions) {
            data.getOffsetsByUtcTime(second * 1000L, parts);
            ZoneOffset standard = offsetOf(parts[0]);
            ZoneOffset wall = offsetOf(parts[0] + parts[1]);

            // A transition that changes nothing is in the file — the tables carry entries that only
            // rename an abbreviation — and ZoneOffsetTransition refuses one, so it is skipped here
            // rather than caught there.
            if (!wall.equals(previousWall)) {
                wallMoves.add(ZoneOffsetTransition.of(
                        LocalDateTime.ofEpochSecond(second, 0, previousWall), previousWall, wall));
            }
            if (!standard.equals(previousStandard)) {
                standardMoves.add(ZoneOffsetTransition.of(
                        LocalDateTime.ofEpochSecond(second, 0, previousStandard),
                        previousStandard, standard));
            }
            previousStandard = standard;
            previousWall = wall;
        }

        // No trailing rules — see the note on this class. The last offset applies from the last
        // transition onwards.
        return ZoneRules.of(baseStandard, baseWall, standardMoves, wallMoves,
                            Collections.<ZoneOffsetTransitionRule>emptyList());
    }

    /**
     * Milliseconds to a {@link ZoneOffset}.
     *
     * <p>Rounded to the second, because that is the unit {@code ZoneOffset} has and the unit the
     * Olson files are written in; the milliseconds come only from {@code ZoneInfoData} keeping
     * everything in the unit {@code java.util.TimeZone} uses.
     */
    private static ZoneOffset offsetOf(int millis) {
        return ZoneOffset.ofTotalSeconds(millis / 1000);
    }

    private static final class RulesCache extends BasicLruCache<String, ZoneRules> {
        RulesCache(int size) {
            super(size);
        }

        @Override
        protected ZoneRules create(String zoneId) {
            return rulesFor(zoneId);
        }
    }
}
