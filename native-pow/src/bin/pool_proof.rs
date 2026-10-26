// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 SecretArrow.
//
//! Real-pool mining proof — mines an ACTUAL accepted share on the live
//! InnovLab/RPlant "parano1d-stratum-v1" pools with a real payout address.
//!
//! This binary is the end-to-end proof that NoidMiner's protocol stack is
//! production-correct: subscribe → authorize(address) → notify(object) →
//! Poseidon2b over the 128-bit session lane → digest below share_target →
//! submit [job_id, nonce128, work_domain_id] → pool result = true.
//!
//! PROOF-RUN v2 (learned from the first live attempt):
//!  1. InnovLab sends mining.pause "canonical-tip-unverified" and may never
//!     send mining.resume — workers now ignore a pause after 20s so the
//!     budget is never burned sleeping.
//!  2. Pools drop long-lived TLS sessions ("peer closed without
//!     close_notify") — the miner now RECONNECTS with backoff and keeps
//!     mining until the deadline. Hashes/accepts accumulate across sessions.
//!  3. A single 4-core runner (~372 kH/s) statistically cannot hit a
//!     ~2^32-hash share target inside a 12-minute budget — the CI workflow
//!     now runs a MATRIX of independent sessions in parallel (each session
//!     gets its own lane/namespace from the pool, so ranges never collide).
//!
//! Built only under the `pool-proof` feature (never part of the Android
//! build): `cargo build --release --features pool-proof --bin pool_proof`.
//!
//! Usage: pool_proof <host> <port> <address> [max-seconds]

#![forbid(unsafe_code)]

use std::io::{Read, Write};
use std::sync::atomic::{AtomicBool, AtomicI64, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use noid_core::Block128;
use noid_poseidon2b::batch::FixedFieldNonceBatch;
use noid_poseidon2b::native::domain::TAG_POWHDR;

const NONCE_FIELD_INDEX: usize = 10;
const DIGEST_BATCH: usize = 512;
/// A pool pause older than this is ignored — InnovLab's
/// "canonical-tip-unverified" pause can persist forever without a
/// mining.resume, which burned 630s of the first proof budget.
const PAUSE_CAP_MS: i64 = 20_000;

// ---------------------------------------------------------------- JSON utils

/// Reads the first `"key":"<value>"` string member from a JSON fragment.
fn json_str<'a>(doc: &'a str, key: &str) -> Option<&'a str> {
    let token = format!("\"{key}\"");
    let mut from = 0usize;
    while let Some(pos) = doc[from..].find(&token) {
        let rest = &doc[from + pos + token.len()..];
        if let Some(after) = rest.trim_start().strip_prefix(':') {
            if let Some(val) = after.trim_start().strip_prefix('"') {
                if let Some(end) = val.find('"') {
                    return Some(&val[..end]);
                }
            }
        }
        from += pos + token.len();
    }
    None
}

fn json_bool_true(doc: &str, key: &str) -> bool {
    let token = format!("\"{key}\"");
    let Some(pos) = doc.find(&token) else {
        return false;
    };
    let rest = doc[pos + token.len()..].trim_start();
    let Some(after) = rest.strip_prefix(':') else {
        return false;
    };
    after.trim_start().starts_with("true")
}

// ------------------------------------------------------------- shared TLS IO

struct SharedTls {
    stream: Mutex<rustls::StreamOwned<rustls::ClientConnection, std::net::TcpStream>>,
}

impl SharedTls {
    fn send_line(&self, line: &str) -> anyhow::Result<()> {
        let mut guard = self.stream.lock().unwrap();
        guard.sock.set_write_timeout(Some(Duration::from_secs(10)))?;
        guard.write_all(line.as_bytes())?;
        guard.write_all(b"\n")?;
        guard.flush()?;
        Ok(())
    }

    /// Reads one newline-terminated line. Byte-wise reads keep the buffered
    /// plaintext INSIDE the rustls session (never lost between calls).
    /// Returns Ok(false) when the read timed out with nothing buffered —
    /// the caller's maintenance window. Ok(true) on a full line.
    fn read_line(&self, buf: &mut String) -> anyhow::Result<bool> {
        let mut guard = self.stream.lock().unwrap();
        guard.sock.set_read_timeout(Some(Duration::from_secs(5)))?;
        let mut out: Vec<u8> = Vec::with_capacity(1024);
        let mut byte = [0u8; 1];
        loop {
            match guard.read(&mut byte) {
                Ok(0) => {
                    // EOF
                    break;
                }
                Ok(_) => {
                    if byte[0] == b'\n' {
                        *buf = String::from_utf8_lossy(&out).into_owned();
                        return Ok(true);
                    }
                    out.push(byte[0]);
                    if out.len() > (1 << 20) {
                        anyhow::bail!("stratum line exceeds 1 MiB");
                    }
                }
                Err(e)
                    if e.kind() == std::io::ErrorKind::WouldBlock
                        || e.kind() == std::io::ErrorKind::TimedOut =>
                {
                    if out.is_empty() {
                        return Ok(false); // maintenance window
                    }
                    // Mid-line timeout is unexpected; surface it.
                    anyhow::bail!("read stalled mid-line");
                }
                Err(e) => return Err(e.into()),
            }
        }
        if out.is_empty() {
            anyhow::bail!("connection closed");
        }
        *buf = String::from_utf8_lossy(&out).into_owned();
        Ok(true)
    }
}

// ----------------------------------------------------------------- job model

#[derive(Clone)]
struct Job {
    id: String,
    fields_hex: String,
    target_le_hex: String,
    lane_hi_hex: String,
    work_domain_hex: String,
    height: u64,
}

// ---------------------------------------------------------------------- main

fn main() {
    if let Err(err) = run() {
        eprintln!("PROOF FAILED: {err:#}");
        std::process::exit(1);
    }
}

fn run() -> anyhow::Result<()> {
    let args: Vec<String> = std::env::args().collect();
    if args.len() < 4 {
        anyhow::bail!("usage: pool_proof <host> <port> <address> [max-seconds]");
    }
    let host = args[1].clone();
    let port: u16 = args[2].parse()?;
    let address = args[3].clone();
    let max_seconds: u64 = args.get(4).and_then(|s| s.parse().ok()).unwrap_or(3600);

    eprintln!("pool_proof: {host}:{port} address={address} budget={max_seconds}s");

    let deadline = Instant::now() + Duration::from_secs(max_seconds);
    let accepted_total = Arc::new(AtomicBool::new(false));
    let hashes_total = Arc::new(AtomicU64::new(0));
    let session_no = AtomicU64::new(0);
    let t0 = Instant::now();
    let mut last_error: Option<String> = None;

    // v2: reconnect loop — pools drop long-lived sessions; a dropped
    // connection must NOT end the proof. Hashes accumulate across sessions.
    while Instant::now() < deadline && !accepted_total.load(Ordering::SeqCst) {
        let n = session_no.fetch_add(1, Ordering::SeqCst) + 1;
        let remaining = max_seconds.saturating_sub(t0.elapsed().as_secs());
        eprintln!("--- session {n} (remaining budget {remaining}s) ---");
        match run_session(
            &host,
            port,
            &address,
            deadline,
            Arc::clone(&hashes_total),
            Arc::clone(&accepted_total),
        ) {
            Ok(SessionOutcome::Accepted) => break,
            Ok(SessionOutcome::Deadline) => break,
            Ok(SessionOutcome::Disconnected(err)) => {
                eprintln!("session {n} disconnected: {err} — reconnecting in 5s");
                last_error = Some(err);
                std::thread::sleep(Duration::from_secs(5));
            }
            Err(e) => {
                // Fatal protocol conditions (authorize rejected etc.).
                return Err(e);
            }
        }
    }

    if accepted_total.load(Ordering::SeqCst) {
        eprintln!(
            "PROOF OK: the pool accepted a REAL share for {address} (hashes={})",
            hashes_total.load(Ordering::Relaxed)
        );
        println!("PROOF OK");
        Ok(())
    } else if Instant::now() >= deadline {
        anyhow::bail!(
            "deadline reached without an accepted share — hashes={}",
            hashes_total.load(Ordering::Relaxed)
        )
    } else {
        anyhow::bail!(
            "no accepted share — hashes={} last_error={:?}",
            hashes_total.load(Ordering::Relaxed),
            last_error
        )
    }
}

enum SessionOutcome {
    Accepted,
    Deadline,
    Disconnected(String),
}

/// One full stratum session: connect → subscribe → authorize → mine.
/// Workers run until [deadline], [accepted] flips, or the connection dies.
fn run_session(
    host: &str,
    port: u16,
    address: &str,
    deadline: Instant,
    hashes_total: Arc<AtomicU64>,
    accepted: Arc<AtomicBool>,
) -> anyhow::Result<SessionOutcome> {
    let mut root_store = rustls::RootCertStore::empty();
    root_store.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
    let config = rustls::ClientConfig::builder()
        .with_root_certificates(root_store)
        .with_no_client_auth();
    let server = rustls::pki_types::ServerName::try_from(host.to_string())
        .map_err(|e| anyhow::anyhow!("bad server name: {e}"))?;
    let conn = rustls::ClientConnection::new(Arc::new(config), server)?;
    let tcp = std::net::TcpStream::connect((host, port))?;
    tcp.set_nodelay(true)?;
    let tls = Arc::new(SharedTls {
        stream: Mutex::new(rustls::StreamOwned::new(conn, tcp)),
    });

    let next_id = AtomicU64::new(10);
    let mut authorize_id: Option<u64> = None;
    let mut subscribed = false;

    // Send mining.subscribe immediately — pools only talk to subscribers.
    let sub_id = next_id.fetch_add(1, Ordering::SeqCst);
    tls.send_line(&format!(
        "{{\"id\":{sub_id},\"method\":\"mining.subscribe\",\"params\":[\"NoidMiner/1.3.4\"]}}"
    ))?;
    eprintln!("subscribe sent (id={sub_id})");

    let current: Arc<Mutex<Option<Job>>> = Arc::new(Mutex::new(None));
    let session_stop = Arc::new(AtomicBool::new(false));
    let pool_paused_since_ms = Arc::new(AtomicI64::new(0));
    let (submit_tx, submit_rx) = std::sync::mpsc::channel::<(String, String, String)>();

    // One thread per core, alive for this session only.
    let threads = std::thread::available_parallelism()
        .map(|n| n.get())
        .unwrap_or(2)
        .min(8);
    for tid in 0..threads {
        let jobs = Arc::clone(&current);
        let stop = Arc::clone(&session_stop);
        let hashes = Arc::clone(&hashes_total);
        let paused_since = Arc::clone(&pool_paused_since_ms);
        let accepted = Arc::clone(&accepted);
        let tx = submit_tx.clone();
        std::thread::spawn(move || {
            miner_worker(tid, jobs, stop, hashes, tx, paused_since, accepted)
        });
    }

    let started = Instant::now();
    let mut buf = String::new();
    let mut last_report = Instant::now();
    let mut session_hashes_base = hashes_total.load(Ordering::Relaxed);
    let mut read_errors = 0u32;

    let outcome = loop {
        if accepted.load(Ordering::SeqCst) {
            break SessionOutcome::Accepted;
        }
        if Instant::now() >= deadline {
            break SessionOutcome::Deadline;
        }

        buf.clear();
        let got = match tls.read_line(&mut buf) {
            Ok(true) => {
                read_errors = 0;
                true
            }
            Ok(false) => {
                read_errors = 0;
                false
            }
            Err(e) => {
                read_errors += 1;
                if read_errors >= 3 {
                    break SessionOutcome::Disconnected(format!("{e:#}"));
                }
                eprintln!("read error: {e}");
                false
            }
        };

        if got {
            let line = buf.trim().to_string();
            if !line.is_empty() {
                if line.contains("\"method\":\"mining.notify\"") {
                    handle_notify(&line, &current);
                } else if line.contains("\"method\":\"mining.pause\"") {
                    eprintln!("pool paused: {}", json_str(&line, "reason").unwrap_or("?"));
                    pool_paused_since_ms.store(
                        std::time::SystemTime::now()
                            .duration_since(std::time::UNIX_EPOCH)
                            .unwrap_or_default()
                            .as_millis() as i64,
                        Ordering::SeqCst,
                    );
                } else if line.contains("\"method\":\"mining.resume\"") {
                    eprintln!("pool resumed");
                    pool_paused_since_ms.store(0, Ordering::SeqCst);
                } else if !subscribed && line.contains("session_namespace") {
                    let ns = json_str(&line, "session_namespace").unwrap_or("");
                    eprintln!("subscribed: session_namespace={ns}");
                    subscribed = true;
                    let id = next_id.fetch_add(1, Ordering::SeqCst);
                    authorize_id = Some(id);
                    tls.send_line(&format!(
                        "{{\"id\":{id},\"method\":\"mining.authorize\",\"params\":[\"{address}\"]}}"
                    ))?;
                } else if authorize_id.is_some()
                    && line.contains(&format!("\"id\":{}", authorize_id.unwrap()))
                {
                    let ok = json_bool_true(&line, "result");
                    eprintln!("authorize result: {ok}");
                    if !ok {
                        session_stop.store(true, Ordering::SeqCst);
                        return Err(anyhow::anyhow!(
                            "pool rejected payout address {address}"
                        ));
                    }
                    authorize_id = None;
                } else if line.contains("mining.submit")
                    || (authorize_id.is_none() && subscribed && line.contains("\"result\""))
                {
                    let ok = line.contains("\"result\":true");
                    eprintln!("submit response: {}", &line[..line.len().min(200)]);
                    if ok {
                        accepted.store(true, Ordering::SeqCst);
                        break SessionOutcome::Accepted;
                    }
                } else {
                    eprintln!("pool: {}", &line[..line.len().min(140)]);
                }
            }
        }

        // Drain worker-found shares and submit over the same connection.
        while let Ok((job_id, nonce_hex, domain)) = submit_rx.try_recv() {
            let id = next_id.fetch_add(1, Ordering::SeqCst);
            eprintln!("submitting share nonce={nonce_hex}");
            if let Err(e) = tls.send_line(&format!(
                "{{\"id\":{id},\"method\":\"mining.submit\",\"params\":[\"{job_id}\",\"{nonce_hex}\",\"{domain}\"]}}"
            )) {
                eprintln!("submit send failed: {e:#}");
                break SessionOutcome::Disconnected(format!("submit send: {e:#}"));
            }
        }

        if last_report.elapsed() > Duration::from_secs(30) {
            let total = hashes_total.load(Ordering::Relaxed);
            let dt = last_report.elapsed().as_secs_f64();
            let rate = (total - session_hashes_base) as f64 / dt.max(0.001);
            session_hashes_base = total;
            last_report = Instant::now();
            eprintln!(
                "progress: hashes={total} rate={:.0} H/s elapsed={}s accepted={}",
                rate,
                started.elapsed().as_secs(),
                accepted.load(Ordering::SeqCst) as u64,
            );
        }
    };

    session_stop.store(true, Ordering::SeqCst);
    Ok(outcome)
}

fn handle_notify(line: &str, current: &Arc<Mutex<Option<Job>>>) {
    let Some(job_id) = json_str(line, "job_id") else {
        return;
    };
    let Some(fields) = json_str(line, "pow_fields_hex") else {
        eprintln!("notify missing pow_fields_hex — skipping");
        return;
    };
    if fields.len() != 512 {
        eprintln!(
            "notify pow_fields len {} (expected 512) — skipping",
            fields.len()
        );
        return;
    }
    let job = Job {
        id: job_id.to_string(),
        fields_hex: fields.to_string(),
        target_le_hex: json_str(line, "share_target_hex").unwrap_or("").to_string(),
        lane_hi_hex: json_str(line, "nonce_prefix_hex").unwrap_or("").to_string(),
        work_domain_hex: json_str(line, "work_domain_id").unwrap_or("").to_string(),
        height: line
            .find("\"height\":")
            .and_then(|p| {
                line[p + 9..]
                    .chars()
                    .take_while(|c| c.is_ascii_digit())
                    .collect::<String>()
                    .parse()
                    .ok()
            })
            .unwrap_or(0),
    };
    eprintln!(
        "job h={} id={}..{} target={}… prefix={}",
        job.height,
        &job.id[..job.id.len().min(8)],
        &job.id[job.id.len().saturating_sub(8)..],
        &job.target_le_hex[..job.target_le_hex.len().min(8)],
        job.lane_hi_hex,
    );
    *current.lock().unwrap() = Some(job);
}

/// Deterministic per-worker counter start (spread across the u64 space).
fn worker_counter_start(tid: usize) -> u64 {
    let nanos = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .subsec_nanos() as u64;
    (nanos & 0x00FF_FFFF_FFFF_FFF0).wrapping_add((tid as u64) << 40)
}

/// One persistent miner thread for a single session. Watches the job slot;
/// rebuilds its hasher when the job id changes; exits on session stop or a
/// globally-accepted share.
fn miner_worker(
    tid: usize,
    jobs: Arc<Mutex<Option<Job>>>,
    stop: Arc<AtomicBool>,
    hashes_total: Arc<AtomicU64>,
    submit_tx: std::sync::mpsc::Sender<(String, String, String)>,
    pool_paused_since_ms: Arc<AtomicI64>,
    accepted: Arc<AtomicBool>,
) {
    let mut hasher: Option<FixedFieldNonceBatch> = None;
    let mut current_id = String::new();
    let mut target = [0xffu8; 32];
    let mut lane_hi: u128 = 0;
    let mut job_id = String::new();
    let mut work_domain = String::new();
    let mut counter = worker_counter_start(tid);
    let mut digests = vec![[0u8; 32]; DIGEST_BATCH];
    let mut pause_ignored_logged = false;

    loop {
        if stop.load(Ordering::SeqCst) || accepted.load(Ordering::SeqCst) {
            return;
        }
        // Honor the pool pause, but only while it is FRESH. A pause older
        // than PAUSE_CAP_MS without a matching mining.resume is treated as
        // pool-side noise (canonical-tip-unverified oscillation) and mining
        // resumes — a budget burned sleeping can never find a share.
        let paused_ms = pool_paused_since_ms.load(Ordering::SeqCst);
        if paused_ms > 0 {
            let now_ms = std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap_or_default()
                .as_millis() as i64;
            if now_ms - paused_ms < PAUSE_CAP_MS {
                std::thread::sleep(Duration::from_millis(500));
                continue;
            }
            if !pause_ignored_logged {
                pause_ignored_logged = true;
                if tid == 0 {
                    eprintln!(
                        "pause older than {}s — mining anyway (pool never resumed)",
                        PAUSE_CAP_MS / 1000
                    );
                }
            }
        }
        // Job slot -> (re)build on change.
        let slot = jobs.lock().unwrap().clone();
        if let Some(job) = slot {
            if job.id != current_id {
                let Ok(field_bytes) = hex::decode(&job.fields_hex) else {
                    std::thread::sleep(Duration::from_millis(300));
                    continue;
                };
                if field_bytes.len() != 256 {
                    std::thread::sleep(Duration::from_millis(300));
                    continue;
                }
                let Ok(target_bytes) = hex::decode(&job.target_le_hex) else {
                    std::thread::sleep(Duration::from_millis(300));
                    continue;
                };
                if target_bytes.len() != 32 {
                    std::thread::sleep(Duration::from_millis(300));
                    continue;
                }
                let mut fields = [Block128::from(0u128); 16];
                for (i, chunk) in field_bytes.chunks_exact(16).enumerate() {
                    fields[i] = Block128::from(u128::from_le_bytes(chunk.try_into().unwrap()));
                }
                hasher = Some(FixedFieldNonceBatch::new(TAG_POWHDR, &fields, NONCE_FIELD_INDEX));
                target.copy_from_slice(&target_bytes);
                lane_hi = hex::decode(&job.lane_hi_hex)
                    .ok()
                    .filter(|b| b.len() == 8)
                    .map(|b| u128::from(u64::from_le_bytes(b.try_into().unwrap())))
                    .unwrap_or(0);
                job_id = job.id.clone();
                work_domain = job.work_domain_hex.clone();
                current_id = job.id;
                counter = worker_counter_start(tid);
                pause_ignored_logged = false;
                if tid == 0 {
                    eprintln!(
                        "worker 0 switched to job {job_id} (prefix={:016x})",
                        lane_hi as u64
                    );
                }
            }
        } else {
            std::thread::sleep(Duration::from_millis(200));
            continue;
        }
        let Some(h) = hasher.as_mut() else { continue };

        let lane = lane_hi | u128::from(counter);
        h.hash_into(lane, &mut digests);
        for (i, digest) in digests.iter().enumerate() {
            if noid_pow::le256_lt(digest, &target) {
                let nonce_counter = counter.wrapping_add(i as u64);
                let nonce_hex =
                    hex::encode((lane_hi | u128::from(nonce_counter)).to_le_bytes());
                eprintln!(
                    "share found counter={nonce_counter} hash={}…",
                    hex::encode(&digest[..6]),
                );
                let _ = submit_tx.send((job_id.clone(), nonce_hex, work_domain.clone()));
            }
        }
        hashes_total.fetch_add(DIGEST_BATCH as u64, Ordering::Relaxed);
        counter = counter.wrapping_add(DIGEST_BATCH as u64);
    }
}
