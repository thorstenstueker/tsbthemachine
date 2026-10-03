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
# The data: stubdata for now, and why that is still a defect.
#
# stubdata.cpp is ICU's placeholder — a header with no data behind it. Linking it is why this
# runtime knows exactly one locale: German numbers come out English on both telephones, a German
# date comes out as "2026 M09 21", and Locale.getAvailableLocales() answers 1 where JDK 25 answers
# 1158. Measured 03.10.2026, see tools/java25/native/FormatCheck.java.
#
# The replacement is prepared and does not work yet. Switching the two lines below links a real
# icudt68l.dat, built by tools/icu-data.sh, and it gets ICU as far as reading supplementalData —
# ulocdata_getCLDRVersion answers 38.1 out of the file, which it cannot do without it. The locale
# resources are still not found: res_index opens without error and reports one installed locale,
# and ures_open(NULL, "de") answers U_USING_DEFAULT_WARNING.
#
# What was ruled out on the way, so that nobody repeats it:
#
#   * the filter — the unfiltered 27 MB file behaves identically
#   * a stale stubdata.o — the build directory was removed and rebuilt
#   * the symbol being local after merge_static_lib_object_files, which localises everything not
#     named: 'icudt*' is in that list now, and nm shows _icudt68_dat as D
#   * genccode instead of .incbin — worse: in C++ a file-scope const has internal linkage, so the
#     compiler dropped the whole array and left a 336-byte object
#
# So the data is linked, global, and read, and the locale index still answers one. That is where it
# stands.
#
# enable_language(ASM) and the configure_file are left in place for the switch: .incbin cannot be
# reached by an include directory or by a -D, both tried, so CMake writes the path into a generated
# copy of the file.
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