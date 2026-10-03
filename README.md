# tsbTheMachine

The ahead-of-time compiler and runtime for the mobile half of **RapidFX** and **RapidJ**. A fork of
[RoboVM / MobiVM](https://github.com/MobiVM/robovm), maintained for our own purposes — see
[`NOTICE`](NOTICE) for the origin and for why it is a fork rather than a contribution.

**Licence: GPL version 2** — the full text is in [`LICENSE`](LICENSE). The parts are licensed
separately and it matters which: see [Licence](#licence) below, and in particular what it means for
a program you compile with this, which is **not** GPL.

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

## Licence

**The repository as a whole is GPL version 2** ([`LICENSE`](LICENSE)), because the compiler is, and
the compiler is the part that gives the whole its name. But the parts carry their own terms, and
the division is deliberate rather than historical:

| | licence | goes into your program |
|---|---|---|
| `compiler/compiler` — the ahead-of-time compiler | GPL 2 ([`LICENSE.txt`](compiler/compiler/LICENSE.txt)) | **no** — it is run, not linked |
| `compiler/vm` — the virtual machine | Apache 2.0 ([`LICENSE.txt`](compiler/vm/LICENSE.txt)) | yes |
| `compiler/rt` — the class library | Apache 2.0 ([`LICENSE.txt`](compiler/rt/LICENSE.txt)); the parts descended from OpenJDK and Android's libcore are GPL 2 **with the Classpath exception** ([`LICENSE_GPL_CE.txt`](compiler/rt/LICENSE_GPL_CE.txt)) | yes |
| `compiler/cocoatouch`, `compiler/llvm` and the other bundled libraries | their own, each with a `LICENSE.txt` beside it | some |

### What this means for a program you compile

**A program compiled with tsbTheMachine does not become GPL.** That is the point of the division
above and it is worth stating plainly, because "the compiler is GPL 2" reads alarming until you see
which part is which.

* The **compiler** is GPL 2 and is *executed*, not linked into anything. Running a GPL program over
  your source has never put your source under the GPL — it is the same arrangement GCC has had since
  1987.
* What is actually linked into your finished application is the **virtual machine** and the **class
  library**. The VM is Apache 2.0. The library is Apache 2.0 except for what descends from OpenJDK,
  which is GPL 2 *with the Classpath exception* — and that exception exists for exactly this: it
  gives permission "to link this library with independent modules to produce an executable,
  regardless of the license terms of these independent modules, and to copy and distribute the
  resulting executable under terms of your choice."

So: your application is yours, under whatever terms you choose, sold or not.

### What you do still owe

Two things, and neither is onerous:

* **Keep the notices.** The Apache 2.0 parts require their `NOTICE` to travel with a distribution.
  RapidFX writes a `THIRD-PARTY-NOTICES.txt` into every application it builds, which is what that is
  for; if you build with this compiler some other way, carry them yourself.
* **If you ship a modified tsbTheMachine, ship its source.** That is the GPL on the compiler, and on
  the GPL-with-exception parts of the runtime. Using it unmodified, as a tool, obliges nothing.

### Corresponding source

Every released binary is built from a commit in this repository and the release tag names that
commit. Take the tag, take the commit, and you have the source for the archive published beside it.
Releases made before this repository existed — `tsbmobile-27.0.0`, `-27.0.1`, `-27.0.2` — live in
[`thorstenstueker/robovm`](https://github.com/thorstenstueker/robovm), which stays archived and
readable for that reason.

### Not legal advice

This section describes the licences as the files in this repository state them. It is a reading, not
a lawyer's opinion. If what you are planning turns on it, read [`LICENSE`](LICENSE),
[`compiler/rt/LICENSE_GPL_CE.txt`](compiler/rt/LICENSE_GPL_CE.txt) and the per-module files
yourself — they are the terms, and this is only a map of them.
