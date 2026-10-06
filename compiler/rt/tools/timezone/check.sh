#!/bin/bash
#
# Holds the trailing POSIX rule against the files it is read out of.
#
# A compiled time-zone file states its transitions explicitly up to a cut-off — 2037 in the files
# Apple's systems ship — and then states the rule in one line of POSIX text. java.time reads the
# transitions through OlsonZoneRulesProvider and, since 06.10.2026, that line through
# TrailingPosixRule. Without it a date in 2038 in a zone with daylight saving is an hour out for
# half the year.
#
# The two halves of a file describe the same thing on either side of the cut-off, which is what
# makes this checkable without an outside reference: the rule, applied to a year the file still has
# transitions for, must reproduce those transitions to the second. Every zone on the host is held to
# that. The host JDK's own rules are compared as well, and where they differ the file is reported as
# the newer tzdb — which it usually is, and which is the situation on a phone.
#
# The class cannot be tested where it lives: java.time.zone is in java.base and a class loader will
# always answer with the platform's copy rather than ours. So the source is transplanted into a
# `probe` package first and compiled as ordinary classpath code — the same arrangement as
# tools/java25/check.sh, for the same reason.
#
# Usage: compiler/rt/tools/timezone/check.sh [/path/to/jdk]
set -e

HERE=$(cd "$(dirname "$0")" && pwd)
RT="$HERE/../../android/libcore/ojluni/src/main/java"

JDK="${1:-}"
if [ -z "$JDK" ]; then
    JDK=$(/usr/libexec/java_home -v 25 2>/dev/null \
        || ls -d "$HOME"/Library/Java/JavaVirtualMachines/*25*/Contents/Home 2>/dev/null | head -1)
fi
if [ ! -x "$JDK/bin/javac" ]; then
    echo "No JDK found. Pass one: check.sh /path/to/jdk" >&2
    exit 1
fi
echo "== against $("$JDK/bin/java" -version 2>&1 | head -1) =="
if [ -r /usr/share/zoneinfo/+VERSION ]; then
    echo "== the host's zone files are tzdata $(cat /usr/share/zoneinfo/+VERSION) =="
fi

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/probe"

python3 - "$RT" "$WORK" <<'PY'
import pathlib
import sys

rt, work = (pathlib.Path(a) for a in sys.argv[1:3])

# The class transplants whole: it reaches nothing outside java.time and java.io, which is what let
# it be written as a separate class in the first place.
text = (rt / 'java/time/zone/TrailingPosixRule.java').read_text(errors='replace')
text = text.replace('package java.time.zone;',
                    'package probe;\n\n'
                    'import java.time.zone.ZoneOffsetTransitionRule;')
text = text.replace('final class TrailingPosixRule', 'public final class TrailingPosixRule')
text = text.replace('    static List<ZoneOffsetTransitionRule> forZone',
                    '    public static List<ZoneOffsetTransitionRule> forZone')
# {@link OlsonZoneRulesProvider} is not transplanted and javadoc would not resolve it.
text = text.replace('{@link OlsonZoneRulesProvider}', 'OlsonZoneRulesProvider')
(work / 'probe/TrailingPosixRule.java').write_text(text)
PY

cp "$HERE"/TrailingRuleCheck.java "$WORK/probe/"
"$JDK/bin/javac" -nowarn -d "$WORK/out" "$WORK"/probe/*.java

"$JDK/bin/java" -cp "$WORK/out" probe.TrailingRuleCheck
