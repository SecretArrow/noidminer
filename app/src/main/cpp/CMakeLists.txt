cmake_minimum_required(VERSION 3.22.1)
project(noidminer_jni CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Prebuilt Rust core (built by scripts/build-rust.sh before Gradle runs).
set(NOID_POW_LIB ${CMAKE_CURRENT_SOURCE_DIR}/../../../jniLibs/${ANDROID_ABI}/libnoid_pow.so)
if(EXISTS ${NOID_POW_LIB})
    add_library(noid_pow SHARED IMPORTED)
    set_target_properties(noid_pow PROPERTIES IMPORTED_LOCATION ${NOID_POW_LIB})
    add_definitions(-DNOID_HAS_NATIVE_POW=1)
else()
    message(WARNING "libnoid_pow.so not found for ${ANDROID_ABI} — building JNI bridge without native PoW calls (software engine will be used).")
    add_definitions(-DNOID_HAS_NATIVE_POW=0)
endif()

add_library(noid_jni SHARED
    noid_jni.cpp
    opencl_miner.cpp)

find_library(log-lib log)

if(NOID_HAS_NATIVE_POW)
    target_link_libraries(noid_jni noid_pow ${log-lib} dl)
else()
    target_link_libraries(noid_jni ${log-lib} dl)
endif()
