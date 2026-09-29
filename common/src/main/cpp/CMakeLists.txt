cmake_minimum_required(VERSION 3.22)
project(wheelplay_rtc LANGUAGES C CXX)
include(FetchContent)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_SKIP_INSTALL_RULES ON)
# MbedTLS leaves DTLS-SRTP disabled by default; WebRTC requires the use_srtp extension.
add_compile_definitions(MBEDTLS_SSL_DTLS_SRTP)
add_compile_options(-O2)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(ENABLE_PROGRAMS OFF CACHE BOOL "" FORCE)
set(ENABLE_TESTING OFF CACHE BOOL "" FORCE)
set(NO_TESTS ON CACHE BOOL "" FORCE)
set(NO_EXAMPLES ON CACHE BOOL "" FORCE)
set(NO_WEBSOCKET ON CACHE BOOL "" FORCE)
set(NO_MEDIA OFF CACHE BOOL "" FORCE)
set(USE_MBEDTLS ON CACHE BOOL "" FORCE)
# Use the bundled MbedTLS backend, including runtime-detected ARM AES acceleration.
set(ENABLE_MBEDTLS ON CACHE BOOL "" FORCE)
set(ENABLE_WARNINGS_AS_ERRORS OFF CACHE BOOL "" FORCE)
set(FETCHCONTENT_UPDATES_DISCONNECTED ON)
FetchContent_Declare(mbedtls
    GIT_REPOSITORY https://github.com/Mbed-TLS/mbedtls.git
    GIT_TAG e185d7fd85499c8ce5ca2a54f5cf8fe7dbe3f8df
    GIT_SHALLOW TRUE)
FetchContent_MakeAvailable(mbedtls)
add_library(MbedTLS::MbedTLS ALIAS mbedtls)
FetchContent_Declare(libdatachannel
    GIT_REPOSITORY https://github.com/paullouisageneau/libdatachannel.git
    GIT_TAG a02b751917ac8afc8c58dc6f4461d25ff9465d48)
FetchContent_GetProperties(libdatachannel)
if(NOT libdatachannel_POPULATED)
    FetchContent_Populate(libdatachannel)
endif()
# libSRTP's find module otherwise searches host libraries during Android cross compilation.
include(dependency_patch.cmake)
wheelplay_replace("${libdatachannel_SOURCE_DIR}/deps/libsrtp/CMakeLists.txt"
    "  find_package(MbedTLS REQUIRED)" [=[
  if(TARGET mbedcrypto)
    set(MBEDTLS_LIBRARIES mbedcrypto)
    get_target_property(MBEDTLS_INCLUDE_DIRS mbedcrypto INTERFACE_INCLUDE_DIRECTORIES)
  else()
    find_package(MbedTLS REQUIRED)
  endif()]=])
add_subdirectory("${libdatachannel_SOURCE_DIR}" "${libdatachannel_BINARY_DIR}")
include(media_send_feedback.cmake)
include(srtp_optimization.cmake)
add_library(wheelplay-rtc SHARED rtc_bridge.cpp video_crypto.cpp)
target_link_libraries(wheelplay-rtc PRIVATE datachannel-static mbedcrypto log)
target_link_options(wheelplay-rtc PRIVATE "-Wl,-z,max-page-size=16384")

# Standalone device validation executable; not an APK target and absent from release builds.
if(CMAKE_BUILD_TYPE STREQUAL "Debug")
    add_executable(wheelplay-srtp-check EXCLUDE_FROM_ALL ../../../../test/native/srtp-packet.cpp)
    target_include_directories(wheelplay-srtp-check PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}")
    target_compile_options(wheelplay-srtp-check PRIVATE -UNDEBUG)
    target_link_libraries(wheelplay-srtp-check PRIVATE srtp2)
endif()
