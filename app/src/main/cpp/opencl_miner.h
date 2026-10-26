// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 SecretArrow.
//
// Optional OpenCL GPU mining backend. Loaded via dlopen so the APK runs on
// devices without libOpenCL.so. All functions are exception-free.
#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace noid {

/// True when libOpenCL.so exposes a usable GPU device (cached after first call).
bool opencl_available();

/// Human-readable GPU device name (empty when unavailable).
std::string opencl_device_name();

/// Batch-hash `count` consecutive nonces (same semantics as the CPU cffi).
/// Returns false on any OpenCL failure — the caller must fall back to CPU.
bool opencl_batch_hash(const std::string& pow_fields_hex,
                       uint64_t nonce_start,
                       uint32_t count,
                       std::vector<uint8_t>& out);

/// Free all OpenCL resources (idempotent).
void opencl_release();

}  // namespace noid
