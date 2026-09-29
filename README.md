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
| Android, AOT | a Java file compiles ahead of time and runs under `adb shell`; as a shared library it loads in ART and starts our VM inside an ART process. The stub and the screen are next |
| Class library | Java 25 in progress: `Math`, `StrictMath`, all of JEP 431, the atomic arrays' memory-order family and the Future/CompletionStage additions done; 1246 members and 425 classes of `java.base` still absent |
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

Android is never built by default — it needs an NDK, and a machine without one should not have its
build fail over a target nobody asked for. Name it and it is built; the NDK is found automatically:

```
compiler/vm/build.sh --target=android-arm64 --build=release
```

`--android-api` defaults to 26, which is MIN_SDK for this product. Compiling at the floor is how a
symbol that is too new becomes a build error here instead of a crash on a customer's phone.

A program for Android is then a shared library rather than an executable — the file an APK carries
in `lib/arm64-v8a/`:

```
bin/robovm -target androidlib -os android -arch arm64 -cp <classes> -d <out> -o Hello Hello
```

## Checking it

Four tools, and they measure different things:

* **`compiler/rt/tools/java25/gap.py`** — what the class library is still missing against Java 25,
  as a table. It refuses to report on a jar that holds duplicated classes, because every number
  derived from one is a guess about zip ordering.
* **`compiler/rt/tools/java25/check.sh`** — the added members against the host JDK's own. Catches a
  wrong body; proves nothing about whether an addition fits onto this library.
* **`compiler/rt/tools/java25/native-check.sh`** — the same checks compiled against `robovm-rt` and
  run as native binaries. This is the one that answers the real question. 394 assertions.
* **`compiler/vm/tools/android-load-test.sh`** — loads an `-target androidlib` library on a device
  three ways: by `dlopen` from a native process, which shows the VM inside it starts; by
  `System.load` from ART, the only way to find out whether ART accepts what `JNI_OnLoad` returns;
  and from inside an ART process through a bridge, which is the only one that puts two virtual
  machines in one process. No APK — `app_process` runs a main class straight from a dex.

## Releasing

```
dist/release-tsb.sh <version>
```

It sweeps the tree for the copies iCloud writes into `target/`, refuses to publish an archive that
still holds any, and refuses again if `robovm-rt.jar` does — which it earned on its first run.
