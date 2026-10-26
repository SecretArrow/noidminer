# NoidMiner

**Miner Parano1d (NOID) — Poseidon2b — untuk Android.** Porting penuh dari miner PC
(CUDA) ke hardware Android: **CPU (ARM PMULL) + GPU (OpenCL)**, mining **background**
dengan foreground service, UI/UX Material 3 yang didesain ulang total, dan
**CI/CD GitHub Actions** lengkap (unit · lint · e2e · release · auto-fix).

> EN summary: a modern Android miner for the Parano1d (NOID) Poseidon2b PoW.
> CPU via the upstream Rust core (PMULL-accelerated), optional OpenCL GPU path
> gated by a runtime self-check, background mining via a foreground service,
> and a fully redesigned Compose Material 3 UI. CI builds fast, tests hard,
> and auto-fixes style issues.

---

## Fitur

| Area | Detail |
|---|---|
| **Algoritma** | Poseidon2b over GF(2^128) — identik dengan `noid_extminer` upstream (16 × 128-bit field, nonce di lane 10, tag `POWHDR__`) |
| **CPU** | Core Rust upstream dikompilasi via `cargo-ndk` untuk `arm64-v8a` — akselerasi **PMULL** otomatis di SoC ARMv8 |
| **GPU** | Kernel OpenCL untuk Adreno/Mali — diaktifkan hanya jika lolos **self-check** (digest GPU harus identik dengan CPU); fallback CPU otomatis |
| **Fallback** | Engine **software murni Kotlin** — aplikasi tetap jalan di x86_64/emulator atau perangkat tanpa native lib |
| **Background** | Foreground service (`specialUse`) + notifikasi live (hashrate, shares) + aksi Pause/Resume/Stop + partial wake lock |
| **Guard** | Thermal (pause di SEVERE, throttle di MODERATE), battery (< X% / hanya saat charging / battery saver), auto-resume |
| **Mode** | **Demo** (mandiri, tanpa jaringan), **Pool** (Stratum SV1 TCP/TLS), **Solo** (JSON-RPC node Parano1d) |
| **Hardware info** | SoC, cluster big/mid/LITTLE, RAM, ABI, GPU vendor/device, benchmark 3 detik |
| **Keamanan** | Wallet di `EncryptedSharedPreferences`, dikecualikan dari cloud backup; token tidak pernah masuk repo |

## Instalasi APK

1. Unduh APK terbaru dari [Releases](https://github.com/SecretArrow/noidminer/releases)
   (`NoidMiner-vX.Y.Z-arm64.apk`, arm64-v8a).
2. Install (aktifkan "Install unknown apps" bila diminta).
3. Buka NoidMiner → tab **Mine** → **Start mining** (mode **Demo** langsung jalan).
4. Untuk mining nyata: tab **Pools** → pilih mode **Pool** (isi Stratum URL + user
   `o1…address`) atau **Solo node** (URL RPC node Parano1d Anda).

## CI/CD (GitHub Actions)

| Workflow | Trigger | Isi |
|---|---|---|
| **CI** | push/PR | `ktlint` + `detekt` (statik, ±1 menit) ∥ **golden vectors Rust** + unit test JVM ∥ build Rust NDK + `assembleDebug` + `lintDebug` → artifact APK debug |
| **E2E** | push main | Emulator ATD API 30 (`android-emulator-runner`) → `connectedDebugAndroidTest`: navigasi semua tab + start/stop mining demo |
| **Release** | tag `v*` | Build Rust arm64 → `assembleRelease` (signed via secrets) → **GitHub Release** + SHA256SUMS |
| **Auto-fix** | CI gagal di main / manual | `ktlint --format` → commit otomatis oleh `github-actions[bot]` |

Optimasi kecepatan build: paralel job, cache Gradle + Rust (registry/git/target/toolchain),
NDK dipin `26.3.11579264`, hanya `arm64-v8a`, concurrency cancel-in-progress,
Konfigurasi-cache Gradle.

## Struktur Proyek

```
noidminer/
├── app/src/main/cpp/          # JNI bridge + OpenCL (dlopen, kernel + konstanta generated)
├── app/src/main/java/.../     # Kotlin: pow/ (engine, service, guards), data/
│                              # (stratum, rpc, settings, vault), ui/ (Compose M3)
├── native-pow/                # Rust crate — wrapper upstream noid_poseidon2b + cffi
│   └── tests/                 # Golden vectors (validasi silang dengan Python & Kotlin)
├── scripts/build-rust.sh      # cargo-ndk → app/src/main/jniLibs
└── .github/workflows/         # ci.yml · e2e.yml · release.yml · autofix.yml
```

## Build Lokal (opsional)

CI selalu menjadi sumber kebenaran, tapi kalau mau build sendiri:

```bash
# prasyarat: JDK 17, Android SDK+NDK 26.3, rustup + target aarch64-linux-android
./scripts/build-rust.sh aarch64-linux-android
./gradlew :app:assembleDebug
```

## Catatan Teknis

- **Correctness chain**: referensi Python (`scripts/`) → vektor emas →
  diuji di **Rust** (CI, upstream crates asli) dan **Kotlin** (unit test JVM).
  Ketiganya harus menghasilkan digest byte-identik; jika tidak, CI gagal.
- **GPU self-check**: sebelum dipakai, kernel GPU diuji terhadap digest CPU
  pada 8 nonce probe. Gagal → GPU dimatikan untuk sesi tersebut (tanpa share salah).
- **Stratum**: klien SV1 standar (TCP/TLS, auto-reconnect). Codec job mencari
  blob `pow_fields` 256-hex + target 64-hex di params `mining.notify`; pool
  dengan skema non-standar akan terlihat jelas di layar Log.
- Dev fee: tidak ada. Semua hashrate milik Anda.

## Lisensi

Apache-2.0. Dependensi PoW upstream mengikuti lisensinya masing-masing.
