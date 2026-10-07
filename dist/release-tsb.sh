#!/bin/bash
# Publishes tsbTheMachine as a GitHub release — the build RapidFX and RapidJ pin to.
#
#   dist/release-tsb.sh 27.1.0
#
# What comes out:
#   tag      tsbthemachine-<version>              on the current HEAD (must be pushed)
#   asset    tsbthemachine-dist-<version>.tar.gz  unpacks to tsbthemachine-<version>/
#   notes    the SHA-256 of the asset, to be written into TheMachine.java of both projects
#
# Why a release and not Sonatype: this is GPL2, and whoever hosts the binary owes the corresponding
# source. A release tagged on the commit it was built from is that correspondence, in one place and
# at no cost. See NOTICE.
#
# Tags before tsbthemachine-27.1.0 were called tsbmobile-* and live in thorstenstueker/robovm,
# which is archived and stays readable for exactly that reason.
#
# Prerequisites: JDK 21 as JAVA_HOME, the VM binaries under compiler/vm/target/binaries
# (see README "Building"), a clean working tree, gh logged in.
set -euo pipefail

VERSION=${1:?version, e.g. 3.0.0-tsb.20260922}
cd "$(dirname "$0")/.."

# The tree must correspond to a commit — except for what the build itself regenerates.
#
# compiler/swift/src/main/robopods holds the xcframework build-natives.sh produces. It is tracked
# on purpose, so that a clone without a Swift toolchain can still build, and it is therefore
# rewritten by every build. Two measurements from 27.09.2026, after ZERO_AR_DATE=1 and after
# sorting the xcframework's Info.plist: the archives still differ by **16 bytes** between two runs
# over identical source — a hash inside the Swift object, not a timestamp, and not worth chasing
# further.
#
# Excluding it does not weaken what this check is for. The correspondence GPL2 asks for is between
# the released binary and the *source* it was built from, and that source — src/main/swift/*.swift
# — is tracked and is covered here. A regenerated artefact differing in a hash is the same
# artefact. Anything else being dirty still stops the release.
DIRTY=$(git status --porcelain -- . ':(exclude)compiler/swift/src/main/robopods')
if [ -n "$DIRTY" ]; then
    echo "The working tree is not clean — the release must correspond to a commit." >&2
    echo "$DIRTY" >&2
    exit 1
fi
COMMIT=$(git rev-parse HEAD)
if ! git branch -r --contains "$COMMIT" | grep -q origin/; then
    echo "HEAD ($COMMIT) is not on origin — push first." >&2
    exit 1
fi

NAME="tsbthemachine-$VERSION"
TAG="tsbthemachine-$VERSION"
ASSET="tsbthemachine-dist-$VERSION.tar.gz"

# iCloud copies, out of the way before anything is packaged.
#
# This repository sits under ~/Documents, which is mirrored, and the file service resolves what it
# thinks are conflicts by writing "Foo 2.class" beside "Foo.class" — including inside target/, where
# nobody looks. On 28.09.2026 the tsbmobile-27.0.0 release went out with **502 of them inside
# robovm-rt.jar**:
# stale duplicates of real classes, carrying an older API. They declare the same internal class name
# as the originals, so anything keyed by class name rather than by path reads whichever comes last
# in the zip. ApiDelta did exactly that and reported 59 methods missing from java.util.Arrays that
# have been there all along.
#
# Deleted rather than merely reported: a build that is one `rm` away from correct should take it.
DUPES=$(find . -name "* [0-9].*" -not -path "./.git/*" | wc -l | tr -d ' ')
if [ "$DUPES" != "0" ]; then
    echo "removing $DUPES iCloud duplicates before packaging"
    find . -name "* [0-9].*" -not -path "./.git/*" -delete
fi

# The distribution ships the release libraries only, and the VM build does not know that.
#
# compiler/vm/build.sh defaults to BUILDS="debug release" and installs both into
# compiler/vm/target/binaries, the debug ones suffixed -dbg.a. The assembly then takes **/* and
# would pack fourteen archives per architecture where the published tsbmobile-27.0.2 has seven.
#
# That release was right by accident: whoever built it had a release-only tree. Checked against the
# published artefact on 28.09.2026 — no -dbg.a anywhere in it. So the decision is written down here
# instead of depending on the state somebody's build directory happens to be in.
#
# Removed rather than excluded in the assembly, because the assembly is upstream's and the next
# import would lose the change.
DBG=$(find compiler/vm/target/binaries -name "*-dbg.a" 2>/dev/null | wc -l | tr -d ' ')
if [ "$DBG" != "0" ]; then
    echo "removing $DBG debug libraries — the distribution ships release only"
    find compiler/vm/target/binaries -name "*-dbg.a" -delete
fi

# The runtime, built and installed before it is packaged.
#
# dist/package depends on robovm-rt as a Maven artefact, so it takes whatever is in ~/.m2 — and
# that is whatever somebody last installed, not what this tree says. On 03.10.2026 that published a
# 27.4.0 whose robovm-rt.jar was two days old and did not contain the fix the release existed for.
# Nothing reported it: the archive was well-formed, the checksum was correct, and the tag pointed at
# the right commit. It was found by unzipping the published asset and looking.
#
# That is the one thing a release of this kind must not get wrong. The tag is the GPL2
# correspondence between source and binary; a binary built from somebody's stale ~/.m2 breaks it
# quietly, which is the only way it can break.
echo "== building the runtime, so that the archive matches this tree =="
mvn -q -pl compiler/rt -am install -DskipTests -Dmaven.javadoc.skip=true

echo "== packaging $NAME =="
mvn -q -pl dist/package clean package -DskipTests -Ddist.name="$NAME"
cp "dist/package/target/$NAME.tar.gz" "dist/package/target/$ASSET"

# And the same question asked of the result, because the copies can reappear during the build.
INSIDE=$(tar tzf "dist/package/target/$ASSET" | grep -c " [0-9]\." || true)
if [ "$INSIDE" != "0" ]; then
    echo "The archive holds $INSIDE duplicated paths — refusing to publish it." >&2
    exit 1
fi
# The runtime jar again from inside, because that is where it actually did the damage: a duplicated
# class sits at a path the archive listing above cannot see into.
UNPACKED=$(mktemp -d)
tar xzf "dist/package/target/$ASSET" -C "$UNPACKED" "$NAME/lib/robovm-rt.jar"
INSIDE=$(unzip -l "$UNPACKED/$NAME/lib/robovm-rt.jar" | grep -c " [0-9]\.class" || true)
if [ "$INSIDE" != "0" ]; then
    rm -rf "$UNPACKED"
    echo "robovm-rt.jar holds $INSIDE duplicated classes — refusing to publish it." >&2
    exit 1
fi

# And that it is the jar this tree just built, byte for byte.
#
# The install above should make this impossible to fail, which is exactly why it is checked: the
# failure it guards against is silent and was real. Comparing the bytes needs no knowledge of what
# changed, which is the property wanted — a check that looked for a particular class would pass the
# next time somebody's ~/.m2 was stale for a different reason.
BUILT=$(ls compiler/rt/target/robovm-rt-*.jar 2>/dev/null | grep -v -- "-sources\|-javadoc" | head -1)
if [ -z "$BUILT" ] || ! cmp -s "$BUILT" "$UNPACKED/$NAME/lib/robovm-rt.jar"; then
    rm -rf "$UNPACKED"
    echo "The archive's robovm-rt.jar is not the one this tree built." >&2
    echo "dist/package takes it from ~/.m2, so it is somebody else's build." >&2
    exit 1
fi
rm -rf "$UNPACKED"
# Both digests, and SHA-256 is the one that is pinned.
#
# RapidFX's TheMachine.DIST_SHA256 went to SHA-256 on 06.10.2026 and this script still reported
# only SHA-1, which left whoever published a release to work the real number out afterwards — the
# step at which a pin gets copied from the wrong place. SHA-1 stays in the notes because releases
# in circulation are named by it, and because a second statement about one file costs nothing.
SHA1=$(shasum -a 1 "dist/package/target/$ASSET" | cut -d' ' -f1)
SHA256=$(shasum -a 256 "dist/package/target/$ASSET" | cut -d' ' -f1)
SIZE=$(du -h "dist/package/target/$ASSET" | cut -f1)

echo "== release $TAG on $COMMIT =="
gh release create "$TAG" "dist/package/target/$ASSET" \
    --repo thorstenstueker/tsbthemachine \
    --target "$COMMIT" \
    --title "tsbTheMachine $VERSION" \
    --notes "$(cat <<EOF
The mobile compiler and runtime for RapidFX and RapidJ: class files up to version 61, a runtime library being filled in against Java 25, CocoaTouch reduced to what a rendered interface needs, and Swing on UIView.

Android compiles ahead of time, not just the VM core. A Java file becomes an arm64 binary that runs under \`adb shell\`, or — with \`-target androidlib\` — the \`lib<name>.so\` an APK carries in \`lib/arm64-v8a/\`: it loads in ART, and our VM starts inside the ART process.

**An \`androidlib\` library is a third of the size it was.** It used to export 293,029 dynamic symbols in order to use four: the linker was asked to make the wanted ones global and was never told to make the rest local, which costs nothing for an executable and nearly everything for a shared library. With a version script and a release link that strips, a hello-world library went from 120 MB to 39 — and stack traces are unaffected, because the VM reads its own tables rather than the symbol table.

A fork of RoboVM/MobiVM for our own purposes — see NOTICE for the origin and the licence.

    asset   $ASSET ($SIZE)
    sha256  $SHA256
    sha1    $SHA1
    commit  $COMMIT

Unpacks to \`$NAME/\`. Pinned in \`TheMachine.java\` of both projects; the checksum there must match this one.
EOF
)"

echo
echo "Now write into TheMachine.java (RapidFX and RapidJ):"
echo "    MACHINE_VERSION = \"$VERSION\""
echo "    DIST_SHA256     = \"$SHA256\""
echo
echo "Hold it against the server's own word before committing the pin:"
echo "    gh api repos/thorstenstueker/tsbthemachine/releases/tags/$TAG --jq '.assets[].digest'"
echo
echo "The two have to agree. A digest read from the API alone is the server's word about the"
echo "server's file; this one was measured here, on the archive this tree just built."
