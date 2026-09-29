#!/usr/bin/env python3
"""Two edits bdwgc needs to link against Bionic. Run as the ExternalProject patch step.

Usage: bionic-bdwgc.py <extgc source dir>

The GC is pulled from mobivm/bdwgc at a pinned commit, so these patches cannot drift out from
under us -- and both are applied here rather than in a fork of that repository, which would be a
second source to keep and a second link in the GPL chain for one page of changes.

Every replacement must match exactly once. A pattern that no longer matches is an error and stops
the build: a patch that silently does nothing is worse than no patch, because what it was
protecting against reappears as a link failure hundreds of lines downstream.

Idempotent -- a source tree that already carries an edit is left alone, since ExternalProject may
re-run the patch step.
"""
import pathlib
import sys

# --- 1. gcconfig.h, AARCH64 + LINUX ------------------------------------------------------------
#
# The block refers to __data_start directly, and llvm-nm shows the reference as U -- a *strong*
# undefined symbol. GNU ld defines __data_start from its linker script; lld does not, so an AOT
# link against Bionic fails with "undefined symbol: __data_start" out of os_dep.c and dyn_load.c.
#
# SEARCH_FOR_DATA_START is bdwgc's own answer to exactly this, and several other platforms in the
# same file already take it: os_dep.c then declares __data_start and data_start `#pragma weak`,
# tries each at startup, and falls back -- to _end when GC_no_dls is set, as our initGC does, and
# to GC_find_limit otherwise. Nothing about the collector changes; only who resolves the address,
# and when.
#
# _end goes from `char` to `int` because os_dep.c declares `extern int _end[]` inside its own
# SEARCH_FOR_DATA_START block. That block is inactive for us today, which is why the two
# declarations have never met; switching it on makes them collide in one translation unit.
GCCONFIG_FROM = """#   ifdef LINUX
#     define OS_TYPE "LINUX"
#     define LINUX_STACKBOTTOM
#     define DYNAMIC_LOADING
      extern int __data_start[];
#     define DATASTART ((ptr_t)__data_start)
      extern char _end[];
#     define DATAEND ((ptr_t)(&_end))
#   endif"""

GCCONFIG_TO = """#   ifdef LINUX
#     define OS_TYPE "LINUX"
#     define LINUX_STACKBOTTOM
#     define DYNAMIC_LOADING
      /* tsb 28.09.2026: was `extern int __data_start[]` with DATASTART taken from it directly.
         lld does not provide __data_start the way GNU ld's linker script does, so the reference
         went out as strong-undefined and the Bionic link failed on it. See bionic-bdwgc.py. */
#     define SEARCH_FOR_DATA_START
      extern ptr_t GC_data_start;
#     define DATASTART GC_data_start
      extern int _end[];
#     define DATAEND ((ptr_t)(_end))
#   endif"""

# --- 2. pthread_stop_world.c, PLATFORM_ANDROID -------------------------------------------------
#
# tkill has never been a public symbol of Bionic; it is a raw system call, and the libc of any
# API level we care about does not export it. Upstream bdwgc long since moved to pthread_kill
# here, but this fork predates that.
#
# tgkill is the call tkill was superseded by -- same thing, with the thread group id passed
# explicitly so a recycled tid cannot be signalled by accident. Going through syscall() rather
# than a libc wrapper keeps this independent of which API level declares what.
TKILL_FROM = """  extern int tkill(pid_t tid, int sig); /* from sys/linux-unistd.h */"""

TKILL_TO = """  /* tsb 28.09.2026: was `extern int tkill(...)`. Bionic does not export tkill -- see
     bionic-bdwgc.py. tgkill is its successor and takes the thread group id explicitly. */
# include <sys/syscall.h>
  static int tkill(pid_t tid, int sig) {
    return syscall(SYS_tgkill, getpid(), tid, sig);
  }"""

# --- 3. GC_jmp_buf, one definition instead of one per translation unit -------------------------
#
# gc_priv.h declares `JMP_BUF GC_jmp_buf;` with no extern, so every unit that sees NEED_FIND_LIMIT
# emits a tentative definition of it. Under -fcommon those merged into one; clang has defaulted to
# -fno-common since version 11, and each one is now a real definition -- twenty "duplicate symbol:
# GC_jmp_buf" at the AOT link. It surfaced here only because patch 1 turns on the code path that
# needs it. Upstream fixed this the same way rather than asking for -fcommon back, and so do we:
# the declaration is the bug, not the compiler's default.
JMPBUF_FROM = """  JMP_BUF GC_jmp_buf;

  /* Set up a handler for address faults which will longjmp to  */"""

JMPBUF_TO = """  /* tsb 28.09.2026: was a tentative definition, i.e. one per translation unit. See
     bionic-bdwgc.py. The single definition now lives in os_dep.c. */
  GC_EXTERN JMP_BUF GC_jmp_buf;

  /* Set up a handler for address faults which will longjmp to  */"""

JMPBUF_DEF_FROM = """    STATIC void GC_fault_handler(int sig GC_ATTR_UNUSED)
    {
        LONGJMP(GC_jmp_buf, 1);
    }"""

JMPBUF_DEF_TO = """    /* tsb 28.09.2026: the one definition, matching the GC_EXTERN in gc_priv.h. */
    JMP_BUF GC_jmp_buf;

    STATIC void GC_fault_handler(int sig GC_ATTR_UNUSED)
    {
        LONGJMP(GC_jmp_buf, 1);
    }"""

PATCHES = [
    ("include/private/gcconfig.h", GCCONFIG_FROM, GCCONFIG_TO),
    ("pthread_stop_world.c", TKILL_FROM, TKILL_TO),
    ("include/private/gc_priv.h", JMPBUF_FROM, JMPBUF_TO),
    ("os_dep.c", JMPBUF_DEF_FROM, JMPBUF_DEF_TO),
]


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    root = pathlib.Path(sys.argv[1])

    for name, before, after in PATCHES:
        path = root / name
        text = path.read_text()
        if after in text:
            print(f"bionic-bdwgc: {name} already patched")
            continue
        count = text.count(before)
        if count != 1:
            sys.exit(f"bionic-bdwgc: {name}: expected the pattern exactly once, found {count}. "
                     f"bdwgc has moved; re-read the patch before changing the pin.")
        path.write_text(text.replace(before, after))
        print(f"bionic-bdwgc: patched {name}")


if __name__ == "__main__":
    main()
