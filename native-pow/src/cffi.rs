// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 SecretArrow.
//
//! C ABI surface consumed by the Android JNI bridge (app/src/main/cpp/noid_jni.cpp).
#![allow(clippy::missing_safety_doc)]

use std::os::raw::{c_char, c_int, c_int64, c_uint, c_ulonglong};

use crate::{
    batch_hash, check_target, hash_pow, le256_lt, validate_bech32m_o1, POW_DIGEST_LEN,
};

/// Parse a hex string into bytes; returns None on malformed input.
fn hex_to_bytes(hex_str: &str) -> Option<Vec<u8>> {
    if hex_str.len() % 2 != 0 {
        return None;
    }
    let bytes = hex::decode(hex_str).ok()?;
    Some(bytes)
}

#[no_mangle]
pub extern "C" fn noidminer_hash_pow(
    pow_fields_hex: *const c_char,
    nonce: c_ulonglong,
    out: *mut u8,
) -> c_int {
    if pow_fields_hex.is_null() || out.is_null() {
        return -1;
    }
    let fields_hex = unsafe { std::ffi::CStr::from_ptr(pow_fields_hex) }
        .to_string_lossy()
        .into_owned();
    match hash_pow(&fields_hex, nonce) {
        Ok(digest) => unsafe {
            std::ptr::copy_nonoverlapping(digest.as_ptr(), out, POW_DIGEST_LEN);
            0
        },
        Err(_) => -2,
    }
}

#[no_mangle]
pub extern "C" fn noidminer_batch_hash(
    pow_fields_hex: *const c_char,
    nonce_start: c_ulonglong,
    nonce_count: c_uint,
    out: *mut u8,
    out_len: usize,
) -> c_int {
    if pow_fields_hex.is_null() || out.is_null() {
        return -1;
    }
    let fields_hex = unsafe { std::ffi::CStr::from_ptr(pow_fields_hex) }
        .to_string_lossy()
        .into_owned();
    let mut buf = vec![0u8; out_len];
    match batch_hash(&fields_hex, nonce_start, nonce_count, &mut buf) {
        Ok(()) => unsafe {
            std::ptr::copy_nonoverlapping(buf.as_ptr(), out, out_len);
            0
        },
        Err(_) => -2,
    }
}

#[no_mangle]
pub extern "C" fn noidminer_check_target(
    hash: *const u8,
    hash_len: usize,
    target_le_hex: *const c_char,
) -> c_int {
    if hash.is_null() || target_le_hex.is_null() || hash_len != POW_DIGEST_LEN {
        return 0;
    }
    let target = unsafe { std::ffi::CStr::from_ptr(target_le_hex) }
        .to_string_lossy()
        .into_owned();
    let mut digest = [0u8; POW_DIGEST_LEN];
    unsafe { std::ptr::copy_nonoverlapping(hash, digest.as_mut_ptr(), POW_DIGEST_LEN) };
    if check_target(&digest, &target) {
        1
    } else {
        0
    }
}

#[no_mangle]
pub extern "C" fn noidminer_le256_lt(a: *const u8, b: *const u8) -> c_int {
    if a.is_null() || b.is_null() {
        return 0;
    }
    unsafe {
        let mut ab = [0u8; POW_DIGEST_LEN];
        let mut bb = [0u8; POW_DIGEST_LEN];
        std::ptr::copy_nonoverlapping(a, ab.as_mut_ptr(), POW_DIGEST_LEN);
        std::ptr::copy_nonoverlapping(b, bb.as_mut_ptr(), POW_DIGEST_LEN);
        if le256_lt(&ab, &bb) {
            1
        } else {
            0
        }
    }
}

#[no_mangle]
pub extern "C" fn noidminer_validate_address(addr: *const c_char) -> c_int {
    if addr.is_null() {
        return 0;
    }
    let address = unsafe { std::ffi::CStr::from_ptr(addr) }
        .to_string_lossy()
        .into_owned();
    if validate_bech32m_o1(&address) {
        1
    } else {
        0
    }
}

/// Benchmark helper: hashes `iters` nonces, returns elapsed nanoseconds.
#[no_mangle]
pub extern "C" fn noidminer_bench(
    pow_fields_hex: *const c_char,
    nonce_start: c_ulonglong,
    iters: c_uint,
) -> c_int64 {
    if pow_fields_hex.is_null() || iters == 0 {
        return -1;
    }
    let fields_hex = unsafe { std::ffi::CStr::from_ptr(pow_fields_hex) }
        .to_string_lossy()
        .into_owned();
    let start = std::time::Instant::now();
    let mut out = vec![0u8; 32 * 64];
    let mut done = 0u32;
    while done < iters {
        let n = (iters - done).min(64);
        if batch_hash(&fields_hex, nonce_start + u64::from(done), n, &mut out).is_err() {
            return -2;
        }
        done += n;
    }
    i64::try_from(start.elapsed().as_nanos()).unwrap_or(-3)
}

/// Convenience: decode 64 hex chars into 32 raw bytes (used by tests).
#[no_mangle]
pub extern "C" fn noidminer_hex_decode_32(hex_str: *const c_char, out: *mut u8) -> c_int {
    if hex_str.is_null() || out.is_null() {
        return -1;
    }
    let s = unsafe { std::ffi::CStr::from_ptr(hex_str) }
        .to_string_lossy()
        .into_owned();
    match hex_to_bytes(&s) {
        Some(bytes) if bytes.len() == 32 => unsafe {
            std::ptr::copy_nonoverlapping(bytes.as_ptr(), out, 32);
            0
        },
        _ => -2,
    }
}
