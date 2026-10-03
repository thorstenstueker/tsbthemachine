project(android-external-icu4c)

set(ICU_PATH "${ANDROID_RT_PATH}/external/icu/icu4c/source")
set(LIBCORE_BRIDGE_PATH "${ANDROID_RT_PATH}/external/icu/android_icu4j/libcore_bridge/src/native")
set(COMMON_PATH "${ICU_PATH}/common")
set(I18N_PATH "${ICU_PATH}/i18n")

include_directories("${COMMON_PATH}" "${I18N_PATH}")

add_definitions(-D_REENTRANT)
add_definitions(-DU_COMMON_IMPLEMENTATION)
add_definitions(-DU_I18N_IMPLEMENTATION)
# add_definitions(-DUDATA_DEBUG)
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O3 -fvisibility=hidden -Wall -Werror -Wno-unused-parameter -Wno-missing-field-initializers")
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -Wno-sign-compare -Wno-deprecated-declarations")

file(GLOB SRC_COMMON "${COMMON_PATH}/*.cpp")
file(GLOB SRC_I18N   "${I18N_PATH}/*.cpp")
file(GLOB SRC_LIBCORE_BRIDGE "${LIBCORE_BRIDGE_PATH}/*.cpp")
# The data: stubdata for now, and the defect is still open.
#
# stubdata.cpp is ICU's placeholder — a header with no data behind it. Linking it is why this
# runtime knows exactly one locale: German numbers come out English on both telephones, a German
# date comes out "2026 M09 21", Locale.getAvailableLocales() answers 1 where JDK 25 answers 1158.
# See tools/java25/native/FormatCheck.java, which reports it on every run.
#
# Switching the two lines at the bottom links a real icudt68l.dat built by tools/icu-data.sh. It
# gets further than it looks and stops in a place nobody would guess, so here is the whole account.
#
# WHAT WORKS with the data linked:
#   * the file is in the archive whole — the .o is exactly the size of the .dat, 27.24 MB for the
#     unfiltered one, and librobovm-rt.a grows to match
#   * the symbol is global: 'icudt*' is in merge_static_lib_object_files's export list, nm shows D
#   * ICU opens it. With -DUDATA_DEBUG the log reads "icudt68l/supplementalData.res: Found",
#     "icudt68l/res_index.res: Found", "icudt68l/lang/de.res: Found"
#   * ulocdata_getCLDRVersion answers 38.1, which it reads out of supplementalData and cannot
#     answer without the file
#
# WHAT DOES NOT: res_index's InstalledLocales table reports ONE entry, "en". uloc_countAvailable
# answers 1, ures_open(NULL, "de") answers U_USING_DEFAULT_WARNING, and every locale-sensitive
# answer stays at root.
#
# The file is not the problem. Parsed by hand, outside ICU:
#
#     root table, 3 entries
#       AliasLocales       URES_TABLE16   51 entries
#       CLDRVersion        URES_STRING_V2
#       InstalledLocales   URES_TABLE16   783 entries
#
#     formatVersion 2, indexes[] = (7, 1236, 2079, 2079, 783, 1, 2074)
#
# 783 entries, as a TABLE16 — and this ICU reports its size as 1. res_countArrayItems reads a
# TABLE16's length out of p16BitUnits, which res_init only populates when formatVersion[0] > 1;
# formatVersion here is 2, so it should be populated. uresdata.cpp, uresbund.cpp and
# locavailable.cpp are all byte-identical with ICU 68.2 as published, so the reader is not patched.
#
# Also ruled out: the filter (the unfiltered 27 MB file behaves identically, rebuilt clean), a
# stale stubdata.o, and genccode instead of .incbin — that one is worse and instructive, because a
# file-scope const in C++ has internal linkage and the compiler dropped the 10 MB array entirely,
# leaving a 336-byte object.
#
# Where to pick it up: p16BitUnits after res_init, for this bundle, in a debugger. Either it is
# null-ish — in which case the question is why, with formatVersion 2 — or it is fine and the fault
# is further up.
#
# enable_language(ASM) and the configure_file stay for the switch: .incbin is resolved by the
# assembler, which reads neither an include directory nor a -D, both tried.
enable_language(ASM)
configure_file("${ICU_PATH}/data/icudata.S.in"
               "${CMAKE_CURRENT_BINARY_DIR}/icudata.S" @ONLY)

set(SRC
  ${SRC_COMMON}
  ${SRC_I18N}
  ${SRC_LIBCORE_BRIDGE}
  ${ICU_PATH}/stubdata/stubdata.cpp
  # ${CMAKE_CURRENT_BINARY_DIR}/icudata.S  <- the real data; see above
)


add_library(android-external-icu4c OBJECT ${SRC})
set(EXTERNAL_ICU4C_OBJECTS $<TARGET_OBJECTS:android-external-icu4c> PARENT_SCOPE)