#!/bin/bash
#
# Rebuilds the filtered ICU data file this runtime links in.
#
#   compiler/rt/tools/icu-data.sh
#
# What comes out:
#   compiler/rt/android/external/icu/icu4c/source/data/icudt68l.dat
#
# Why there is a file to build at all
# -----------------------------------
# Until 03.10.2026 the build linked ICU's stubdata.cpp — a header with no data behind it — and
# nobody had noticed, because the character properties are compiled into ICU's code and those are
# what Character.isLetter and friends use. Everything locale-sensitive fell back to root. Measured
# on an Android emulator and an iPhone simulator, through the same RapidFX form:
#
#     NumberFormat.getInstance(Locale.GERMAN).format(1234.5)  ->  1,234.5   (JDK 25: 1.234,5)
#     DateFormat.getDateInstance(MEDIUM, GERMANY)             ->  2026 M09 21
#     Locale.GERMAN.getDisplayLanguage(GERMAN)                ->  de        (JDK 25: Deutsch)
#     Locale.getAvailableLocales().length                     ->  1         (JDK 25: 1158)
#
# What is in it, and what was left out
# ------------------------------------
# Measured rather than guessed — the three variants, from the same source file:
#
#     everything, 1150 locales                                 27.2 MB
#     the 28 languages below                                   15.6 MB
#     the same, without transliterations and the converters      9.8 MB   <- this one
#
# The languages are the European Union's plus Norwegian, which is the market this product is sold
# into. Adding one is a line below and about 200 KB.
#
# Two things are deliberately absent:
#
#   * **Transliterations** (translit/, 712 KB). Writing Greek in Latin letters is not something a
#     business form does. The largest single entry in the whole file is translit/root.res.
#   * **The character-set converters** (*.cnv, 5.1 MB). These are the old encodings — EUC-TW,
#     Shift-JIS, the IBM code pages. UTF-8, UTF-16 and ISO-8859-1 are in ICU's code rather than in
#     the data, so they are unaffected; a form reading a Windows-1252 CSV is the case that would
#     notice, and it has java.nio.charset for that, which is not ICU.
#
# The break-iterator dictionaries (brkitr/*.dict, 3.1 MB with cjdict alone at 1.9) ARE kept, even
# though no European language needs them: brkitr/root.res declares a dependency on cjdict.dict, and
# icupkg refuses to write a file whose dependencies are broken. Removing them means editing root,
# which is a bigger intervention than 1.9 MB is worth.
#
# Prerequisites: gh (for the download), and icupkg from any ICU 70+ — the package format has not
# changed and 77's tool reads 68's file.
set -euo pipefail

VERSION=68.2
MAJOR=68
HERE=$(cd "$(dirname "$0")" && pwd)
TARGET="$HERE/../android/external/icu/icu4c/source/data/icudt${MAJOR}l.dat"

# The languages. One per line so that adding one is a one-line diff with a reason beside it.
LANGUAGES="root en de fr it es nl pl pt cs da sv fi nb tr el hu ro bg hr sk sl et lv lt ga mt"

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

echo "== fetching ICU $VERSION data =="
gh release download "release-${VERSION//./-}" --repo unicode-org/icu \
   --pattern "icu4c-${VERSION}-data-bin-l.zip" --dir "$WORK" --clobber
unzip -q -o "$WORK/icu4c-${VERSION}-data-bin-l.zip" "icudt${MAJOR}l.dat" -d "$WORK"

ICUPKG=$(ls /opt/homebrew/Cellar/icu4c@*/*/sbin/icupkg 2>/dev/null | head -1 || true)
if [ -z "$ICUPKG" ]; then
    ICUPKG=$(command -v icupkg || true)
fi
if [ -z "$ICUPKG" ]; then
    echo "No icupkg. brew install icu4c" >&2
    exit 1
fi

echo "== listing =="
"$ICUPKG" -l "$WORK/icudt${MAJOR}l.dat" > "$WORK/all.txt"
echo "   $(wc -l < "$WORK/all.txt") entries, $(du -h "$WORK/icudt${MAJOR}l.dat" | cut -f1)"

echo "== choosing =="
LANGUAGES="$LANGUAGES" python3 - "$WORK/all.txt" "$WORK/remove.txt" <<'PY'
import os, pathlib, sys

languages = set(os.environ["LANGUAGES"].split())

# Not languages: shared tables and indexes. Without them the file is broken rather than small —
# pool.res is a shared string pool every .res in its directory refers into.
#
# langInfo was added 03.10.2026: it is a table and not a locale, and removing it left ICU4J throwing
# MissingResourceException out of UPropertyAliases. Found by the MANIFEST check below, which is the
# whole reason that check exists.
infrastructure = {"pool", "res_index", "supplementalData", "metaZones", "zoneinfo64",
                  "windowsZones", "keyTypeData", "likelySubtags", "metadata",
                  "numberingSystems", "plurals", "genderList", "pluralRanges",
                  "timezoneTypes", "icuver", "icustd", "sprepdata", "langInfo"}

entries = pathlib.Path(sys.argv[1]).read_text().split()
remove = []
for entry in entries:
    name = entry.rsplit("/", 1)[-1]
    folder = entry.rsplit("/", 1)[0] if "/" in entry else ""

    if folder == "translit" or name.endswith(".cnv"):
        remove.append(entry)
        continue
    if not name.endswith(".res"):
        continue

    base = name[:-4]
    # An upper-case first letter is a converter alias table or similar, never a locale.
    if base in infrastructure or base[0].isupper():
        continue
    # zone/tzdbNames.res is the one file under zone/ that is not a locale, and it was removed by
    # the rule below until 03.10.2026 because "tzdbNames" is not a language either.
    if entry == "zone/tzdbNames.res":
        continue
    if base.split("_")[0] not in languages:
        remove.append(entry)

pathlib.Path(sys.argv[2]).write_text("\n".join(remove) + "\n")
print(f"   {len(remove)} of {len(entries)} entries removed")
PY

echo "== filtering =="
mkdir -p "$WORK/out"
cp "$WORK/icudt${MAJOR}l.dat" "$WORK/out/"
# icupkg derives the expected package name from the file name, so the output has to keep it —
# hence the subdirectory rather than a second name.
"$ICUPKG" -r "$WORK/remove.txt" "$WORK/out/icudt${MAJOR}l.dat"

# icupkg reports a broken dependency on stderr and still writes the file, so the size is the check
# that something happened at all.
BEFORE=$(stat -f%z "$WORK/icudt${MAJOR}l.dat" 2>/dev/null || stat -c%s "$WORK/icudt${MAJOR}l.dat")
AFTER=$(stat -f%z "$WORK/out/icudt${MAJOR}l.dat" 2>/dev/null || stat -c%s "$WORK/out/icudt${MAJOR}l.dat")
if [ "$AFTER" -ge "$BEFORE" ]; then
    echo "The filtered file is no smaller than the original — icupkg refused it." >&2
    echo "Its complaint is above; a dependency on a removed entry is the usual reason." >&2
    exit 1
fi

# The five entries ICU's published data archive does not carry
# ---------------------------------------------------------------
# pnames.icu, uprops.icu, ucase.icu, ubidi.icu and nfc.nrm are the character-property tables. ICU4C
# compiles them into its own code, so the release archive leaves them out — measured 03.10.2026:
# zero of the five appear in icu4c-68.2-data-bin-l.zip's 3823 entries.
#
# ICU4J has no C code and reads them as package entries. Without pnames.icu, android.icu's
# UPropertyAliases throws MissingResourceException on its first use, which arrives through
# NumberFormat.format by way of CurrencySpacingEnabledModifier — a stack with nothing about
# character properties in it.
#
# They are therefore checked in, at 269 KB for all five, because there is nowhere to fetch them
# from. They came out of the minimal builtin package that shipped before this file existed.
echo "== adding the five the archive does not have =="
BUILTIN="$HERE/icu-builtin"
# The list has to be a real file whose name ends in .txt: anything else is taken as a single item
# name and joined to -s, which is how a process substitution ends up as "icu-builtin//dev/fd/63".
(cd "$BUILTIN" && ls *.icu *.nrm) > "$WORK/add.txt"
"$ICUPKG" -a "$WORK/add.txt" -s "$BUILTIN" "$WORK/out/icudt${MAJOR}l.dat"

# Nothing the old package had may go missing
# ------------------------------------------
# icu-builtin/MANIFEST is the entry list of the minimal package this file replaced. Every one of its
# 47 entries has to be in the result, because that package demonstrably worked: a smaller set is a
# regression however much larger the file around it has become.
echo "== checking against the old package =="
"$ICUPKG" -l "$WORK/out/icudt${MAJOR}l.dat" | sort > "$WORK/result.txt"
MISSING=$(comm -23 "$BUILTIN/MANIFEST" "$WORK/result.txt")
if [ -n "$MISSING" ]; then
    echo "These entries were in the old minimal package and are not in the new file:" >&2
    echo "$MISSING" | sed 's/^/   /' >&2
    echo >&2
    echo "Either add them to 'infrastructure' in the filter above, or to icu-builtin/." >&2
    exit 1
fi
echo "   all $(wc -l < "$BUILTIN/MANIFEST" | tr -d ' ') entries of the old package are present"

mkdir -p "$(dirname "$TARGET")"
cp "$WORK/out/icudt${MAJOR}l.dat" "$TARGET"
AFTER=$(stat -f%z "$TARGET" 2>/dev/null || stat -c%s "$TARGET")
echo "== done =="
printf "   %s -> %s\n" "$(numfmt --to=iec "$BEFORE" 2>/dev/null || echo "$BEFORE")" \
                       "$(numfmt --to=iec "$AFTER" 2>/dev/null || echo "$AFTER")"
echo "   $TARGET"
echo
echo "Rebuild the native runtime for it to take effect:"
echo "   compiler/vm/build.sh --target=macosx-arm64 --build=release"
