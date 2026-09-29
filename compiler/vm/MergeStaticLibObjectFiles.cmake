# Copyright (C) 2015 RoboVM AB

# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at

#      http://www.apache.org/licenses/LICENSE-2.0

# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# Merges the object files in a sttaic lib into a single object file and
# overwrites the static lib with a new static lib only containing this single
# object file. Will not do anything if CMAKE_BUILD_TYPE is 'debug' as it
# destroys the debug info. Only the specified symbols will be externally
# visible in the resulting static lib.

# Usage: merge_static_lib_object_files(lib sym1 sym2 ... symn)

function(merge_static_lib_object_files lib)

  if (NOT CMAKE_BUILD_TYPE STREQUAL "debug")

    set(exported_symbols ${ARGV})
    list(REMOVE_AT exported_symbols 0)

    if (APPLE)

      foreach(sym ${exported_symbols})
        list(APPEND exported_symbols_args "-exported_symbol")
        list(APPEND exported_symbols_args "_${sym}")
      endforeach()

      string(REPLACE ";" ", " exported_symbols_joined "${exported_symbols}")
      string(REPLACE ";" " " exported_symbols_args_joined "${exported_symbols_args}")
      add_custom_command(TARGET ${lib} POST_BUILD
        COMMAND echo Merging object files in $<TARGET_FILE:${lib}> with exported symbols: ${exported_symbols_joined}
        COMMAND echo ld -arch ${CARCH} -platform_version ${CPLATFORM} ${CPLATFORM_MIN_VERSION} ${CPLATFORM_MIN_VERSION} -r ${exported_symbols_args} -all_load $<TARGET_FILE:${lib}> -o ${CMAKE_CURRENT_BINARY_DIR}/merged.o
        COMMAND ld -arch ${CARCH} -platform_version ${CPLATFORM} ${CPLATFORM_MIN_VERSION} ${CPLATFORM_MIN_VERSION} -r ${exported_symbols_args} -all_load $<TARGET_FILE:${lib}> -o ${CMAKE_CURRENT_BINARY_DIR}/merged.o
        COMMAND rm -f $<TARGET_FILE:${lib}>
        COMMAND ar rcs $<TARGET_FILE:${lib}> ${CMAKE_CURRENT_BINARY_DIR}/merged.o
      )

    else()
      # Linux, and Bionic, which is Linux for every purpose in this file.
      if(ARCH STREQUAL "arm64" OR CMAKE_SYSTEM_PROCESSOR STREQUAL "aarch64")
        set(EMULATION_MODE aarch64linux)
      elseif(64_BIT)
        set(EMULATION_MODE elf_x86_64)
      else()
        set(EMULATION_MODE elf_i386)
      endif()

      # By full path when the toolchain names one. These three are invoked directly, not through
      # the compiler driver, so on a cross build the bare names would resolve to the *host's* --
      # and on a macOS host `ld` is Apple's, which does not know --whole-archive. It stayed hidden
      # until 28.09.2026 (tsb) because this whole function is skipped for a debug build, and every
      # Android measurement so far had been a debug build.
      set(MERGE_LD ld)
      set(MERGE_OBJCOPY objcopy)
      set(MERGE_AR ar)
      if(CMAKE_LINKER)
        set(MERGE_LD "${CMAKE_LINKER}")
      endif()
      if(CMAKE_OBJCOPY)
        set(MERGE_OBJCOPY "${CMAKE_OBJCOPY}")
      endif()
      if(CMAKE_AR)
        set(MERGE_AR "${CMAKE_AR}")
      endif()
      message(STATUS "Format ${FORMAT}")

      foreach(sym ${exported_symbols})
        list(APPEND exported_symbols_args "-G")
        list(APPEND exported_symbols_args "${sym}")
      endforeach()

      string(REPLACE ";" ", " exported_symbols_joined "${exported_symbols}")
      string(REPLACE ";" " " exported_symbols_args_joined "${exported_symbols_args}")
      add_custom_command(TARGET ${lib} POST_BUILD
        COMMAND echo Merging object files in $<TARGET_FILE:${lib}> with exported symbols: ${exported_symbols_joined}
        COMMAND echo ${MERGE_LD} -m ${EMULATION_MODE} -r --whole-archive $<TARGET_FILE:${lib}> -o ${CMAKE_CURRENT_BINARY_DIR}/tmp.o
        COMMAND ${MERGE_LD} -m ${EMULATION_MODE} -r --whole-archive $<TARGET_FILE:${lib}> -o ${CMAKE_CURRENT_BINARY_DIR}/tmp.o
        COMMAND echo ${MERGE_OBJCOPY} -w ${exported_symbols_args} ${CMAKE_CURRENT_BINARY_DIR}/tmp.o ${CMAKE_CURRENT_BINARY_DIR}/merged.o
        COMMAND ${MERGE_OBJCOPY} -w ${exported_symbols_args} ${CMAKE_CURRENT_BINARY_DIR}/tmp.o ${CMAKE_CURRENT_BINARY_DIR}/merged.o
        COMMAND rm -f $<TARGET_FILE:${lib}>
        COMMAND ${MERGE_AR} rcs $<TARGET_FILE:${lib}> ${CMAKE_CURRENT_BINARY_DIR}/merged.o
      )

    endif()

  endif()

endfunction()
