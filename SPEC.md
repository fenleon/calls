# WhatsApp Calls — SPEC

**Tool:** WhatsApp voice + video calling for the Light Phone III (`com.lightphone.calls`).
**Status:** spec only — no code yet. Date: 2026-09-19.
**Foundation:** [whatsapp-rust](https://github.com/oxidezap/whatsapp-rust) v0.7, `voip` feature — the only open-source WhatsApp client with a working full call media path (audio: PR #918; video: PR #1024; group calls/call links: PR #1130 — **excluded from v1**). License: MIT. API facts below are taken from the library's own [VoIP guide](https://oxidezap.github.io/whatsapp-rust/guides/voip-calls), not guessed.

## 0. Product shape & non-goals

A calm, native-feeling call tool: pair once, see a call log, answer calls, place calls, (later) upgrade to video. Everything else is out.

**Non-goals (v1):** messaging, group calls, call links, multi-account, contact sync/presence UI, bandwidth-adaptive video (static tiers only), a dialpad beyond simple number entry if the call log proves sufficient.

**Account model:** linked-device pairing against the user's existing WhatsApp account (same model as WhatsApp Web). The library is a WhatsApp *Web* API client; registration is QR / pair-code linking. **Unofficial clients violate WhatsApp ToS — document this in the tool, recommend a secondary number, and never ship auto-retry on auth failures.**

## 1. Architecture

Single APK, merged tool+server pattern (tasks/chats precedent):

```
calls/
├── :app      plugin-scanned LightOS tool — lighttool.toml, LightScreen UIs only
├── :server   unscanned Android library — Rust .so + UniFFI Kotlin + LightSdkService
└── rust/     wa-bridge crate (wraps whatsapp-rust; UniFFI exports)
```

- `:server` hosts the `LightSdkService`; `serverPackage` = own id (`com.thelightphone.sdk.emulator` on the emulator). All non-allowlisted code (JNI, tokio, SQLite session store) lives here; `:app` stays plugin-clean.
- Rust targets: `aarch64-linux-android` (LP3) + `x86_64-linux-android` (emulator). Build via cargo-ndk wired into Gradle; `tools/build --dir calls :app:assembleDebug` is the only installable output.
- No sans-IO `CallEngine` work — the `client.voip()` facade runs on an in-process tokio runtime inside the bridge.

### Data flow (audio call)

```
AudioRecord (16 kHz) ──960-sample i16──▶ bridge ──AudioSource chan──▶ voip engine
AudioTrack  (16 kHz) ◀─960-sample i16── bridge ◀──AudioSink chan──── voip engine
                                                                          │
              CallListener (UniFFI callback) ◀── events ──── relay/WS ◀───┘
```

Video (M3) adds `VideoSource`/`VideoSink` Annex-B AU channels fed by/draining MediaCodec.

## 2. Rust bridge (`rust/` → `wa-bridge`)

Thin wrapper; **no hand-rolled queues** — the library's endpoints are `async_channel` channels which already expose `send_blocking`/`recv_blocking`. The bridge surface is:

> **Pin (M0):** resolved `whatsapp-rust 0.7.0` / `wacore 0.7.0` / `wacore-binary 0.7.0` / `opus 0.3.1` (crates.io, 2026-09-19). `wacore-binary`'s default `simd` feature needs `portable_simd` → the crate pins the **nightly** toolchain (`rust-toolchain.toml`); stable 1.98.1 fails E0554.

```rust
#[derive(uniffi::Object)] pub struct WaBridge { /* tokio rt + Client + handles */ }

// lifecycle / pairing
fn start(&self, listener: Box<dyn CallListener>) -> Result<(), WaError>;   // restore session or emit NeedPairing
fn pair_start(&self) -> Result<(), WaError>;                               // begins QR flow, emits PairingQr { payload }
fn pair_confirm(&self, paired: bool) -> Result<(), WaError>;
fn logout(&self) -> Result<(), WaError>;

// calls (async → Kotlin coroutines)
async fn place_call(&self, phone_or_jid: String) -> Result<String, WaError>; // bridge normalizes number → JID; returns call_id
async fn accept_call(&self) -> Result<(), WaError>;
async fn reject_call(&self) -> Result<(), WaError>;
async fn terminate(&self) -> Result<(), WaError>;        // CallHandle::terminate() — NOT hangup_local()
async fn set_muted(&self, muted: bool) -> Result<(), WaError>;

// data plane (sync, called from dedicated Kotlin threads)
fn send_mic_pcm(&self, samples: Vec<i16>) -> Result<(), WaError>;          // 960 samples / 60 ms
fn recv_playout_pcm(&self, timeout_ms: u32) -> Vec<i16>;                   // 960 samples; empty = starved
fn request_peer_keyframe(&self, immediate: bool);                          // KeyframeUrgency

// video (M3)
fn send_video_au(&self, au: Vec<u8>) -> Result<(), WaError>;               // complete Annex-B AU
fn recv_video_au(&self, timeout_ms: u32) -> Option<UniVideoFrame>;         // data + keyframe + orientation
async fn start_video(&self) -> Result<(), WaError>;
async fn accept_video(&self, token: VideoUpgradeToken) -> Result<(), WaError>;
async fn stop_video(&self) -> Result<(), WaError>;
fn video_states(&self) -> Option<VideoStates>;                             // (self, peer)
```

Bridge rules:

- **Pacing:** Kotlin's AudioRecord/AudioTrack threads own the clock (60 ms blocks). Mic push is bounded/drop-oldest (never block the capture thread); playout pull blocks with timeout. The engine paces playout itself (jitter buffer exists — see `playout_trimmed_samples` stat).
- **Events:** one consumer on `handle.events()` (cloned consumers share one queue — the bridge owns the single consumer) mapping the library's `Event`/`CallEvent` onto a Kotlin enum: `NeedPairing`, `PairingQr`, `IncomingCall { is_video, peer }`, `CallStarted`, `CallEnded { reason }`, `MissedCall`, `EndedElsewhere`, `VideoStateChanged { state, upgrade_token }`, `VideoKeyframeNeeded`, `AudioSilent { reason, silent_for_ms }`, `AudioReceptionStalled`, `MediaSetupFailed`, `RelayAllocateFailed`. Callbacks arrive on Rust threads — generated shim enqueues; Kotlin hops to the main dispatcher.
- **Redaction:** the library redacts SRTP/HBH keying material in Debug; the bridge additionally never logs JIDs, tokens, or session material (workspace privacy rule).
- Pin the crate version/rev exactly; the API moved under us at least three times during 2026 (`accept_video` signature, `terminate()` rename, codec-selection fix — PRs #1051/#1351/#1111). Bump only with a re-read of the guide's warning section.

## 3. Kotlin layer

### 3.1 Services & permissions

- `WaSyncService` — `dataSync` FGS holding the persistent WhatsApp WebSocket (chats' `ChatSyncService` pattern; no GMS — the socket *is* the push channel). LP3 ships doze disabled, so keep idle traffic minimal; verbose logging behind a runtime opt-in flag, off by default.
- `WaCallService` — `microphone|camera` FGS started **from the answer/ring UI tap** (Android 14 requires mic/camera FGS start while foreground-visible).
- Permissions: `RECORD_AUDIO`, `CAMERA`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`(+`_MICROPHONE`/`_CAMERA`), `WAKE_LOCK`. `USE_FULL_SCREEN_INTENT` for incoming-call UX — ⚠ verify LightOS honors FSI for sideloaded tools; fallback = heads-up notification + tap.
- During a call: partial wake lock + `WifiLock`; `AudioManager.mode = MODE_IN_COMMUNICATION`.

### 3.2 Audio

- **Capture:** `AudioRecord`, source `VOICE_COMMUNICATION` (HAL AEC/NS), **16 kHz mono PCM16** — the library's operating point is 960 samples/60 ms. Open at 16 kHz directly (HAL resamples); only if latency or AEC quality suffers, capture at the device-preferred rate and resample before push. Dedicated thread, `THREAD_PRIORITY_URGENT_AUDIO`.
- **Playout:** `AudioTrack` 16 kHz, `MODE_STREAM`, `PERFORMANCE_MODE_LOW_LATENCY`, `USAGE_VOICE_COMMUNICATION`; pull 960-sample frames, treat starved reads as silence; watch `media_stats().audio_sink_dropped`.
- **Routing:** `setCommunicationDevice()` (earpiece ↔ speaker) toggle in the call UI. AEC is the #1 on-device risk — run a double-talk loopback test on the LP3 in M2 before trusting the HAL path.
- Codec note: MLow is the default negotiated codec (PT 120, 16 kHz) and the library handles it — Kotlin never sees codec bytes. `AudioSilent { dominant_reason }` surfaces decode problems.

### 3.3 Video (M3)

- **Encode:** Camera2 (front) → `MediaCodec` `video/avc`, **Constrained Baseline L3.1**, no B-frames, one slice/frame. Tiers: 480p@15 fps ~600 kbps (default/cellular), 720p@20 fps ~1.8 Mbps (WiFi). GOP 30. `VideoSource.rtp_timestamp_stride()` must equal `90_000 / fps` — declare it to match the encoder tier.
- **CSD repetition:** MediaCodec hands out SPS/PPS once as CSD; Kotlin must prepend them to **every** IDR AU before `send_video_au` (the engine packs them into one STAP-A, PR #1496, but only if the AU carries them). Deterministic test: every AU flagged keyframe contains SPS+PPS+IDR.
- **Keyframes:** react to `VideoKeyframeNeeded` with `MediaCodec PARAMETER_KEY_REQUEST_SYNC_FRAME` + `request_peer_keyframe(immediate)`; the engine withholds dependent AUs after peer PLI until the next IDR. On local queue overflow: drop-oldest + request a sync frame.
- **Decode:** `recv_video_au` pull thread → `video/avc` decoder → `SurfaceView` (not TextureView). Hold AUs until first keyframe; seed decoder config from its SPS/PPS. `KEY_LOW_LATENCY=1`.
- **Orientation:** send sensor-native frames; `VideoFrame.orientation` from the peer is applied at render. ⚠ Verify the orientation value convention against a real call before shipping.
- Final accept for video-from-start calls gates on "first decodable SPS/PPS/IDR pushed" — pre-warm the camera when the ring UI appears (camera open is ~300–800 ms).

## 4. UI (Light design system)

Screens (all `LightScreen`/`LightScreenViewModel`; toolbox launch; `@InitialScreen` = call log; taps/toggles only — **no long-press**):

- **Pairing** — shown until linked. Renders the library's QR payload via ZXing (allowlisted) + pair-code fallback text. No content, no messages — pure setup.
- **Call log (initial)** — recent calls (in/out/missed + peer name/number), tap to call. JSON persisted in private storage.
- **Active call** — peer, duration, mute toggle, speaker toggle, hang up; video (M3) adds start/stop video + accept-upgrade prompt when `VideoStateChanged` arrives with `is_upgrade_request()`.
- **Incoming call** — full-screen answer/decline.
- **Settings** — paired-account status, logout, audio-route default, diagnostics toggle (opt-in verbose logging).

Design pass via the `lightos-design` skill at end of each implementation session.

## 5. Testing

- **M0 gates:** `cargo ndk -t aarch64-linux-android -t x86_64-linux-android build --features voip`; license confirmed (MIT ✓); QR pairing completes on the emulator; `.so` loads from `:server`.
- **M1 (emulator):** bridge unit tests against the guide's documented semantics (terminate vs hangup_local, token expiry on `accept_video`, drop-oldest mic queue). End-to-end audio call emulator ↔ real WhatsApp on a second phone: intelligible bidirectional audio.
- **M2 (LP3):** AEC double-talk loopback; earpiece/speaker routing; 30-min soak with battery + RAM logging (audit dir convention); missed-call notification when WS reconnects after offline.
- **M3 (video):** synthetic test-pattern AUs render on an official client; ffprobe of a dumped outbound stream confirms CBP L3.1 + SPS/PPS-per-IDR + one slice; upgrade/downgrade both directions; lipsync sanity.
- **M4:** netem (host hotspot, `tc netem` 100±30 ms, 1/5/10 % loss): call survives 5 %; `media_stats()` counters (rtp_received, srtp_unprotect_failed, mlow_off_point_dropped, peer_keyframe_requests) as the dashboard; reconnect after airplane-mode toggle.

## 6. Milestones

| M | Contents | Gate |
|---|----------|------|
| M0 (days) | NDK cross-compile, pairing proof, Gradle wiring | `.so` loads; session persists across restart |
| M1 (1–2 wk) | wa-bridge (audio), CallScreens, WaSyncService | Intelligible emulator ↔ real WhatsApp call |
| M2 (1–2 wk) | LP3 audio: AEC, routing, notifications, soak | Daily-driver voice call; loopback < 300 ms |
| M3 (2–3 wk) | Video: encode/decode, CSD, keyframes, upgrade UX | 480p15 call both directions vs official client |
| M4 (1 wk) | netem robustness, reconnect, stats | 5 % loss survivable; clean reconnect |
| M5 | apk-shrinking pass, release notes, upstream pin bump | Release APK ≤ ~10 MB |

## 7. Risks

1. **Account ban** — ToS violation inherent to unofficial clients; burner number, document in-app, no auto-retry loops on auth failure.
2. **AEC quality on MTK G99** — double-talk test gates M2; fallback is a Rust `webrtc-audio-processing` module in the bridge (budgeted, not scheduled).
3. **Library churn** — fast-moving upstream, breaking changes in 2026 already; pin rev, re-read guide warnings on bump, keep the bridge seam thin so swaps are contained.
4. **`.so` size** — tokio + rtc-dtls/sctp + MLow across two ABIs; measure at M0, apply APK-SHRINKING.md methodology at M5 (`strip`, single-ABI per device install is not possible — both ABIs ship).
5. **LightOS FGS/FSI semantics** — incoming-call UX depends on FSI behavior for sideloaded tools; heads-up fallback specced.
6. **Battery** — persistent WS is unavoidable (no push); LP3 doze is disabled so discipline comes from the tool: no wakeups beyond socket keepalive, opt-in logging only.
