// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 SecretArrow.
//
// JNI bridge between Kotlin (org.secretarrow.noidminer.pow.NativePow) and:
//  - libnoid_pow.so   — Rust core wrapping upstream noid_poseidon2b (CPU path)
//  - libOpenCL.so     — optional GPU path (dlopen, never link-time required)
//
// CRITICAL: every JNI function catches all C++ exceptions and returns
// null/false on error. Nothing may propagate across the JNI boundary.

#include <jni.h>
#include <android/log.h>
#include <cstdint>
#include <cstring>
#include <stdexcept>
#include <string>
#include <vector>

#include "opencl_miner.h"

extern "C" {
#if NOID_HAS_NATIVE_POW
int32_t noidminer_hash_pow(const char* pow_fields_hex, unsigned long long nonce, uint8_t* out);
int32_t noidminer_batch_hash(const char* pow_fields_hex, unsigned long long nonce_start,
                             unsigned int nonce_count, uint8_t* out, size_t out_len);
int32_t noidminer_batch_hash_lanes(const char* pow_fields_hex, unsigned long long lane_hi,
                                   unsigned long long lane_lo_start, unsigned int nonce_count,
                                   uint8_t* out, size_t out_len);
int32_t noidminer_check_target(const uint8_t* hash, size_t hash_len, const char* target_le_hex);
int32_t noidminer_le256_lt(const uint8_t* a, const uint8_t* b);
int32_t noidminer_validate_address(const char* addr);
int64_t noidminer_bench(const char* pow_fields_hex, unsigned long long nonce_start,
                        unsigned int iters);
#endif
}

#define LOG_TAG "noid_jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

std::string jstr_to_std(JNIEnv* env, jstring jstr) {
    if (!jstr || !env) return {};
    const char* chars = env->GetStringUTFChars(jstr, nullptr);
    if (!chars) return {};
    std::string s(chars);
    env->ReleaseStringUTFChars(jstr, chars);
    return s;
}

jbyteArray bytes_to_jbyteArray(JNIEnv* env, const std::vector<uint8_t>& bytes) {
    jbyteArray result = env->NewByteArray(static_cast<jsize>(bytes.size()));
    if (!result) return nullptr;
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(bytes.size()),
                            reinterpret_cast<const jbyte*>(bytes.data()));
    return result;
}

constexpr jint MAX_BATCH = 4096;

}  // namespace

// ----------------------------------------------------------------- CPU path

extern "C" JNIEXPORT jbyteArray JNICALL
Java_org_secretarrow_noidminer_pow_NativePow_batchHashLaneNative(
        JNIEnv* env, jclass, jstring jPowFields, jlong jLaneHi, jlong jLaneLoStart, jint jCount) {
    if (!env) return nullptr;
    try {
#if NOID_HAS_NATIVE_POW
        auto fields = jstr_to_std(env, jPowFields);
        if (jCount <= 0 || jCount > MAX_BATCH) return nullptr;
        std::vector<uint8_t> out(static_cast<size_t>(jCount) * 32u, 0);
        if (noidminer_batch_hash_lanes(fields.c_str(),
                                       static_cast<unsigned long long>(jLaneHi),
                                       static_cast<unsigned long long>(jLaneLoStart),
                                       static_cast<unsigned int>(jCount), out.data(),
                                       out.size()) != 0) {
            return nullptr;
        }
        return bytes_to_jbyteArray(env, out);
#else
        (void)jLaneHi;
        (void)jLaneLoStart;
        return nullptr;
#endif
    } catch (const std::exception& e) {
        LOGE("batchHashLaneNative: %s", e.what());
        return nullptr;
    } catch (...) {
        return nullptr;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_secretarrow_noidminer_pow_NativePow_checkTargetNative(
        JNIEnv* env, jclass, jbyteArray jHash, jstring jTargetLe) {
    if (!env) return JNI_FALSE;
    try {
#if NOID_HAS_NATIVE_POW
        if (!jHash || env->GetArrayLength(jHash) != 32) return JNI_FALSE;
        uint8_t hash[32] = {0};
        env->GetByteArrayRegion(jHash, 0, 32, reinterpret_cast<jbyte*>(hash));
        auto target = jstr_to_std(env, jTargetLe);
        return noidminer_check_target(hash, 32, target.c_str()) == 1 ? JNI_TRUE : JNI_FALSE;
#else
        return JNI_FALSE;
#endif
    } catch (...) {
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_secretarrow_noidminer_pow_NativePow_le256LtNative(
        JNIEnv* env, jclass, jbyteArray jA, jbyteArray jB) {
    if (!env) return JNI_FALSE;
    try {
#if NOID_HAS_NATIVE_POW
        if (!jA || !jB || env->GetArrayLength(jA) != 32 || env->GetArrayLength(jB) != 32) {
            return JNI_FALSE;
        }
        uint8_t a[32] = {0};
        uint8_t b[32] = {0};
        env->GetByteArrayRegion(jA, 0, 32, reinterpret_cast<jbyte*>(a));
        env->GetByteArrayRegion(jB, 0, 32, reinterpret_cast<jbyte*>(b));
        return noidminer_le256_lt(a, b) == 1 ? JNI_TRUE : JNI_FALSE;
#else
        return JNI_FALSE;
#endif
    } catch (...) {
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_secretarrow_noidminer_pow_NativePow_validateAddressNative(
        JNIEnv* env, jclass, jstring jAddr) {
    if (!env) return JNI_FALSE;
    try {
#if NOID_HAS_NATIVE_POW
        auto addr = jstr_to_std(env, jAddr);
        return noidminer_validate_address(addr.c_str()) == 1 ? JNI_TRUE : JNI_FALSE;
#else
        return JNI_FALSE;
#endif
    } catch (...) {
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jlong JNICALL
Java_org_secretarrow_noidminer_pow_NativePow_benchNative(
        JNIEnv* env, jclass, jstring jPowFields, jlong jNonceStart, jint jIters) {
    if (!env) return -1;
    try {
#if NOID_HAS_NATIVE_POW
        auto fields = jstr_to_std(env, jPowFields);
        return noidminer_bench(fields.c_str(), static_cast<unsigned long long>(jNonceStart),
                               static_cast<unsigned int>(jIters));
#else
        return -1;
#endif
    } catch (...) {
        return -1;
    }
}

// ----------------------------------------------------------------- GPU path

extern "C" JNIEXPORT jboolean JNICALL
Java_org_secretarrow_noidminer_pow_NativePow_openclAvailableNative(JNIEnv*, jclass) {
    return noid::opencl_available() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_secretarrow_noidminer_pow_NativePow_openclDeviceNameNative(JNIEnv* env, jclass) {
    if (!env) return nullptr;
    try {
        auto name = noid::opencl_device_name();
        if (name.empty()) return nullptr;
        return env->NewStringUTF(name.c_str());
    } catch (...) {
        return nullptr;
    }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_org_secretarrow_noidminer_pow_NativePow_openclBatchHashLaneNative(
        JNIEnv* env, jclass, jstring jPowFields, jlong jLaneHi, jlong jLaneLoStart, jint jCount) {
    if (!env) return nullptr;
    try {
        if (jCount <= 0 || jCount > MAX_BATCH) return nullptr;
        auto fields = jstr_to_std(env, jPowFields);
        std::vector<uint8_t> out(static_cast<size_t>(jCount) * 32u, 0);
        if (!noid::opencl_batch_hash(fields, static_cast<uint64_t>(jLaneHi),
                                     static_cast<uint64_t>(jLaneLoStart),
                                     static_cast<uint32_t>(jCount), out)) {
            return nullptr;
        }
        return bytes_to_jbyteArray(env, out);
    } catch (const std::exception& e) {
        LOGE("openclBatchHashLaneNative: %s", e.what());
        return nullptr;
    } catch (...) {
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_org_secretarrow_noidminer_pow_NativePow_releaseOpenclNative(JNIEnv*, jclass) {
    noid::opencl_release();
}
