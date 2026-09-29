#!/bin/bash

SELF=$(basename $0)
BASE=$(cd $(dirname $0); pwd -P)
CLEAN=0
VERBOSE=
WORKERS=8

function usage {
  cat <<EOF
Usage: $SELF [options]
Options:
  --build=[release|debug] Specifies the build type. If not set both release
                          and debug versions of the libraries will be built.
  --target=...            Specifies the target(s) to build for. Supported 
                          targets are:
                          MacOSX:
                             macosx-x86_64, macosx-arm64
                          iOS:
                             ios-arm64, ios-x86_64-simulator, ios-arm64-simulator
                          Linux:
                             linux-x86_64, linux-arm64
                          Android:
                             android-arm64, android-x86_64
                          Enclose multiple targets in quotes and
                          separate with spaces or specify --target multiple
                          times. If not set the current host OS determines the
                          targets. macosx-x86_64, ios-x86_64-simulator,
                          ios-arm64-simulator, ios-thumbv7 and
                          ios-arm64 on MacOSX and
                          linux-x86_64 on Linux. Android is never a default:
                          it needs an NDK, and a machine without one should
                          not have its build fail over a target nobody asked
                          for. Name it and it is built.
  --android-api=N         API level to compile the Android targets against.
                          Defaults to 26, which is MIN_SDK for this product --
                          compiling at the floor is how a symbol that is too
                          new becomes a build error here instead of a crash on
                          a customer's phone.
  --verbose               Enable verbose output during the build.
  --clean                 Cleans the build dir before starting the build.
  --help                  Displays this information and exits.
EOF
  exit $1
}

while [ "${1:0:2}" = '--' ]; do
  NAME=${1%%=*}
  VALUE=${1#*=}
  case $NAME in
    '--target') TARGETS="$TARGETS $VALUE" ;;
    '--clean') CLEAN=1 ;;
    '--verbose') VERBOSE=VERBOSE=1 ;;
    '--build') BUILDS="$BUILDS $VALUE" ;;
    '--android-api') ANDROID_API=$VALUE ;;
    '--help')
      usage 0
      ;;
    *)
      echo "Unrecognized option or syntax error in option '$1'"
      usage 1
      ;;
  esac
  shift
done

if [ "x$TARGETS" = 'x' ]; then
  OS=$(uname)
  case $OS in
  Darwin)
    TARGETS="macosx-arm64 macosx-x86_64 ios-x86_64-simulator ios-arm64-simulator ios-arm64"
    ;;
  Linux)
    # 27.09.2026 (tsb): ask the machine instead of assuming x86_64. A Pi or an arm64 VM
    # used to get a build for the wrong architecture and no warning about it.
    case $(uname -m) in
      aarch64|arm64) TARGETS="linux-arm64" ;;
      *)             TARGETS="linux-x86_64" ;;
    esac
    ;;
  *)
    echo "Unsupported OS: $OS"
    exit 1
    ;;
  esac
fi
if [ "x$BUILDS" = 'x' ]; then
  BUILDS="debug release"
fi
if [ "x$ANDROID_API" = 'x' ]; then
  ANDROID_API=26
fi

# Validate targets
for T in $TARGETS; do
  if ! [[ $T =~ (macosx-(x86_64|arm64))|(ios-(x86_64-simulator|arm64-simulator|thumbv7|arm64))|(linux-(x86_64|arm64))|(android-(x86_64|arm64)) ]] ; then
    echo "Unsupported target: $T"
    exit 1
  fi
done

# Validate build types
for B in $BUILDS; do
  if ! [[ $B =~ (debug|release) ]] ; then
    echo "Unsupported build type: $B"
    exit 1
  fi
done

mkdir -p "$BASE/target/build"
if [ "$CLEAN" = '1' ]; then
  for T in $TARGETS; do
    for B in $BUILDS; do
      rm -rf "$BASE/target/build/$T-$B"
    done
  done
fi

if [ $(uname) = 'Darwin' ]; then
  if xcrun -f clang &> /dev/null; then
    CC=$(xcrun -f clang)
  else
    CC=$(which clang)
  fi
  if xcrun -f clang++ &> /dev/null; then
    CXX=$(xcrun -f clang++)
  else
    CXX=$(which clang++)
  fi
else
  # 27.09.2026 (tsb): clang first on Linux, gcc only if there is no clang.
  #
  # This said gcc, and the rest of the build does not agree with it. CMakeLists.txt passes
  # -Wno-error=incompatible-function-pointer-types to the bundled collector, which is a clang
  # spelling -- gcc stops at "no option -Wincompatible-function-pointer-types" and configure
  # then reports the useless "C compiler cannot create executables". Android's libcore under
  # rt/ is worse: it is written with _Nullable and [[clang::fallthrough]] throughout.
  #
  # So the build has needed clang on Linux for a long time and asked for gcc anyway.
  CC=$(which clang || which gcc)
  CXX=$(which clang++ || which g++)
fi

for T in $TARGETS; do
  OS=${T%%-*}
  ARCH=${T#*-}
  # find out cross-compilation parameters, required to controll CMake on Apple Silicon 
  case $OS in
  macosx)
    SYSTEM_NAME_PARAM="-DCMAKE_SYSTEM_NAME=Darwin"
    ;;
  ios)
    SYSTEM_NAME_PARAM="-DCMAKE_SYSTEM_NAME=iOS"
    ;;
  *)
    SYSTEM_NAME_PARAM=""
    ;;
  esac
  
  for B in $BUILDS; do
    BUILD_TYPE=$B
    mkdir -p "$BASE/target/build/$T-$B"
    rm -rf "$BASE/binaries/$OS/$ARCH/$B"
    # 27.09.2026 (tsb): only ask xcrun where the SDK is when there is an xcrun to ask.
    # It ran unconditionally and printed "xcrun: command not found" on every Linux build.
    SDK_PARAM=""
    if command -v xcrun >/dev/null 2>&1; then
      SDK_PARAM="-DCMAKE_OSX_SYSROOT=$(xcrun --show-sdk-path)"
    fi
    if [ "$OS" = 'android' ]; then
      # Android goes through bionic.toolchain.cmake and gets none of the above.
      #
      # The toolchain file picks the NDK's clang, ar, ranlib, linker and objcopy itself, and it has
      # to: the host compiler found further up cannot produce Bionic binaries. CMAKE_OSX_SYSROOT is
      # left out for the same reason it is left out there -- on a macOS host it would hand the
      # Apple SDK to a cross build, and the failure surfaces deep inside rt/android as
      # "unsupported option '-arch'", a long way from the cause. See the header of that file.
      #
      # Added 29.09.2026 (tsb), after A3a had to call cmake by hand to get an Android distribution.
      CMAKE_ARGS="-DCMAKE_TOOLCHAIN_FILE='$BASE/bionic.toolchain.cmake' -DANDROID_API=$ANDROID_API"
    else
      CMAKE_ARGS="$SYSTEM_NAME_PARAM $SDK_PARAM -DCMAKE_C_COMPILER=$CC -DCMAKE_CXX_COMPILER=$CXX"
    fi
    bash -c "cd '$BASE/target/build/$T-$B'; cmake $CMAKE_ARGS -DCMAKE_BUILD_TYPE=$BUILD_TYPE -DOS=$OS -DARCH=$ARCH '$BASE'; make -j $WORKERS $VERBOSE install"
    R=$?
    if [[ $R != 0 ]]; then
      echo "$T-$B build failed"
      exit $R
    fi
  done
done
