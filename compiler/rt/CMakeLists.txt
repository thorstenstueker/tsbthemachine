project(rt)

add_subdirectory(robovm)
add_subdirectory(android)

add_library(robovm-rt STATIC ${ROBOVM_RT_CORE_OBJECTS} ${ANDROID_OBJECTS})
add_dependencies(robovm-rt extgc)
set_target_properties(robovm-rt PROPERTIES SUFFIX "${LIB_SUFFIX}")
# icudt* alongside the two patterns that were here: merging makes every symbol local except the
# ones named, and ICU's data block is reached by name from openCommonData. Without it the data is
# in the archive and invisible — which is how it stood until 03.10.2026, when linking the real
# icudt68l.dat changed nothing at all.
merge_static_lib_object_files(robovm-rt 'Java_*' 'rvmRT*' 'icudt*')
install(TARGETS robovm-rt DESTINATION ${INSTALL_DIR})
