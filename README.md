# tsbTheMachine

The ahead-of-time compiler and runtime for the mobile half of **RapidFX** and **RapidJ**. A fork of
[RoboVM / MobiVM](https://github.com/MobiVM/robovm), maintained for our own purposes — see
[`NOTICE`](NOTICE) for the origin, the licence and why it is a fork rather than a contribution.

MobiVM's own README is kept as [`README.MobiVM.md`](README.MobiVM.md).

## What it is for

One program, compiled ahead of time, on an iPhone and on an Android phone, drawing the same
rendered user interface as the desktop and the web build. Not native widgets — that is the problem
this is meant to remove.

## Where it stands

| | |
|---|---|
| iOS, arm64 and simulator | ships; RapidFX builds and launches on it |
| Android, AOT | VM core runs on Bionic, **24/24** on a device. The entry point is next |
| Class library | Java 25 in progress: `Math`, `StrictMath` and all of JEP 431 done; 1307 members and 425 classes of `java.base` still absent |
| Class files accepted | up to version 61 |

The library's own class files are version 52 — it is compiled `-source 8` because it *is* the boot
class path — while its API is filled in against Java 25. Those are separate things, and only the
second is what a customer writes against.

## Building

```
mvn -pl compiler/rt install -DskipTests            # the runtime library
mvn -pl dist/package clean package -DskipTests \
    -Ddist.name=tsbthemachine-test                 # a distribution to test with
```

The VM core for Android needs no arguments beyond the toolchain file; it finds the NDK itself:

```
cmake -DCMAKE_TOOLCHAIN_FILE=compiler/vm/bionic.toolchain.cmake \
      -DOS=android -DARCH=arm64 -DANDROID_API=26 compiler/vm
```

## Checking it

Three tools, and they measure different things:

* **`compiler/rt/tools/java25/gap.py`** — what the class library is still missing against Java 25,
  as a table. It refuses to report on a jar that holds duplicated classes, because every number
  derived from one is a guess about zip ordering.
* **`compiler/rt/tools/java25/check.sh`** — the added members against the host JDK's own. Catches a
  wrong body; proves nothing about whether an addition fits onto this library.
* **`compiler/rt/tools/java25/native-check.sh`** — the same checks compiled against `robovm-rt` and
  run as native binaries. This is the one that answers the real question. 279 assertions.

## Releasing

```
dist/release-tsb.sh <version>
```

It sweeps the tree for the copies iCloud writes into `target/`, refuses to publish an archive that
still holds any, and refuses again if `robovm-rt.jar` does — which it earned on its first run.
