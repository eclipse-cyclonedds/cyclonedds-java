# Copyright(c) 2026 Sven Trittler
# SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause

set(CYCLONEDDS_CORE_SOURCE_DIR "" CACHE PATH "Matching Cyclone DDS core checkout, used to collect distribution notices")
set(_core_notice "${CYCLONEDDS_CORE_SOURCE_DIR}/NOTICE.md")
if(NOT CYCLONEDDS_CORE_SOURCE_DIR OR NOT EXISTS "${_core_notice}")
  message(FATAL_ERROR "Missing core notice: ${_core_notice}. Set -DCYCLONEDDS_CORE_SOURCE_DIR=/path/to/cyclonedds to the core checkout used for this installation.")
endif()
# The preprocessor moved inside idlc after the 11.0.1 release.
find_file(_mcpp_source NAMES system.H
  PATHS "${CYCLONEDDS_CORE_SOURCE_DIR}/src/tools/idlc/idlpp/src"
        "${CYCLONEDDS_CORE_SOURCE_DIR}/src/tools/idlpp/src"
  NO_DEFAULT_PATH NO_CACHE)
if(NOT _mcpp_source)
  message(FATAL_ERROR "Missing MCPP license source system.H in ${CYCLONEDDS_CORE_SOURCE_DIR}; checked src/tools/idlc/idlpp/src and src/tools/idlpp/src.")
endif()
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${_core_notice}" "${_mcpp_source}")
set(CORE_NOTICES_DIR "${CMAKE_BINARY_DIR}/dependency-notices/cyclonedds")
file(MAKE_DIRECTORY "${CORE_NOTICES_DIR}")
# Normalize upstream checkout line endings so all platform inputs share notices.
file(READ "${_core_notice}" _notice)
string(REPLACE "\r\n" "\n" _notice "${_notice}")
# file(WRITE) uses native text-mode line endings on Windows. These resources
# must have identical bytes in every platform JAR before universal assembly.
file(CONFIGURE OUTPUT "${CORE_NOTICES_DIR}/NOTICE.md" CONTENT "${_notice}" @ONLY NEWLINE_STYLE LF)
file(READ "${_mcpp_source}" _mcpp)
string(REPLACE "\r\n" "\n" _mcpp "${_mcpp}")
string(FIND "${_mcpp}" "*/" _license_end)
if(NOT _mcpp MATCHES "^/\\*-" OR _license_end LESS 0)
  message(FATAL_ERROR "Cannot locate the MCPP license header in ${_mcpp_source}")
endif()
math(EXPR _license_length "${_license_end} - 3")
string(SUBSTRING "${_mcpp}" 3 ${_license_length} _mcpp)
string(REGEX REPLACE "(^|\n)[ \t]*\\* ?" "\\1" _mcpp "${_mcpp}")
string(STRIP "${_mcpp}" _mcpp)
if(NOT _mcpp MATCHES "Kiyoshi Matsui" OR NOT _mcpp MATCHES "SUCH DAMAGE\\.")
  message(FATAL_ERROR "Incomplete MCPP license header in ${_mcpp_source}")
endif()
file(CONFIGURE OUTPUT "${CORE_NOTICES_DIR}/LICENSE-mcpp" CONTENT "${_mcpp}\n" @ONLY NEWLINE_STYLE LF)
