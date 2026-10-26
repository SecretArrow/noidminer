// GENERATED-safe: OpenCL kernel source, embedded via opencl_kernel.cl.h.
// Poseidon2b flat-basis leaf sponge over GF(2^128) (GCM polynomial).
// Host does tower<->flat conversion; kernel does absorb + permutation.
// One work-item hashes one leaf (one nonce).

typedef struct { unsigned long lo, hi; } u128v;

inline u128v u128_xor(u128v a, u128v b) { return (u128v){a.lo ^ b.lo, a.hi ^ b.hi}; }

// Multiply by x (shift left, reduce degree-128 term by the GCM poly).
inline u128v xtime(u128v a) {
    unsigned long carry = a.hi >> 63;
    u128v r;
    r.lo = (a.lo << 1) ^ (carry ? 0x87UL : 0UL);
    r.hi = (a.hi << 1) | (a.lo >> 63);
    return r;
}

// Carry-less multiply reduced mod x^128 + x^7 + x^2 + x + 1 (shift-add).
inline u128v gcm_mul(u128v a, u128v b) {
    u128v acc = {0UL, 0UL};
    for (int i = 0; i < 128; ++i) {
        if (b.lo & 1UL) acc = u128_xor(acc, a);
        a = xtime(a);
        b.lo = (b.lo >> 1) | (b.hi << 63);
        b.hi = b.hi >> 1;
    }
    return acc;
}

inline u128v sbox_x7(u128v x) {
    u128v x2 = gcm_mul(x, x);
    u128v x4 = gcm_mul(x2, x2);
    u128v x6 = gcm_mul(x, x2);
    return gcm_mul(x6, x4);
}

inline void mds_full(u128v* s) {
    u128v inp[4];
    for (int i = 0; i < 4; ++i) inp[i] = s[i];
    for (int i = 0; i < 4; ++i) {
        u128v acc = {0UL, 0UL};
        for (int j = 0; j < 4; ++j) {
            if (MDS_FULL_ONE[i * 4 + j]) {
                acc = u128_xor(acc, inp[j]);
            } else {
                acc = u128_xor(acc, gcm_mul(inp[j], MDS_FULL_M[i * 4 + j]));
            }
        }
        s[i] = acc;
    }
}

inline void mds_partial(u128v* s) {
    u128v inp[4];
    for (int i = 0; i < 4; ++i) inp[i] = s[i];
    for (int i = 0; i < 4; ++i) {
        u128v acc = {0UL, 0UL};
        for (int j = 0; j < 4; ++j) {
            if (MDS_PARTIAL_ONE[i * 4 + j]) {
                acc = u128_xor(acc, inp[j]);
            } else {
                acc = u128_xor(acc, gcm_mul(inp[j], MDS_PARTIAL_M[i * 4 + j]));
            }
        }
        s[i] = acc;
    }
}

inline void permute(u128v* s) {
    mds_full(s);
    for (int r = 0; r < 66; ++r) {
        if (r < 4 || r >= 62) {
            for (int i = 0; i < 4; ++i) {
                s[i] = sbox_x7(u128_xor(s[i], RC[i * 66 + r]));
            }
            mds_full(s);
        } else {
            s[0] = sbox_x7(u128_xor(s[0], RC[r]));
            mds_partial(s);
        }
    }
}

__kernel void noid_leaf_sponge(__global const unsigned long* leaves,
                               __global const unsigned long* iv,
                               __global unsigned long* out,
                               const unsigned int count) {
    const unsigned int gid = (unsigned int)get_global_id(0);
    if (gid >= count) return;
    __global const unsigned long* leaf = leaves + (size_t)gid * 32;
    u128v s0 = {0UL, 0UL};
    u128v s1 = {0UL, 0UL};
    u128v s2 = {iv[0], iv[1]};
    u128v s3 = {iv[2], iv[3]};
    for (int b = 0; b < 8; ++b) {
        s0 = u128_xor(s0, (u128v){leaf[b * 4 + 0], leaf[b * 4 + 1]});
        s1 = u128_xor(s1, (u128v){leaf[b * 4 + 2], leaf[b * 4 + 3]});
        u128v st[4] = {s0, s1, s2, s3};
        permute(st);
        s0 = st[0];
        s1 = st[1];
        s2 = st[2];
        s3 = st[3];
    }
    __global unsigned long* o = out + (size_t)gid * 8;
    o[0] = s0.lo;
    o[1] = s0.hi;
    o[2] = s1.lo;
    o[3] = s1.hi;
}
