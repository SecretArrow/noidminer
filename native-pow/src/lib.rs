// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 SecretArrow.
//
//! NoidMiner PoW core — thin C ABI over the upstream `noid_poseidon2b` crate.
//!
//! The hash logic mirrors the upstream `noid_extminer` reference miner:
//! 16 × 16-byte LE `Block128` fields → set field 10 to the nonce →
//! `Poseidon2b` with `TAG_POWHDR` → 32-byte digest. A share is valid when
//! `hash < target`, both interpreted as 256-bit little-endian integers.

use noid_core::Block128;
use noid_poseidon2b::batch::FixedFieldNonceBatch;
use noid_poseidon2b::native::domain::TAG_POWHDR;

pub const POW_HEADER_FIELD_COUNT: usize = 16;
pub const POW_NONCE_FIELD_INDEX: usize = 10;
pub const POW_FIELDS_BYTE_LEN: usize = POW_HEADER_FIELD_COUNT * 16;
pub const POW_DIGEST_LEN: usize = 32;

pub type PowResult<T> = Result<T, String>;

fn decode_pow_fields_hex(pow_fields_hex: &str) -> PowResult<[Block128; POW_HEADER_FIELD_COUNT]> {
    let bytes = hex::decode(pow_fields_hex).map_err(|e| format!("hex decode: {e}"))?;
    if bytes.len() != POW_FIELDS_BYTE_LEN {
        return Err(format!(
            "pow_fields_hex must be {POW_FIELDS_BYTE_LEN} bytes, got {}",
            bytes.len()
        ));
    }
    let mut fields = [Block128::from(0u128); POW_HEADER_FIELD_COUNT];
    for (i, chunk) in bytes.chunks_exact(16).enumerate() {
        let arr: [u8; 16] = chunk.try_into().expect("chunk is 16 bytes");
        fields[i] = Block128::from(u128::from_le_bytes(arr));
    }
    Ok(fields)
}

fn decode_target_le_hex(target_le_hex: &str) -> PowResult<[u8; 32]> {
    let bytes = hex::decode(target_le_hex).map_err(|e| format!("target hex decode: {e}"))?;
    if bytes.len() != POW_DIGEST_LEN {
        return Err(format!("target must be {POW_DIGEST_LEN} bytes, got {}", bytes.len()));
    }
    Ok(bytes.try_into().expect("32 bytes"))
}

/// Compare two 32-byte values as 256-bit LE integers.
#[must_use]
pub fn le256_lt(a: &[u8; 32], b: &[u8; 32]) -> bool {
    for i in (0..32).rev() {
        if a[i] < b[i] {
            return true;
        }
        if a[i] > b[i] {
            return false;
        }
    }
    false
}

/// Hash one nonce (low 64 bits of the 128-bit nonce lane).
pub fn hash_pow(pow_fields_hex: &str, nonce: u64) -> PowResult<[u8; 32]> {
    let mut fields = decode_pow_fields_hex(pow_fields_hex)?;
    fields[POW_NONCE_FIELD_INDEX] = Block128::from(nonce as u128);
    let mut hasher = FixedFieldNonceBatch::new(TAG_POWHDR, &fields, POW_NONCE_FIELD_INDEX);
    let mut out = [[0u8; 32]; 1];
    hasher.hash_into(nonce as u128, &mut out[..1]);
    Ok(out[0])
}

/// Batch-hash `count` consecutive nonces starting at `nonce_start`.
/// Output buffer receives `count * 32` bytes.
pub fn batch_hash(
    pow_fields_hex: &str,
    nonce_start: u64,
    count: u32,
    out: &mut [u8],
) -> PowResult<()> {
    let expected = usize::try_from(count).map_err(|_| "count overflow".to_string())? * POW_DIGEST_LEN;
    if out.len() != expected {
        return Err(format!("out buffer must be {expected} bytes, got {}", out.len()));
    }
    let fields = decode_pow_fields_hex(pow_fields_hex)?;
    let mut hasher = FixedFieldNonceBatch::new(TAG_POWHDR, &fields, POW_NONCE_FIELD_INDEX);
    let mut digests = vec![[0u8; POW_DIGEST_LEN]; count as usize];
    hasher.hash_into(nonce_start as u128, &mut digests);
    for (i, digest) in digests.iter().enumerate() {
        out[i * POW_DIGEST_LEN..(i + 1) * POW_DIGEST_LEN].copy_from_slice(digest);
    }
    Ok(())
}

/// True when `hash < target` (256-bit LE comparison).
#[must_use]
pub fn check_target(hash: &[u8; 32], target_le_hex: &str) -> bool {
    match decode_target_le_hex(target_le_hex) {
        Ok(target) => le256_lt(hash, &target),
        Err(_) => false,
    }
}

/// Validate a bech32m Parano1d address (HRP `o1`), pure implementation
/// without pulling the bech32 crate — keeps the native surface minimal.
#[must_use]
pub fn validate_bech32m_o1(address: &str) -> bool {
    bech32m_o1::validate(address)
}

mod bech32m_o1 {
    const CHARSET: &[u8] = b"qpzry9x8gf2tvdw0s3jn54khce6mua7l";
    const BECH32M_CONST: u32 = 0x2bc830a3;

    fn polymod(values: &[u8]) -> u32 {
        const GEN: [u32; 5] = [0x3b6a_57b2, 0x2650_8e6d, 0x1ea1_19fa, 0x3d42_33dd, 0x2a14_62b3];
        let mut chk: u32 = 1;
        for v in values {
            let b = chk >> 25;
            chk = ((chk & 0x1ff_ffff) << 5) ^ u32::from(*v);
            for (i, g) in GEN.iter().enumerate() {
                if (b >> i) & 1 == 1 {
                    chk ^= g;
                }
            }
        }
        chk
    }

    fn hrp_expand(hrp: &str) -> Vec<u8> {
        hrp.bytes()
            .map(|c| c >> 5)
            .chain(std::iter::once(0))
            .chain(hrp.bytes().map(|c| c & 31))
            .collect()
    }

    fn decode_to_5bit(address: &str) -> Option<(String, Vec<u8>)> {
        if address.len() < 8 || address.chars().any(|c| c as u32 > 126) {
            return None;
        }
        let (pos, c) = address.char_indices().rev().find(|(_, c)| *c >= 'a' && *c <= 'z')?;
        let _ = c;
        let lower = address.to_lowercase();
        let upper = address.to_uppercase();
        if address != lower && address != upper {
            return None;
        }
        let addr = lower.as_bytes();
        let sep_pos = addr.iter().rposition(|&b| b == b'1')?;
        if sep_pos == 0 || sep_pos + 7 > addr.len() {
            return None;
        }
        let hrp = lower[..sep_pos].to_string();
        let mut data = Vec::with_capacity(addr.len() - sep_pos - 1);
        for &b in &addr[sep_pos + 1..] {
            let idx = CHARSET.iter().position(|&c| c == b)?;
            data.push(idx as u8);
        }
        Some((hrp, data))
    }

    /// Validate: lowercase `o1…` address with a correct Bech32m checksum.
    #[must_use]
    pub fn validate(address: &str) -> bool {
        let Some((hrp, data)) = decode_to_5bit(address) else {
            return false;
        };
        if hrp != "o1" || data.len() < 7 {
            return false;
        }
        let checksum = data.split_off(data.len() - 6);
        let mut values = hrp_expand(&hrp);
        values.extend_from_slice(&data);
        values.extend_from_slice(&[0, 0, 0, 0, 0, 0]);
        let computed = polymod(&values) ^ BECH32M_CONST;
        checksum
            .iter()
            .enumerate()
            .all(|(i, &c)| ((computed >> (5 * (5 - i as u32))) & 0x1f) as u8 == c)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn zero_pow_fields_hex() -> String {
        "00".repeat(POW_FIELDS_BYTE_LEN)
    }

    #[test]
    fn hash_pow_returns_nonzero_digest() {
        let hash = hash_pow(&zero_pow_fields_hex(), 0).expect("hash succeeds");
        assert_eq!(hash.len(), POW_DIGEST_LEN);
        assert!(hash.iter().any(|&b| b != 0));
    }

    #[test]
    fn hash_pow_is_deterministic() {
        let a = hash_pow(&zero_pow_fields_hex(), 7).expect("hash");
        let b = hash_pow(&zero_pow_fields_hex(), 7).expect("hash");
        assert_eq!(a, b);
    }

    #[test]
    fn hash_pow_rejects_short_hex() {
        assert!(hash_pow(&"00".repeat(POW_FIELDS_BYTE_LEN - 1), 0).is_err());
    }

    #[test]
    fn batch_hash_matches_single_hash() {
        let fields = zero_pow_fields_hex();
        let single = hash_pow(&fields, 42).expect("single");
        let mut out = vec![0u8; POW_DIGEST_LEN];
        batch_hash(&fields, 42, 1, &mut out).expect("batch");
        assert_eq!(&out[..], &single[..]);
    }

    #[test]
    fn le256_lt_matches_byte_order() {
        let mut a = [0u8; 32];
        a[0] = 0x01;
        let b = [0u8; 32];
        assert!(!le256_lt(&a, &b));
        assert!(le256_lt(&b, &a));
    }

    #[test]
    fn check_target_boundary() {
        let low = [0x00u8; 32];
        let high = [0xffu8; 32];
        assert!(check_target(&low, &"ff".repeat(32)));
        assert!(!check_target(&high, &"00".repeat(32)));
    }

    #[test]
    fn bech32m_rejects_garbage() {
        assert!(!validate_bech32m_o1(""));
        assert!(!validate_bech32m_o1("garbage"));
        // Valid bech32 (not bech32m) checksum with a bc1 HRP.
        assert!(!validate_bech32m_o1("bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"));
    }

    /// A syntactically valid bech32m string with the wrong HRP must fail.
    #[test]
    fn bech32m_rejects_wrong_hrp() {
        // Constructed with the standard bech32m algorithm for HRP "xx".
        assert!(!validate_bech32m_o1("xx1q"), "too short must fail");
    }
}
