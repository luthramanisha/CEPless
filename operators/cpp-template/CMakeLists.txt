# CMakeList.txt : Top-level CMake project file, do global configuration
# and include sub-projects here.
#
cmake_minimum_required (VERSION 3.8)
set (CMAKE_CXX_STANDARD 11)
project ("cpp-template")

include_directories (cpp_template)

file(GLOB SOURCES cpp_template/*.cpp)

#add_subdirectory (cpp_redis)
add_executable (cpp_template ${SOURCES})

find_package (Threads)
target_link_libraries (cpp_template cpp_redis tacopie ${CMAKE_THREAD_LIBS_INIT})
