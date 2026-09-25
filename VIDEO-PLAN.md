# VIDEO-PLAN — M3: 1:1 video calls

Implementation plan for `calls` M3 (SPEC.md §3.3). Status lives here; history in
root `WORKLOG.md`. Grounded in the vendored lib (`rust/whatsapp-rust`, fork HEAD
`a7934969` on 0.7.0) and the bridge as built (v0.2.0, audio daily-drivable on the LP3).

## 0. Spec corrections baked in (verified against the lib; SPEC §2/§3.3 updated)

- The library has **no `send_video_au`/`recv_video_au`** — video moves as whole
  Annex-B AU channels behind the `VideoSource`/`VideoSink` traits
  (`src/voip/video.rs`), attached at builder time (`.video(source, sink)`) or
  mid-call (`start_video(source, sink)` / `accept_video(token, source, sink)`,
  facade.rs:3384/3398). The bridge keeps its sync Kotlin surface by owning the
  channels itself — the mic/playout pattern (`wa_bridge` lib.rs:252–261).
- **No `video_states()` getter** — video state arrives only via
  `CallEvent::VideoStateChanged { state, orientation, upgrade_token }`
  (`wacore/src/voip/engine.rs:491`).
- **No `VideoKeyframeNeeded` event** — peer PLI/FIR is handled engine-internally
  (`VideoControl::RequireKeyframe`, `src/client/voip.rs:1716`); consumers see
  `RtcpReceived` and `OutboundMediaDropped { video_access_units, packets }`.
  How the engine asks the *source* for a sync frame is an open question (V1-Q1).
- `VideoUpgradeToken` = `{ generation: u64, epoch: u64 }` (registry.rs:46) —
  crosses UniFFI as two numbers, not an object.
- `announce_video_enabled()` (facade.rs:3418) must be sent after accepting a
  mid-call upgrade.
- `WaEvent::IncomingCall { is_video }` already exists (lib.rs:37) but
  `accept_call()` ignores it — video-from-start needs builder `.video(...)`.

## Ordering

PLAN.md §1 (outgoing cancel doesn't stop peer ringing) and §2 (incoming ring
from dead process) land **first** — video multiplies both paths, and the
PLAN.md two-device test rig (LP3 + emulator) is also the video test rig.

## V0 — bridge video surface (Rust, ~2–3 days)

New UniFFI types in `rust/wa-bridge/src/lib.rs`:

```rust
record WaVideoFrame { data: Vec<u8>, keyframe: bool, orientation: u8 }
record WaVideoUpgradeToken { generation: u64, epoch: u64 }
// WaEvent additions:
//   VideoStateChanged { state: i32, orientation: Option<u8>, upgrade: Option<WaVideoUpgradeToken> }
//   OutboundVideoDropped { access_units: u32 }        // from CallEvent::OutboundMediaDropped
```

Bridge internals: `video_tx` (Kotlin→engine, `bounded(4)`, drop-oldest) +
`video_rx` (engine→Kotlin) channels created once, drained on call teardown.
`BridgeVideoSource { rx, fps }` implements `VideoSource` (`rtp_timestamp_stride`
= `90_000 / fps`); `BridgeVideoSink { tx }` implements `VideoSink`.

Bridge functions (mirror mic/playout signatures):

```rust
fn send_video_au(&self, au: Vec<u8>) -> Result<bool, WaError>;   // Ok(dropped) — Kotlin requests a sync frame on true
fn recv_video_frame(&self, timeout_ms: u32) -> Option<WaVideoFrame>;
async fn start_video(&self, fps: u32) -> Result<(), WaError>;            // CallHandle::start_video(source, sink)
async fn accept_video(&self, token: WaVideoUpgradeToken, fps: u32) -> Result<(), WaError>;
async fn stop_video(&self) -> Result<(), WaError>;                       // idempotent (facade.rs:3443)
fn announce_video_enabled(&self) -> Result<(), WaError>;
// place_call / accept_call gain `with_video: bool` → builder .video(...) (video-from-start)
```

Wire `VideoStateChanged`/`OutboundMediaDropped` into the existing event relay
(lib.rs:1370 area). Regenerate `server/.../bridge/wa_bridge.kt`.

**Gate:** `cargo ndk -t aarch64-linux-android -t x86_64-linux-android build
--features voip`; `#[cfg(test)]` AU roundtrip through `send_video_au` → source
receiver; drop-oldest unit test (mirrors the mic-queue test).

## V1 — encode pipeline (Kotlin `:server`, ~3–5 days)

`server/.../VideoLink.kt` — encode half, sibling of `AudioLink.kt`:

- Camera2 front → `MediaCodec` `video/avc` via `createInputSurface()` (no YUV
  plumbing). Constrained Baseline L3.1, B-frames 0, one slice/frame (encoder
  default), I-frame interval 1 s (GOP 30 @ 15 fps).
- Tiers (SPEC §3.3): 640×480 @ 15 fps ~600 kbps (default) · 1280×720 @ 20 fps
  ~1.8 Mbps (WiFi). Chosen tier's fps is passed to `start_video(fps)` so the
  bridge stride matches — stride mismatch fails at builder time (facade.rs:214).
- **CSD per IDR:** hold SPS/PPS from `BUFFER_FLAG_CODEC_CONFIG`; prepend to
  every keyframe AU before `send_video_au` (the engine packs one STAP-A only if
  the AU carries them). Every keyframe AU must contain SPS+PPS+IDR.
- Local overflow: on `send_video_au` returning dropped → drop-oldest already
  done in the bridge; request `PARAMETER_KEY_REQUEST_SYNC_FRAME` on the encoder.
- Camera open/encode on a dedicated handler thread; release on `stop_video` and
  on `CallEnded` (WaCallEngine.kt:315 reducer's teardown branch).
- All verbose dumps (AU stream capture for ffprobe) behind the runtime opt-in
  diagnostics flag — battery rule.

**V1-Q1 (resolve first):** trace how the engine delivers peer PLI to the
`VideoSource` (`VideoControl::RequireKeyframe` flow, `src/client/voip.rs:1716`
→ driver) — if it never reaches the source, remote keyframe repair relies on
the 1 s GOP only; note the ceiling and consider a `RtcpReceived`-triggered
`PARAMETER_KEY_REQUEST_SYNC_FRAME` from the Kotlin side.

**Gate (emulator, virtual-scene camera):** captured AU stream → `ffprobe`:
h264 Constrained Baseline L3.1, SPS+PPS before every IDR, one slice/frame,
~15 fps, ~600 kbps.

## V2 — decode, render, call UI (Kotlin `:app` + `:server`, ~4–6 days)

- `VideoLink` decode half: pull thread `recv_video_frame(50)` → `video/avc`
  decoder, `KEY_LOW_LATENCY=1`, render to a `SurfaceView`. Hold AUs until the
  first keyframe; seed the decoder by pushing the first IDR with
  `BUFFER_FLAG_CODEC_CONFIG`.
- Orientation: rotate the SurfaceView per `VideoFrame.orientation` (90° steps
  0..3). **Verify the convention against a real call** — flagged in SPEC §3.3.
- `WaCallEngine`: a `videoState: StateFlow<VideoUiState>` reduced from
  `VideoStateChanged` (Disabled/Enabled/UpgradeRequest/Stopped/Error → UI
  states; `is_upgrade_request()` gates the prompt). `acceptVideo(fps)` /
  `startVideo(fps)` / `stopVideo()` actions; `OutboundVideoDropped` → sync-frame
  request plumbing.
- UI (`ActiveCallScreen.kt`): video surface (peer, full-bleed) behind the
  existing monochrome overlay (status line / peer name / controls stay per
  PHONE-PANELS.md); self-view is out — YAGNI for v1; controls gain a video
  toggle (underline = active, icon-row grammar) and the upgrade prompt row
  ("video?" accept/decline) styled like the confirm-panel rows. Errors reuse
  `ErrorFlash`. Incoming video-from-start: `IncomingCallScreen` shows the
  video badge; answer pre-warms the camera (~300–800 ms open) before
  `accept_call(with_video=true)`.
- Start/stop of `VideoLink` owned by `WaCallEngine` next to `audioLink().start()`
  (WaCallEngine.kt:349) and torn down in the `CallEnded` branch.

**Gate:** SPEC M3 — synthetic test-pattern AUs through the bridge render on an
official WhatsApp client; official client video renders on our SurfaceView;
then upgrade/downgrade both directions on the PLAN.md rig (LP3 ↔ emulator,
both initiator roles).

## V3 — lifecycle, permissions, FGS (~2–3 days)

- `WaCallService` FGS type += `camera` (currently mic-only, WaCallService.kt:23);
  `CAMERA` permission flow mirroring `MicPermission`; grant before any camera open.
- Upgrade-flow matrix, all eight `VideoState` wire paths that matter for 1:1:
  out-upgrade accept / reject / timeout · in-upgrade accept / reject /
  timeout · peer stop mid-call · downgrade cleanup (stop encoder, release
  camera, blank surface, stride state reset).
- Glare with PLAN.md §1: a cancel during `EnableAwaitingAccept` must terminate
  cleanly (no orphaned video signaling).

**Gate:** every matrix cell passes on the rig; FGS with camera type starts from
the answer/connect tap on both emulator and LP3 (Android 14 while-in-use rule).

## V4 — LP3 hardening (~2–3 days)

Real-account matrix from V3 (needs the PLAN.md rig; emulator can only gate the
mechanics — see the V3 report):

- [ ] out-upgrade: peer accepts our mid-call upgrade (video flows both ways)
- [ ] out-upgrade: peer rejects our mid-call upgrade (clean downgrade, no orphaned signaling)
- [ ] out-upgrade: peer never answers the upgrade (timeout — clean downgrade)
- [ ] in-upgrade: we accept the peer's upgrade (CAMERA grant → encoder joins, FGS elevated)
- [ ] in-upgrade: we decline the peer's upgrade (state-6 decline; peer exits its wait)
- [ ] in-upgrade: we never answer the prompt (timeout — clean)
- [ ] peer stop mid-call: our encode/decode stop, surface blanks, state resets
- [ ] downgrade cleanup: stop → camera released, stride state reset, no stale AUs
- [ ] FGS starts as microphone|camera from the answer/connect tap on the LP3 (Android 14 while-in-use rule)
- [ ] glare on-device: cancel during a pending video upgrade (`EnableAwaitingAccept`) terminates cleanly (no orphaned video signaling, flags reset)

- Orientation convention verified vs a real call; lipsync sanity (audio path
  unchanged — watch playout trim interaction). If the orientation is learned
  late, the peer keeps the stale value until the next `<video>` stanza — the
  static read closes the practical case.
- Dynamic mid-call rotation (re-announce on device rotation) — verify live:
  rotate the LP3 mid-video-call; peer picture should rotate; calibration
  (raw vs inverse) decided same retest.
- Orientation residual (from-start callee): the wacore `video_accept_node`/
  `video_preaccept_node` (`<accept>`-child `<video>`) and `video_offer_node`
  still hardcode `device_orientation="0"` — a video-from-start call needs the
  same plumbing through `build_accept`/`build_offer` params; the upgrade flow
  is fully wired (facade states 1/11/4 + handler answer/announce sites).
- Confirm the peer's client exits its upgrade-wait cleanly after our
  stop-mapped decline (SPEC §3.3: prompt-decline sends VideoState 6, not 5).
- Thermal + battery: 30-min 480p15 video soak on the LP3, audit-dir convention
  (battery-audit-*); G99 encode thermal ceiling check — drop to 360p tier if
  the SoC throttles.
- Tier decision: v1 ships the 480p@15 tier only — the 720p@20 WiFi tier stays
  a documented config point (SPEC §3.3) unless real-call evidence demands it.
- Incoming video badge on IncomingCallScreen (needs a Light glyph pass) —
  deferred from V2/V3.
- Peer decode death shows a frozen frame — verify the UX on a real call.
- First-picture latency on the LP3: the CODEC_CONFIG seed may drop VCL NALs
  on HW decoders — fallback: split SPS/PPS config from the IDR data buffer.
- B&W AMOLED rendering check (LP3 display renders video grayscale — confirm
  it's usable; the overlay must stay readable).
- Regression: PLAN.md rig matrix (answer/decline/cancel both directions, ringer
  + haptics) re-run with `with_video` variants.

**Gate = SPEC M3 gate:** 480p15 call both directions vs official client;
ffprobe stream checks; upgrade/downgrade both directions; clean teardown.

## Risks

1. **V1-Q1 keyframe path unknown** — if the engine never signals the source,
   quality on loss degrades to GOP-1 s recovery; acceptable ceiling, revisit if
   the official client reports persistently bad video.
2. **G99 encoder thermal/battery at 480p15** — soak gates it; 360p fallback tier.
3. **Emulator vs LP3 MediaCodec differences** — emulator uses SW c2.android.avc
   encoder (fine for gating); LP3 HW encoder may behave differently on
   CSD/slice layout → the ffprobe gate re-runs on a LP3 capture at V4.
4. **UniFFI callback additions** are additive — no session-migration risk; new
   events arrive on Rust threads like existing ones (main-dispatcher hop unchanged).
5. **Library churn** — accept_video signature moved twice in 2026; pin the fork
   rev, re-read the guide warnings on bump (SPEC §2 rule).

## Orientation fix (2026-09-24, live-call finding)

**Root cause:** LP3→emulator video rendered horizontally on the receiver — the
sender encoded sensor-native frames (front camera is natively landscape) while
the vendored library hardcoded `device_orientation: Some(0)` in every outbound
`<video>` stanza (no shell API to signal it). Receiver was already correct.

**Patch (fork, `call-log-sync`):** `VideoShared` gains an `AtomicU8
device_orientation`; `CallHandle::set_device_orientation()`/`device_orientation()`
expose it; the outbound-state sites in `voip/facade.rs` (state 1, and the
upgrade closure — states 11/4) send `Some(self.device_orientation())`. The
state-6 Stopped stanza stays hardcoded 0. **Follow-up (2026-09-25, retest):**
the fatal 0 came from the signaling handler's own sends — the initiator's
post-accept `Enabled` and the concurrent-upgrade `UpgradeAccept`/`Enabled`
answer (`handlers/call.rs`) — which never read the cell; they now read
`registry.device_orientation()` (the facade shares `VideoShared`'s cell with
the registry at registration). The from-start-callee `<accept>`/`<offer>`
video nodes in wacore still carry 0 — see the V4 residual note.
**Bridge:** `WaBridge.set_video_orientation(u8)` stores the value (AtomicU8,
default 0), applies it to the live handle, and `attach_call` applies it to a
new handle (pre-call settings in force for the first state-11 stanza).
**Sensor source:** `VideoLink` reports `SENSOR_ORIENTATION / 90` (0..3) at
encode start (`onSensorOrientation`); `frontSensorOrientationSteps()` reads
the same characteristic without a camera open (not CAMERA-gated);
`WaCallEngine.videoOrientationSignal()` computes the signaled value, pushed
before `startVideo`/`acceptVideo`/`acceptCall` and on every camera report.
**Convention calibration:** raw sensor value vs complement `(4 - x) % 4` is
calibrated on-device in the live retest — the computation lives only in
`videoOrientationSignal()`, so flipping it is a one-line change.
