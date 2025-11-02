cmake_minimum_required(VERSION 3.10)
project(neurasys_monitor C)

set(CMAKE_C_STANDARD 11)

set(SOURCES FileMonitor.c)  # Replace with your actual source file

add_library(neurasys_monitor SHARED ${SOURCES})

target_link_libraries(neurasys_monitor pthread)

set_target_properties(neurasys_monitor PROPERTIES
    LIBRARY_OUTPUT_DIRECTORY "${CMAKE_SOURCE_DIR}/../../../lib/windows"
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_SOURCE_DIR}/../../../lib/windows"
)

install(TARGETS neurasys_monitor
    LIBRARY DESTINATION lib/windows
    RUNTIME DESTINATION lib/windows
)
