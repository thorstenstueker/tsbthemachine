# Built only for Bionic, the way frameworksupport is built only for Darwin: each is the glue to one
# platform's way of loading a library, and neither means anything on the other.
if(BIONIC)
	project(androidsupport)

	include_directories(../../core/include)

	add_library(robovm-androidsupport STATIC androidsupport.c)
	add_dependencies(robovm-androidsupport extgc)
	set_target_properties(robovm-androidsupport PROPERTIES SUFFIX "${LIB_SUFFIX}")
	install(TARGETS robovm-androidsupport DESTINATION ${INSTALL_DIR})
endif()
