// Golden-vector tests: validate the native engine against digests computed
// by the independent Python reference (scripts/gen_poseidon_constants.py)
// and mirrored in the Kotlin software engine. If this test fails in CI,
// either upstream changed or one of the backends drifted.
use noidminer_pow::{batch_hash, hash_pow, le256_lt};

include!("vectors_test_data.rs");

#[test]
fn golden_vectors_match_python_reference() {
    for (name, fields_hex, digests) in GOLDEN_VECTORS {
        for (nonce, expected_hex) in digests {
            let got = hash_pow(fields_hex, *nonce)
                .unwrap_or_else(|e| panic!("{name}: hash failed: {e}"));
            assert_eq!(&hex::encode(got), *expected_hex, "{name} nonce {nonce}");
        }
    }
}

#[test]
fn golden_batch_matches_single() {
    let (_, fields_hex, digests) = &GOLDEN_VECTORS[0];
    let count = digests.len() as u32;
    let mut out = vec![0u8; 32 * count as usize];
    batch_hash(fields_hex, 0, count, &mut out).expect("batch hash");
    for (i, (_, expected_hex)) in digests.iter().enumerate() {
        assert_eq!(&hex::encode(&out[i * 32..(i + 1) * 32]), *expected_hex);
    }
}

#[test]
fn le256_semantics() {
    let mut a = [0u8; 32];
    a[0] = 0x01;
    let b = [0u8; 32];
    assert!(!le256_lt(&a, &b));
    assert!(le256_lt(&b, &a));
}
