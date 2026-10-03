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
# stubdata stays, and the ICU data arrives a different way entirely.
#
# This file spent 03.10.2026 linking a real icudt68l.dat here and it could never have worked. The
# reason is in libcore.luni4/.../android_icu_impl_ICUBinary.cpp:
#
#     // RoboVM note: Look for custom ICU data and fall back to builtin minimal data if not found.
#     if (findCustomICUData(path))  icu_data_ = IcuDataMap::Create(path);
#     else                          icu_data_ = IcuDataGzip::Create();
#     udata_setCommonData(icu_data_->data_, &status);
#     udata_setFileAccess(UDATA_NO_FILES, &status);
#
# udata_setCommonData registers a package that takes precedence over the linked-in entry point, so
# whatever is linked here is shadowed. The builtin fallback is a minimal package of 47 entries —
# visible in a UDATA_DEBUG log as "entry 34 of 47" for res_index against "entry 1468 of 3823" for
# a file that was linked beside it. That minimal res_index lists one locale, "en", which is where
# the single available locale comes from.
#
# And ICU4J reads the same package and nothing else. ICUBinary.java:
#
#     // RoboVM Note: will not provide both icu4j resources and .dat file for native part
#     // instead asking for data file from native part
#     ByteBuffer pkgBytes = getIcuData();
#
# So java.text.NumberFormat, java.text.Collator and java.time all come out of that one 47-entry
# package. Putting the icu4j data jar's resources into robovm-rt.jar does not help either: tried,
# and it fails with "pool.res has a different checksum than this bundle", because the two halves
# then come from different builds.
#
# THE WAY IN IS findCustomICUData, which looks for icudt<version>l.dat beside the executable — and
# only on Apple platforms. For Linux and Android it is a hard-coded "/usr/share/icu" with a TODO
# beside it.
#
# What that means for each target:
#
#   macOS/iOS   the file goes next to the binary, which for an app means into the bundle. rfxmobile
#               already copies resources there.
#   Android     findCustomICUData needs a path it can actually use. The machine is already told
#               where its resources are, through -rvm:ResourcesPath=, which is how FlatLaf's
#               .properties were found in A4 — the same hook fits here.
#
# tools/icu-data.sh builds the file. Measured: 27.2 MB everything, 15.6 MB for 28 European
# languages, 9.8 MB once transliterations and the old converters go.
set(SRC
  ${SRC_COMMON}
  ${SRC_I18N}
  ${SRC_LIBCORE_BRIDGE}
  ${ICU_PATH}/stubdata/stubdata.cpp
)


add_library(android-external-icu4c OBJECT ${SRC})
set(EXTERNAL_ICU4C_OBJECTS $<TARGET_OBJECTS:android-external-icu4c> PARENT_SCOPE)