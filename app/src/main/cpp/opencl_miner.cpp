// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 SecretArrow.
//
// OpenCL GPU backend (optional, dlopen-based). The kernel computes the
// Poseidon2b flat sponge; the host does tower<->flat basis conversion and
// digest assembly. Any failure simply reports false and the app falls back
// to the CPU engine — a runtime self-check against the native CPU engine
// additionally gates whether the GPU path is ever used for real mining.
#include "opencl_miner.h"

#include <android/log.h>
#include <dlfcn.h>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <vector>

#include "poseidon_matrix.h"

#define TAG "noid_opencl"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

namespace noid {
namespace {

// Minimal cl types (avoids pulling CL headers).
using cl_platform_id = void*;
using cl_device_id = void*;
using cl_context = void*;
using cl_command_queue = void*;
using cl_program = void*;
using cl_kernel = void*;
using cl_mem = void*;
using cl_int = int;
using cl_uint = unsigned int;
using cl_bool = unsigned int;
using cl_device_info = unsigned int;
using cl_program_build_info = unsigned int;

constexpr cl_int CL_SUCCESS = 0;
constexpr cl_device_info CL_DEVICE_NAME = 0x102B;
constexpr cl_program_build_info CL_PROGRAM_BUILD_LOG = 0x1183;
constexpr cl_uint CL_DEVICE_TYPE_GPU = 4;

// dlopen'd entry points.
struct ClApi {
    cl_int (*GetPlatformIDs)(cl_uint, cl_platform_id*, cl_uint*) = nullptr;
    cl_int (*GetDeviceIDs)(cl_platform_id, unsigned long long, cl_uint, cl_device_id*, cl_uint*) = nullptr;
    cl_int (*GetDeviceInfo)(cl_device_id, cl_device_info, size_t, void*, size_t*) = nullptr;
    cl_context (*CreateContext)(void*, cl_uint, const cl_device_id*, void*, void*, cl_int*) = nullptr;
    cl_command_queue (*CreateCommandQueue)(cl_context, cl_device_id, unsigned long long, cl_int*) = nullptr;
    cl_program (*CreateProgramWithSource)(cl_context, cl_uint, const char**, const size_t*, cl_int*) = nullptr;
    cl_int (*BuildProgram)(cl_program, cl_uint, const cl_device_id*, const char*, void*, void*) = nullptr;
    cl_int (*GetProgramBuildInfo)(cl_program, cl_device_id, cl_program_build_info, size_t, void*, size_t*) = nullptr;
    cl_kernel (*CreateKernel)(cl_program, const char*, cl_int*) = nullptr;
    cl_mem (*CreateBuffer)(cl_context, unsigned long long, size_t, void*, cl_int*) = nullptr;
    cl_int (*SetKernelArg)(cl_kernel, cl_uint, size_t, const void*) = nullptr;
    cl_int (*EnqueueNDRangeKernel)(cl_command_queue, cl_kernel, cl_uint, const size_t*, const size_t*, const size_t*, cl_uint, cl_mem*, void**) = nullptr;
    cl_int (*EnqueueReadBuffer)(cl_command_queue, cl_mem, cl_bool, size_t, size_t, void*, cl_uint, void*, void**) = nullptr;
    cl_int (*EnqueueWriteBuffer)(cl_command_queue, cl_mem, cl_bool, size_t, size_t, const void*, cl_uint, void*, void**) = nullptr;
    cl_int (*Finish)(cl_command_queue) = nullptr;
    cl_int (*ReleaseMemObject)(cl_mem) = nullptr;
    cl_int (*ReleaseKernel)(cl_kernel) = nullptr;
    cl_int (*ReleaseProgram)(cl_program) = nullptr;
    cl_int (*ReleaseCommandQueue)(cl_command_queue) = nullptr;
    cl_int (*ReleaseContext)(cl_context) = nullptr;
};

struct GpuState {
    bool attempted = false;
    bool ok = false;
    void* handle = nullptr;
    ClApi api;
    cl_context context = nullptr;
    cl_command_queue queue = nullptr;
    cl_kernel kernel = nullptr;
    cl_device_id device = nullptr;
    std::string device_name;
};

GpuState g_gpu;
std::mutex g_mutex;

template <typename T>
bool sym(void* handle, const char* name, T* out) {
    *out = reinterpret_cast<T>(dlsym(handle, name));
    return *out != nullptr;
}

bool load_api(GpuState& g) {
    // Order matters: vendor first, then generic names.
    const char* candidates[] = {
        "libOpenCL.so", "/vendor/lib64/libOpenCL.so", "/system/lib64/libOpenCL.so",
        "libGLES_mali.so", "libPVROCL.so",
    };
    for (const char* path : candidates) {
        g.handle = dlopen(path, RTLD_LAZY | RTLD_LOCAL);
        if (g.handle) break;
    }
    if (!g.handle) return false;

#define S(n, f) sym(g.handle, n, &g.api.f)
    return S("clGetPlatformIDs", GetPlatformIDs) && S("clGetDeviceIDs", GetDeviceIDs) &&
           S("clGetDeviceInfo", GetDeviceInfo) && S("clCreateContext", CreateContext) &&
           (S("clCreateCommandQueue", CreateCommandQueue) ||
            (sym(g.handle, "clCreateCommandQueueWithProperties", &g.api.CreateCommandQueue))) &&
           S("clCreateProgramWithSource", CreateProgramWithSource) &&
           S("clBuildProgram", BuildProgram) &&
           S("clGetProgramBuildInfo", GetProgramBuildInfo) && S("clCreateKernel", CreateKernel) &&
           S("clCreateBuffer", CreateBuffer) && S("clSetKernelArg", SetKernelArg) &&
           S("clEnqueueNDRangeKernel", EnqueueNDRangeKernel) &&
           S("clEnqueueReadBuffer", EnqueueReadBuffer) &&
           S("clEnqueueWriteBuffer", EnqueueWriteBuffer) && S("clFinish", Finish) &&
           S("clReleaseMemObject", ReleaseMemObject) && S("clReleaseKernel", ReleaseKernel) &&
           S("clReleaseProgram", ReleaseProgram) &&
           S("clReleaseCommandQueue", ReleaseCommandQueue) && S("clReleaseContext", ReleaseContext);
#undef S
}

// The kernel source (constants + sponge/permutation) is generated into
// opencl_constants.h by scripts/gen_kotlin_and_rust_data.py as a single
// raw string literal.
#include "opencl_constants.h"

bool init_locked(GpuState& g) {
    if (!load_api(g)) {
        LOGW("no libOpenCL provider found");
        return false;
    }
    cl_platform_id platforms[4] = {};
    cl_uint n_platforms = 0;
    if (g.api.GetPlatformIDs(4, platforms, &n_platforms) != CL_SUCCESS || n_platforms == 0) {
        LOGW("no OpenCL platform");
        return false;
    }
    for (cl_uint p = 0; p < n_platforms; ++p) {
        cl_device_id devices[4] = {};
        cl_uint n_devices = 0;
        if (g.api.GetDeviceIDs(platforms[p], CL_DEVICE_TYPE_GPU, 4, devices, &n_devices) == CL_SUCCESS &&
            n_devices > 0) {
            g.device = devices[0];
            break;
        }
    }
    if (!g.device) {
        LOGW("no OpenCL GPU device");
        return false;
    }
    char name[256] = {0};
    if (g.api.GetDeviceInfo(g.device, CL_DEVICE_NAME, sizeof(name) - 1, name, nullptr) == CL_SUCCESS) {
        g.device_name = name;
    }
    cl_int err = 0;
    g.context = g.api.CreateContext(nullptr, 1, &g.device, nullptr, nullptr, &err);
    if (!g.context || err != CL_SUCCESS) {
        LOGW("clCreateContext failed (%d)", err);
        return false;
    }
    g.queue = g.api.CreateCommandQueue(g.context, g.device, 0, &err);
    if (!g.queue || err != CL_SUCCESS) {
        LOGW("command queue failed (%d)", err);
        return false;
    }
    const char* src = FULL_KERNEL_SOURCE;
    size_t len = strlen(src);
    g.kernel = nullptr;
    cl_program prog = g.api.CreateProgramWithSource(g.context, 1, &src, &len, &err);
    if (!prog || err != CL_SUCCESS) {
        LOGW("program creation failed (%d)", err);
        return false;
    }
    err = g.api.BuildProgram(prog, 1, &g.device, "-cl-fast-relaxed-math", nullptr, nullptr);
    if (err != CL_SUCCESS) {
        char log[1024] = {0};
        g.api.GetProgramBuildInfo(prog, g.device, CL_PROGRAM_BUILD_LOG, sizeof(log) - 1, log, nullptr);
        LOGW("kernel build failed (%d): %s", err, log);
        g.api.ReleaseProgram(prog);
        return false;
    }
    g.kernel = g.api.CreateKernel(prog, "noid_leaf_sponge", &err);
    g.api.ReleaseProgram(prog);
    if (!g.kernel || err != CL_SUCCESS) {
        LOGW("kernel creation failed (%d)", err);
        return false;
    }
    return true;
}

// ------------------------------------------------------- GF(2^128) host math

struct U128 {
    uint64_t lo = 0;
    uint64_t hi = 0;
};

U128 apply_matrix(const unsigned long long (*m)[2], const U128& v) {
    U128 res;
    U128 cur = v;
    for (int i = 0; i < 128; ++i) {
        if (cur.lo & 1ULL) {
            res.lo ^= m[i][0];
            res.hi ^= m[i][1];
        }
        cur.lo = (cur.lo >> 1) | (cur.hi << 63);
        cur.hi >>= 1;
    }
    return res;
}

U128 load_le(const uint8_t* p) {
    uint64_t lo = 0;
    uint64_t hi = 0;
    memcpy(&lo, p, 8);
    memcpy(&hi, p + 8, 8);
    return U128{lo, hi};
}

void store_le(uint8_t* p, const U128& v) {
    memcpy(p, &v.lo, 8);
    memcpy(p + 8, &v.hi, 8);
}

std::vector<uint8_t> parse_hex(const std::string& hex, size_t expected) {
    if (hex.size() != expected * 2) return {};
    auto nib = [](char c) -> int {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    };
    std::vector<uint8_t> out(expected, 0);
    for (size_t i = 0; i < expected; ++i) {
        int hi = nib(hex[2 * i]);
        int lo = nib(hex[2 * i + 1]);
        if (hi < 0 || lo < 0) return {};
        out[i] = static_cast<uint8_t>((hi << 4) | lo);
    }
    return out;
}

}  // namespace

bool opencl_available() {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_gpu.attempted) {
        g_gpu.attempted = true;
        g_gpu.ok = init_locked(g_gpu);
        if (g_gpu.ok) LOGI("OpenCL ready: %s", g_gpu.device_name.c_str());
    }
    return g_gpu.ok;
}

std::string opencl_device_name() {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_gpu.attempted) {
        g_gpu.attempted = true;
        g_gpu.ok = init_locked(g_gpu);
    }
    return g_gpu.ok ? g_gpu.device_name : std::string();
}

bool opencl_batch_hash(const std::string& pow_fields_hex,
                       uint64_t nonce_start,
                       uint32_t count,
                       std::vector<uint8_t>& out) {
    if (count == 0 || count > 4096) return false;
    if (!opencl_available()) return false;

    auto field_bytes = parse_hex(pow_fields_hex, 256);
    if (field_bytes.empty()) return false;

    // Build flat leaves: 16 fields x (lo,hi) ulongs, nonce overwrites field 10.
    std::vector<uint64_t> leaves(static_cast<size_t>(count) * 32u);
    U128 flat_fields[16];
    for (int i = 0; i < 16; ++i) {
        U128 tower = load_le(&field_bytes[i * 16]);
        flat_fields[i] = apply_matrix(TOWER_TO_FLAT, tower);
    }
    for (uint32_t n = 0; n < count; ++n) {
        uint64_t* leaf = &leaves[static_cast<size_t>(n) * 32u];
        for (int i = 0; i < 16; ++i) {
            leaf[i * 2] = flat_fields[i].lo;
            leaf[i * 2 + 1] = flat_fields[i].hi;
        }
        U128 nonce_tower{nonce_start + n, 0};
        U128 nonce_flat = apply_matrix(TOWER_TO_FLAT, nonce_tower);
        leaf[20] = nonce_flat.lo;   // field 10 low word
        leaf[21] = nonce_flat.hi;   // field 10 high word
    }

    // IV: capacity words for TAG_POWHDR in flat basis (hi, lo).
    U128 iv_tower_hi{0ULL, 0x50574844525f5f5fULL};  // label_u64 << 64
    U128 iv_tower_lo{0x50574844525f5f5fULL, 0ULL};  // label_u64
    U128 iv_hi = apply_matrix(TOWER_TO_FLAT, iv_tower_hi);
    U128 iv_lo = apply_matrix(TOWER_TO_FLAT, iv_tower_lo);
    const uint64_t iv_words[4] = {iv_hi.lo, iv_hi.hi, iv_lo.lo, iv_lo.hi};

    GpuState& g = g_gpu;
    cl_int err = 0;
    const size_t leaf_bytes = static_cast<size_t>(count) * 32u * sizeof(uint64_t);
    const size_t out_words = static_cast<size_t>(count) * 8u;

    cl_mem buf_leaves = g.api.CreateBuffer(g.context, 0x1 /*CL_MEM_READ_ONLY*/, leaf_bytes, nullptr, &err);
    cl_mem buf_iv = g.api.CreateBuffer(g.context, 0x1, sizeof(iv_words), nullptr, &err);
    cl_mem buf_out = g.api.CreateBuffer(g.context, 0x2 /*CL_MEM_WRITE_ONLY*/, out_words * sizeof(uint64_t), nullptr, &err);
    if (!buf_leaves || !buf_iv || !buf_out) {
        LOGW("buffer alloc failed");
        return false;
    }
    bool ok = false;
    do {
        if (g.api.EnqueueWriteBuffer(g.queue, buf_leaves, 1 /*CL_TRUE*/, 0, leaf_bytes, leaves.data(), 0, nullptr, nullptr) != CL_SUCCESS) break;
        if (g.api.EnqueueWriteBuffer(g.queue, buf_iv, 1, 0, sizeof(iv_words), iv_words, 0, nullptr, nullptr) != CL_SUCCESS) break;
        if (g.api.SetKernelArg(g.kernel, 0, sizeof(cl_mem), &buf_leaves) != CL_SUCCESS) break;
        if (g.api.SetKernelArg(g.kernel, 1, sizeof(cl_mem), &buf_iv) != CL_SUCCESS) break;
        if (g.api.SetKernelArg(g.kernel, 2, sizeof(cl_mem), &buf_out) != CL_SUCCESS) break;
        unsigned int c = count;
        if (g.api.SetKernelArg(g.kernel, 3, sizeof(unsigned int), &c) != CL_SUCCESS) break;
        size_t gws = count;
        if (g.api.EnqueueNDRangeKernel(g.queue, g.kernel, 1, nullptr, &gws, nullptr, 0, nullptr, nullptr) != CL_SUCCESS) break;
        std::vector<uint64_t> raw(out_words);
        if (g.api.EnqueueReadBuffer(g.queue, buf_out, 1, 0, out_words * sizeof(uint64_t), raw.data(), 0, nullptr, nullptr) != CL_SUCCESS) break;
        if (g.api.Finish(g.queue) != CL_SUCCESS) break;

        // Assemble digests: flat -> tower per 16-byte half.
        out.resize(static_cast<size_t>(count) * 32u);
        for (uint32_t n = 0; n < count; ++n) {
            U128 st0{raw[n * 8 + 0], raw[n * 8 + 1]};
            U128 st1{raw[n * 8 + 2], raw[n * 8 + 3]};
            U128 d_hi = apply_matrix(FLAT_TO_TOWER, st0);
            U128 d_lo = apply_matrix(FLAT_TO_TOWER, st1);
            store_le(&out[static_cast<size_t>(n) * 32u], d_hi);
            store_le(&out[static_cast<size_t>(n) * 32u + 16], d_lo);
        }
        ok = true;
    } while (false);

    g.api.ReleaseMemObject(buf_leaves);
    g.api.ReleaseMemObject(buf_iv);
    g.api.ReleaseMemObject(buf_out);
    if (!ok) LOGW("opencl batch failed — caller should fall back to CPU");
    return ok;
}

void opencl_release() {
    std::lock_guard<std::mutex> lock(g_mutex);
    GpuState& g = g_gpu;
    if (g.kernel) g.api.ReleaseKernel(g.kernel);
    if (g.queue) g.api.ReleaseCommandQueue(g.queue);
    if (g.context) g.api.ReleaseContext(g.context);
    if (g.handle) dlclose(g.handle);
    g = GpuState{};
}

}  // namespace noid
