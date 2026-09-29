#!/bin/bash
#
# Checks that an Android shared library built by `-target androidlib` actually loads, every way
# that matters, on a device.
#
#   compiler/vm/tools/android-load-test.sh <path to lib<name>.so> [main class]
#
# Three loads, because they test different things and none covers the others:
#
#   1. dlopen from a plain native process, then JNI_CreateJavaVM out of the library, then call the
#      Java main. This shows the VM inside the library starts and runs. It says nothing about
#      JNI_OnLoad -- a native process never calls it.
#
#   2. System.load from ART, driven by app_process with a small dex. This is how an APK loads the
#      library, and it is the only way to find out whether ART accepts what JNI_OnLoad returns. A
#      library it rejects fails with a bare UnsatisfiedLinkError that names no cause.
#
#   3. Both at once: an ART process loads a small bridge library, and the bridge starts *our* VM
#      and calls into it. This is what the stub will do, and it is the only one of the three that
#      puts two virtual machines in one process -- two sets of signal handlers, two heaps, and two
#      symbols called JNI_CreateJavaVM. Passing 1 and 2 says nothing about whether that holds.
#
# No APK: app_process runs a main class straight from a dex, so there is no manifest, no resource
# compilation and no signing between a change and the answer. An APK adds packaging, not coverage.
#
# Needs: the NDK (found under the Android SDK), build-tools for d8, a JDK, and a device or
# emulator on adb.
set -e

SO=${1:?usage: android-load-test.sh <path to lib<name>.so> [main class, default Hello]}
MAIN=${2:-Hello}
HERE=$(cd "$(dirname "$0")" && pwd)
SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
ADB="$SDK/platform-tools/adb"
DEVDIR=/data/local/tmp/tsb-load-test

say() { printf '\n\033[1m%s\033[0m\n' "$1"; }
die() { printf '\n\033[31m%s\033[0m\n' "$1" >&2; exit 1; }

[ -f "$SO" ] || die "No such library: $SO"
[ -x "$ADB" ] || die "No adb at $ADB. Set ANDROID_HOME."

NDK=${ANDROID_NDK_HOME:-$(ls -d "$SDK"/ndk/* 2>/dev/null | sort | tail -1)}
[ -d "$NDK" ] || die "No NDK. Set ANDROID_NDK_HOME, or install one under <sdk>/ndk/."
CC=$(ls -d "$NDK"/toolchains/llvm/prebuilt/*/bin 2>/dev/null | head -1)/clang
[ -x "$CC" ] || die "No clang in the NDK at $NDK"

BT=$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)
[ -x "$BT/d8" ] || die "No d8 in $SDK/build-tools/"
PLATFORM=$(ls -d "$SDK"/platforms/android-* 2>/dev/null | sort -V | tail -1)
[ -f "$PLATFORM/android.jar" ] || die "No android.jar under $SDK/platforms/"

DEV=$("$ADB" devices | awk '/device$/ {print $1; exit}')
[ -n "$DEV" ] || die "No device on adb."

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
LIBNAME=$(basename "$SO")

say "device: $DEV, $("$ADB" -s "$DEV" shell getprop ro.product.cpu.abi | tr -d '\r'), API $("$ADB" -s "$DEV" shell getprop ro.build.version.sdk | tr -d '\r')"
say "library: $SO ($(du -h "$SO" | cut -f1))"

"$ADB" -s "$DEV" shell "mkdir -p $DEVDIR" < /dev/null
"$ADB" -s "$DEV" push "$SO" "$DEVDIR/$LIBNAME" > /dev/null

# --- 1. dlopen, and start the VM out of the library ---------------------------------------------
cat > "$WORK/loadtest.c" <<EOF
#include <dlfcn.h>
#include <stdio.h>
#include <string.h>
#include <jni.h>

typedef jint (*CreateJavaVM)(JavaVM**, void**, void*);

int main(int argc, char** argv) {
    void* handle = dlopen(argv[1], RTLD_NOW | RTLD_GLOBAL);
    if (!handle) { printf("FAIL dlopen: %s\n", dlerror()); return 1; }
    printf("ok   dlopen\n");

    CreateJavaVM createJavaVM = (CreateJavaVM) dlsym(handle, "JNI_CreateJavaVM");
    if (!createJavaVM) { printf("FAIL dlsym JNI_CreateJavaVM\n"); return 1; }
    printf("ok   dlsym JNI_CreateJavaVM\n");

    JavaVM* vm = NULL; JNIEnv* env = NULL; JavaVMInitArgs args;
    memset(&args, 0, sizeof(args));
    args.version = JNI_VERSION_1_2;
    if (createJavaVM(&vm, (void**) &env, &args) != JNI_OK) { printf("FAIL JNI_CreateJavaVM\n"); return 1; }
    printf("ok   JNI_CreateJavaVM\n");

    jclass cls = (*env)->FindClass(env, argv[2]);
    if (!cls) { printf("FAIL FindClass %s\n", argv[2]); return 1; }
    jmethodID mid = (*env)->GetStaticMethodID(env, cls, "main", "([Ljava/lang/String;)V");
    if (!mid) { printf("FAIL GetStaticMethodID main\n"); return 1; }
    jclass stringClass = (*env)->FindClass(env, "java/lang/String");
    printf("--- %s.main ---\n", argv[2]);
    (*env)->CallStaticVoidMethod(env, cls, mid, (*env)->NewObjectArray(env, 0, stringClass, NULL));
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionDescribe(env); printf("FAIL main threw\n"); return 1; }
    printf("--- returned ---\n");
    return 0;
}
EOF
ABI=$("$ADB" -s "$DEV" shell getprop ro.product.cpu.abi | tr -d '\r')
case "$ABI" in
  arm64-v8a) TRIPLE=aarch64-linux-android26 ;;
  x86_64)    TRIPLE=x86_64-linux-android26 ;;
  *)         die "Unsupported ABI: $ABI" ;;
esac
"$CC" --target=$TRIPLE "$WORK/loadtest.c" -ldl -o "$WORK/loadtest"
"$ADB" -s "$DEV" push "$WORK/loadtest" "$DEVDIR/" > /dev/null
"$ADB" -s "$DEV" shell "chmod 755 $DEVDIR/loadtest" < /dev/null

say "1. dlopen, then JNI_CreateJavaVM out of the library"
"$ADB" -s "$DEV" shell "cd $DEVDIR && ./loadtest ./$LIBNAME $MAIN; echo EXIT=\$?" < /dev/null | tr -d '\r'

# --- 2. System.load from ART --------------------------------------------------------------------
mkdir -p "$WORK/java" "$WORK/classes"
cat > "$WORK/java/LoadProbe.java" <<'EOF'
public class LoadProbe {
    public static void main(String[] args) {
        try {
            System.load(args[0]);
            System.out.println("ok   ART loaded it and accepted JNI_OnLoad");
        } catch (Throwable t) {
            System.out.println("FAIL " + t);
            System.exit(1);
        }
    }
}
EOF
JAVAC=${JAVA_HOME:+$JAVA_HOME/bin/javac}
JAVAC=${JAVAC:-javac}
"$JAVAC" --release 8 -nowarn -classpath "$PLATFORM/android.jar" -d "$WORK/classes" "$WORK/java/LoadProbe.java"
"$BT/d8" --min-api 26 --output "$WORK" "$WORK/classes/LoadProbe.class"
"$ADB" -s "$DEV" push "$WORK/classes.dex" "$DEVDIR/" > /dev/null

say "2. System.load from ART, the way an APK loads it"
"$ADB" -s "$DEV" shell "cd $DEVDIR && CLASSPATH=$DEVDIR/classes.dex app_process $DEVDIR LoadProbe $DEVDIR/$LIBNAME; echo EXIT=\$?" < /dev/null | tr -d '\r'

# --- 3. our VM inside an ART process ------------------------------------------------------------
cat > "$WORK/bridge.c" <<'EOF'
#include <dlfcn.h>
#include <stdio.h>
#include <string.h>
#include <jni.h>

typedef jint (*CreateJavaVM)(JavaVM**, void**, void*);

JNIEXPORT jint JNICALL Java_MachineProbe_startMachine(JNIEnv* artEnv, jclass cls, jstring jpath, jstring jmain) {
    /* Unbuffered: the Java side ends with System.exit, which ends the process without flushing C
     * stdio, and the evidence lines would be lost with only an exit code left. */
    setvbuf(stdout, NULL, _IONBF, 0);
    const char* path = (*artEnv)->GetStringUTFChars(artEnv, jpath, NULL);
    const char* mainClass = (*artEnv)->GetStringUTFChars(artEnv, jmain, NULL);

    /* RTLD_LOCAL on purpose: the machine exports JNI_* and so does ART. Keeping the machine out of
     * the global namespace is what stops one from answering for the other. */
    void* handle = dlopen(path, RTLD_NOW | RTLD_LOCAL);
    if (!handle) { printf("FAIL dlopen: %s\n", dlerror()); return 1; }
    printf("ok   dlopen from inside ART\n");

    CreateJavaVM createJavaVM = (CreateJavaVM) dlsym(handle, "JNI_CreateJavaVM");
    if (!createJavaVM) { printf("FAIL dlsym\n"); return 2; }

    JavaVM* vm = NULL; JNIEnv* env = NULL; JavaVMInitArgs args;
    memset(&args, 0, sizeof(args));
    args.version = JNI_VERSION_1_2;
    if (createJavaVM(&vm, (void**) &env, &args) != JNI_OK) { printf("FAIL JNI_CreateJavaVM\n"); return 3; }
    printf("ok   our VM started inside an ART process\n");

    jclass mc = (*env)->FindClass(env, mainClass);
    if (!mc) { printf("FAIL FindClass %s\n", mainClass); return 4; }
    jmethodID mid = (*env)->GetStaticMethodID(env, mc, "main", "([Ljava/lang/String;)V");
    if (!mid) { printf("FAIL GetStaticMethodID\n"); return 5; }
    jclass sc = (*env)->FindClass(env, "java/lang/String");
    printf("--- crossing into %s.main ---\n", mainClass);
    (*env)->CallStaticVoidMethod(env, mc, mid, (*env)->NewObjectArray(env, 0, sc, NULL));
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionDescribe(env); return 6; }
    printf("--- back in ART ---\n");
    return 0;
}
EOF
cat > "$WORK/java/MachineProbe.java" <<'EOF'
public class MachineProbe {
    static native int startMachine(String libPath, String mainClass);
    public static void main(String[] args) {
        System.load(args[0]);
        int r = startMachine(args[1], args[2]);
        System.out.println(r == 0 ? "ok   returned to ART cleanly" : "FAIL code " + r);
        System.exit(r);
    }
}
EOF
"$CC" --target=$TRIPLE -shared -fPIC "$WORK/bridge.c" -ldl -o "$WORK/libbridge.so"
"$JAVAC" --release 8 -nowarn -classpath "$PLATFORM/android.jar" -d "$WORK/classes" "$WORK/java/MachineProbe.java"
rm -f "$WORK/classes.dex"
"$BT/d8" --min-api 26 --output "$WORK" "$WORK/classes/MachineProbe.class"
"$ADB" -s "$DEV" push "$WORK/libbridge.so" "$DEVDIR/" > /dev/null
"$ADB" -s "$DEV" push "$WORK/classes.dex" "$DEVDIR/probe.dex" > /dev/null

say "3. our VM started from inside an ART process -- what the stub will do"
"$ADB" -s "$DEV" shell "cd $DEVDIR && CLASSPATH=$DEVDIR/probe.dex app_process $DEVDIR MachineProbe $DEVDIR/libbridge.so $DEVDIR/$LIBNAME $MAIN; echo EXIT=\$?" < /dev/null | tr -d '\r'
