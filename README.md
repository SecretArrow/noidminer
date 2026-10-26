# NoidMiner

[![CI](https://github.com/SecretArrow/noidminer/actions/workflows/ci.yml/badge.svg)](https://github.com/SecretArrow/noidminer/actions/workflows/ci.yml) [![E2E](https://github.com/SecretArrow/noidminer/actions/workflows/e2e.yml/badge.svg)](https://github.com/SecretArrow/noidminer/actions/workflows/e2e.yml) [![Release](https://github.com/SecretArrow/noidminer/actions/workflows/release.yml/badge.svg)](https://github.com/SecretArrow/noidminer/actions/workflows/release.yml)

**Miner Parano1d (NOID) — Poseidon2b — untuk Android.** Porting penuh dari miner PC
(CUDA) ke hardware Android: **CPU (ARM PMULL) + GPU (OpenCL)**, mining **background**
dengan foreground service, **UI/UX Material 3 yang didesain ulang total**, dan
**CI/CD GitHub Actions** lengkap (unit · lint · e2e · release · auto-fix).

> EN summary: a modern Android miner for the Parano1d (NOID) Poseidon2b PoW.
> CPU via the upstream Rust core (PMULL-accelerated), optional OpenCL GPU path
> gated by a runtime self-check, background mining via a foreground service,
> WorkManager cronjobs for self-healing, and a fully redesigned Compose Material 3
> UI ("Aurora Midnight" theme — glass cards, radial gauge, animated gradient CTA).
> CI builds fast, tests hard, and auto-fixes style issues.

---

## Fitur

| Area | Detail |
|---|---|
| **Algoritma** | Poseidon2b over GF(2^128) — identik dengan `noid_extminer` upstream (16 × 128-bit field, nonce di lane 10, tag `POWHDR__`) |
| **CPU** | Core Rust upstream dikompilasi via `cargo-ndk` untuk `arm64-v8a` + `armeabi-v7a` — akselerasi **PMULL** otomatis di SoC ARMv8 |
| **GPU** | Kernel OpenCL untuk Adreno/Mali — diaktifkan hanya jika lolos **self-check** (digest GPU harus identik dengan CPU); fallback CPU otomatis |
| **Fallback** | Engine **software murni Kotlin** — aplikasi tetap jalan di x86_64/emulator atau perangkat tanpa native lib |
| **Background** | Foreground service (`specialUse`) + notifikasi live (hashrate, shares) + aksi Pause/Resume/Stop + partial wake lock |
| **Cronjobs** | **WorkManager periodic** — `HealthCheckWorker` (15 min, restart service kalau OS kill), `HashrateLoggerWorker` (30 min, snapshot ke log), `GuardReevalWorker` (15 min, resume dari pause kalau kondisi sudah aman) |
| **Guard** | Thermal (pause di SEVERE, throttle di MODERATE), battery (< X% / hanya saat charging / battery saver), auto-resume |
| **Mode** | **Pool** (`parano1d-stratum-v1` TCP/TLS — InnovLab/RPlant/Alphapool) dan **Solo** (JSON-RPC node Parano1d) — **tanpa mode demo/simulasi**, semua hashrate/share nyata |
| **UI v2** | "Aurora Midnight" theme — glass-morphism cards, radial gauge, animated gradient CTA, live indicator, status pills, sparkline. Material 3 dengan brand identity (bukan dynamic color default) |
| **Hardware info** | SoC, cluster big/mid/LITTLE, RAM, ABI, GPU vendor/device, benchmark 3 detik |
| **Keamanan** | Wallet di `EncryptedSharedPreferences`, dikecualikan dari cloud backup; token tidak pernah masuk repo |

## Instalasi APK

1. Unduh APK sesuai ABI perangkat dari [Releases](https://github.com/SecretArrow/noidminer/releases):
   - `NoidMiner-vX.Y.Z-arm64.apk` — hampir semua HP modern (2016+)
   - `NoidMiner-vX.Y.Z-arm32.apk` — HP lawas 32-bit
   - `NoidMiner-vX.Y.Z-x86_64.apk` — emulator & tablet Intel/AMD
   - `NoidMiner-vX.Y.Z-x86.apk` — emulator lawas (jarang)
2. Install (aktifkan "Install unknown apps" bila diminta).
3. Buka NoidMiner → tab **Pools** → pilih mode **Pool** (Stratum URL + user
   `o1…address`) atau **Solo node** (URL RPC node Parano1d) → **Save**.
4. Tab **Mine** → **Start mining**. Mining berjalan di background dengan
   notifikasi live (Pause/Resume/Stop dari notifikasi).

### Troubleshooting Instalasi

| Gejala | Penyebab | Solusi |
|---|---|---|
| **"Aplikasi tidak diinstal"** | APK v1.0.0 lama **tidak ditandatangani** (bug build) atau konflik tanda tangan dengan build sebelumnya | **Uninstall NoidMiner lama dulu**, lalu install v1.1.0+ — APK sekarang signed v1+v2+v3 |
| "Aplikasi tidak diinstal" di HP lawas | APK berisi lib `arm64-v8a`; perangkat 32-bit (armeabi-v7a/x86) tidak didukung | Pakai **arm32 APK** (v1.2.0+ sudah split per ABI) atau gunakan perangkat arm64 |
| "Ada masalah saat menguraikan paket" | File korup — mis. artifact CI terunduh sebagai `.zip` lama di-rename `.apk` | Unduh langsung dari halaman **Releases**, cek SHA256SUMS.txt |
| Install OK tapi langsung close di Android 15+ | Perangkat 16 KB page-size + versi app lama (4 KB-aligned) | Gunakan **v1.1.0+** — native lib sudah 16 KB-aligned |

## CI/CD (GitHub Actions)

| Workflow | Trigger | Isi |
|---|---|---|
| **CI** | push/PR main | `ktlint` + `detekt` (statik, ±1 menit) ∥ **golden vectors Rust** + unit test JVM (incl. WorkManager tests via Robolectric) ∥ build Rust NDK **4 ABI sekaligus** + `assembleDebug` (split per ABI) + `lintDebug` → artifact APK debug |
| **E2E** | push main | Emulator ATD API 30 (`android-emulator-runner`) → `connectedDebugAndroidTest`: navigasi semua tab + **e2e stratum nyata** (server SV1 lokal: subscribe → notify → share → `mining.submit` diterima) |
| **Release** | tag `v*` | **Matrix 4 ABI paralel** — tiap job: build Rust untuk 1 target → `assembleRelease` (signed via secrets) → verify signature (hard gate) → upload artifact. Job `publish` download semua → **GitHub Release** + SHA256SUMS |
| **Auto-fix** | CI gagal di main / manual | `ktlint --format` → commit otomatis oleh `github-actions[bot]` |

Optimasi kecepatan build:
- Matrix 4 ABI **paralel** di release (4x faster daripada serial)
- Cache Gradle + Rust (registry/git/target/toolchain) per-target
- NDK dipin `26.3.11579264`, 4 ABI sekaligus di CI debug build (single cargo-ndk invocation)
- `concurrency.cancel-in-progress: true` di semua workflow
- Konfigurasi-cache Gradle

## Struktur Proyek

```
noidminer/
├── app/src/main/cpp/          # JNI bridge + OpenCL (dlopen, kernel + konstanta generated)
├── app/src/main/java/.../     # Kotlin: pow/ (engine, service, guards), data/
│                              # (stratum, rpc, settings, vault), ui/ (Compose M3 v2),
│                              # work/ (WorkManager cronjobs), di/ (ServiceLocator)
├── app/src/main/jniLibs/      # .so per-ABI (di-generate oleh scripts/build-rust.sh)
├── native-pow/                # Rust crate — wrapper upstream noid_poseidon2b + cffi
│   └── tests/                 # Golden vectors (validasi silang dengan Python & Kotlin)
├── scripts/build-rust.sh      # cargo-ndk → app/src/main/jniLibs (default: 4 ABI)
└── .github/workflows/         # ci.yml · e2e.yml · release.yml · autofix.yml
```

## Build Lokal (opsional)

CI selalu menjadi sumber kebenaran, tapi kalau mau build sendiri:

```bash
# prasyarat: JDK 17, Android SDK+NDK 26.3, rustup + 4 target Android
rustup target add aarch64-linux-android armv7a-linux-androideabi x86_64-linux-android i686-linux-android

# Build Rust untuk semua 4 ABI (default), lalu APK debug split per ABI
./scripts/build-rust.sh
./gradlew :app:assembleDebug

# Atau build 1 ABI saja:
./scripts/build-rust.sh aarch64-linux-android
./gradlew :app:assembleDebug -Pnoid.abis=arm64-v8a
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
- **Cronjobs**: WorkManager periodic survive reboot. `HealthCheckWorker`
  mendeteksi MinerService yang di-kill OS (Doze/OOM/app-standby bucket) dan
  restart otomatis. Tidak perlu BootReceiver untuk cronjob — WorkManager
  punya persistence sendiri. BootReceiver hanya untuk auto-start mining.
- Dev fee: tidak ada. Semua hashrate milik Anda.

## Lisensi

Apache-2.0. Dependensi PoW upstream mengikuti lisensinya masing-masing.
