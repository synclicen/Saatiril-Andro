cmake_minimum_required(VERSION 3.16)
project(SonyCli CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Source files
set(SOURCES
    cli/main.cpp
    cli/CrDebugString.cpp  # If exists, otherwise remove
)

# Include directories
include_directories(${CMAKE_CURRENT_SOURCE_DIR}/include)

# Find the Cr_Core.lib
set(CR_CORE_LIB ${CMAKE_CURRENT_SOURCE_DIR}/dll/../external/crsdk/Cr_Core.lib)
if(NOT EXISTS ${CR_CORE_LIB})
    # Try alternate path
    set(CR_CORE_LIB ${CMAKE_CURRENT_SOURCE_DIR}/lib/Cr_Core.lib)
endif()

# Output binary
add_executable(sony-cli ${SOURCES})

# Link with Cr_Core.lib if found
if(EXISTS ${CR_CORE_LIB})
    target_link_libraries(sony-cli ${CR_CORE_LIB})
endif()

# Copy DLLs to output directory after build
add_custom_command(TARGET sony-cli POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E copy_directory
    ${CMAKE_CURRENT_SOURCE_DIR}/dll
    $<TARGET_FILE_DIR:sony-cli>
)
