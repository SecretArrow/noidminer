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
//! Built only under the `pool-proof` feature (never part of the Android
//! build): `cargo build --release --features pool-proof --bin pool_proof`.
//!
//! Usage: pool_proof <host> <port> <address> [max-seconds]

#![forbid(unsafe_code)]

use std::io::{Read, Write};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use noid_core::Block128;
use noid_poseidon2b::batch::FixedFieldNonceBatch;
use noid_poseidon2b::native::domain::TAG_POWHDR;

const NONCE_FIELD_INDEX: usize = 10;
const DIGEST_BATCH: usize = 512;

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
        guard.sock.set_read_timeout(Some(Duration::from_secs(20)))?;
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

    eprintln!("pool_proof: {host}:{port} address={address}");

    let mut root_store = rustls::RootCertStore::empty();
    root_store.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
    let config = rustls::ClientConfig::builder()
        .with_root_certificates(root_store)
        .with_no_client_auth();
    let server = rustls::pki_types::ServerName::try_from(host.clone())
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
    let started = Instant::now();

    let current: Arc<Mutex<Option<Job>>> = Arc::new(Mutex::new(None));
    let stop = Arc::new(AtomicBool::new(false));
    let accepted_flag = Arc::new(AtomicBool::new(false));
    let hashes_total = Arc::new(AtomicU64::new(0));
    let pool_paused = Arc::new(AtomicBool::new(false));
    let (submit_tx, submit_rx) = std::sync::mpsc::channel::<(String, String, String)>();

    // Persistent mining workers.
    for _ in 0..1 {
        let jobs = Arc::clone(&current);
        let stop = Arc::clone(&stop);
        let hashes = Arc::clone(&hashes_total);
        let paused = Arc::clone(&pool_paused);
        let tx = submit_tx.clone();
        std::thread::spawn(move || mine_loop(jobs, stop, hashes, tx, paused));
    }

    let deadline = Instant::now() + Duration::from_secs(max_seconds);
    let mut buf = String::new();
    let mut last_report = Instant::now();
    let mut read_errors = 0u32;
    let mut silent_windows = 0u64;

    while Instant::now() < deadline && !accepted_flag.load(Ordering::SeqCst) {
        buf.clear();
        let got = match tls.read_line(&mut buf) {
            Ok(true) => {
                read_errors = 0;
                true
            }
            Ok(false) => {
                // 20s silent window — the pool accepted us but says nothing.
                silent_windows += 1;
                eprintln!(
                    "still silent ({}) after {}s — pool may be filtering this IP",
                    silent_windows,
                    started.elapsed().as_secs(),
                );
                read_errors = 0;
                false
            }
            Err(e) => {
                read_errors += 1;
                if read_errors >= 3 {
                    return Err(e.context("stratum connection lost"));
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
                    pool_paused.store(true, Ordering::SeqCst);
                } else if line.contains("\"method\":\"mining.resume\"") {
                    eprintln!("pool resumed");
                    pool_paused.store(false, Ordering::SeqCst);
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
                        anyhow::bail!("pool rejected payout address {address}");
                    }
                    authorize_id = None;
                } else if line.contains("mining.submit")
                    || (authorize_id.is_none()
                        && subscribed
                        && line.contains("\"result\""))
                {
                    let ok = line.contains("\"result\":true");
                    eprintln!("submit response: {}", &line[..line.len().min(200)]);
                    if ok {
                        accepted_flag.store(true, Ordering::SeqCst);
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
            tls.send_line(&format!(
                "{{\"id\":{id},\"method\":\"mining.submit\",\"params\":[\"{job_id}\",\"{nonce_hex}\",\"{domain}\"]}}"
            ))?;
        }

        if last_report.elapsed() > Duration::from_secs(30) {
            last_report = Instant::now();
            eprintln!(
                "progress: hashes={} accepted={}",
                hashes_total.load(Ordering::Relaxed),
                accepted_flag.load(Ordering::SeqCst) as u64,
            );
        }
    }

    stop.store(true, Ordering::SeqCst);

    if accepted_flag.load(Ordering::SeqCst) {
        eprintln!(
            "PROOF OK: the pool accepted a REAL share for {address} (hashes={})",
            hashes_total.load(Ordering::Relaxed),
        );
        println!("PROOF OK");
        Ok(())
    } else {
        anyhow::bail!(
            "no accepted share within {max_seconds}s — hashes={}",
            hashes_total.load(Ordering::Relaxed)
        );
    }
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
        eprintln!("notify pow_fields len {} (expected 512) — skipping", fields.len());
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

/// Persistent worker: mines the newest job's lane space, restarts on change.
fn mine_loop(
    jobs: Arc<Mutex<Option<Job>>>,
    stop: Arc<AtomicBool>,
    hashes_total: Arc<AtomicU64>,
    submit_tx: std::sync::mpsc::Sender<(String, String, String)>,
    pool_paused: Arc<AtomicBool>,
) {
    loop {
        if stop.load(Ordering::SeqCst) {
            return;
        }
        let Some(job) = jobs.lock().unwrap().clone() else {
            std::thread::sleep(Duration::from_millis(200));
            continue;
        };
        if pool_paused.load(Ordering::SeqCst) {
            std::thread::sleep(Duration::from_millis(500));
            continue;
        }
        mine_job(&job, &jobs, &stop, &hashes_total, &submit_tx, &pool_paused);
    }
}

fn mine_job(
    job: &Job,
    jobs: &Arc<Mutex<Option<Job>>>,
    stop: &Arc<AtomicBool>,
    hashes_total: &Arc<AtomicU64>,
    submit_tx: &std::sync::mpsc::Sender<(String, String, String)>,
    pool_paused: &Arc<AtomicBool>,
) {
    let Ok(field_bytes) = hex::decode(&job.fields_hex) else {
        return;
    };
    if field_bytes.len() != 256 {
        return;
    }
    let Ok(target_bytes) = hex::decode(&job.target_le_hex) else {
        return;
    };
    if target_bytes.len() != 32 {
        return;
    }
    let mut target = [0u8; 32];
    target.copy_from_slice(&target_bytes);

    let lane_hi: u128 = hex::decode(&job.lane_hi_hex)
        .ok()
        .filter(|b| b.len() == 8)
        .map(|b| u128::from(u64::from_le_bytes(b.try_into().unwrap())))
        .unwrap_or(0);

    let mut fields = [Block128::from(0u128); 16];
    for (i, chunk) in field_bytes.chunks_exact(16).enumerate() {
        fields[i] = Block128::from(u128::from_le_bytes(chunk.try_into().unwrap()));
    }

    let threads = std::thread::available_parallelism()
        .map(|n| n.get())
        .unwrap_or(2)
        .min(8);
    eprintln!("mining job {} on {threads} threads", job.id);

    let start_counter =
        (std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .subsec_nanos() as u64)
            & 0x00FF_FFFF_FFFF_FFF0;

    std::thread::scope(|scope| {
        for tid in 0..threads {
            let stop = Arc::clone(stop);
            let hashes_total = Arc::clone(hashes_total);
            let submit_tx = submit_tx.clone();
            let pool_paused = Arc::clone(pool_paused);
            let jobs = Arc::clone(jobs);
            let job_id = job.id.clone();
            let work_domain = job.work_domain_hex.clone();
            scope.spawn(move || {
                let mut hasher = FixedFieldNonceBatch::new(TAG_POWHDR, &fields, NONCE_FIELD_INDEX);
                let mut digests = vec![[0u8; 32]; DIGEST_BATCH];
                let mut counter =
                    start_counter.wrapping_add((tid as u64) * 0x0400_0000_0000u64);
                while !stop.load(Ordering::SeqCst) {
                    if pool_paused.load(Ordering::SeqCst) {
                        std::thread::sleep(Duration::from_millis(500));
                        continue;
                    }
                    // A newer job arrived? Abandon this generation.
                    {
                        let slot = jobs.lock().unwrap();
                        match slot.as_ref() {
                            Some(cur) if cur.id != job_id => return,
                            _ => {}
                        }
                    }
                    let lane = lane_hi | u128::from(counter);
                    hasher.hash_into(lane, &mut digests);
                    for (i, digest) in digests.iter().enumerate() {
                        if noid_pow::le256_lt(digest, &target) {
                            let nonce_counter = counter.wrapping_add(i as u64);
                            let nonce_hex = hex::encode(
                                (lane_hi | u128::from(nonce_counter)).to_le_bytes(),
                            );
                            eprintln!(
                                "share found counter={nonce_counter} hash={}…",
                                hex::encode(&digest[..6]),
                            );
                            let _ =
                                submit_tx.send((job_id.clone(), nonce_hex, work_domain.clone()));
                        }
                    }
                    hashes_total.fetch_add(DIGEST_BATCH as u64, Ordering::Relaxed);
                    counter = counter.wrapping_add(DIGEST_BATCH as u64);
                }
            });
        }
    });
}
