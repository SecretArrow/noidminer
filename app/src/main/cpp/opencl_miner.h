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

/// Batch-hash `count` consecutive 128-bit lanes
/// `(lane_hi shl 64) | (lane_lo_start + n)` — pool stratum passes the
/// session prefix as `lane_hi`. Returns false on any OpenCL failure — the
/// caller must fall back to CPU.
bool opencl_batch_hash(const std::string& pow_fields_hex,
                       uint64_t lane_hi,
                       uint64_t lane_lo_start,
                       uint32_t count,
                       std::vector<uint8_t>& out);

/// Free all OpenCL resources (idempotent).
void opencl_release();

}  // namespace noid
