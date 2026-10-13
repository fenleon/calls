# Call Resilience + Video Lifecycle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A video call survives a transient WebSocket/transport drop, the peer's camera off→on restores correct orientation, and our own camera off→on resumes sending without the peer's side having to cycle.

**Architecture:** Three independent fixes in two layers: (1) decouple the voip media transport (`net_conn`, DTLS/UDP relay) from WhatsApp signaling WebSocket reconnects in the vendored `whatsapp-rust` stack; (2) carry the peer's `device_orientation` across media-engine rebuilds via a registry-shared cell (the outbound `device_orientation` already works this way); (3) keep the outbound video source channel armed across our `signal_video_stopped` → `announce_video_enabled` cycle. Items 2 and 3 have pinned root-cause hypotheses with wire/log evidence; item 1 has an investigation gate because the teardown owner isn't yet identified.

**Tech Stack:** Vendored [whatsapp-rust](https://github.com/oxidezap/whatsapp-rust) fork (Rust, nightly pin) at `calls/rust/whatsapp-rust/`, kept in sync with `calls/rust/patches/`; UniFFI bridge `calls/rust/wa-bridge/`; Kotlin engine `calls/server/src/main/kotlin/com/lightphone/calls/server/`.

**Spec / evidence:**
- `calls/SPEC.md` (video model: camera off ≠ leaving video mode — `signal_video_stopped` keeps the plane)
- `WORKLOG.md` 2026-10-13 entries (all evidence cited below)
- Live evidence 2026-10-13 20:59 call: `WebSocket read error: Software caused connection abort (os error 103)` → `Transport disconnected; reconnecting` (1 s) → `webrtc_sctp::association: [wa-voip] failed to write packets on net_conn: conn is closed` × 9 → call dead. Reconnect itself succeeded (`Handshake complete (IK)` 1 s later).

## Global Constraints

- Rust crate uses a **per-crate nightly pin** (`RUSTUP_TOOLCHAIN=nightly-2026-06-16` per WORKLOG 2026-10-12; `mlow` sits behind the `voip-mlow` feature). Run `cargo test` with that env.
- Vendored-stack edits stay in `calls/rust/whatsapp-rust/` **and** the corresponding patch file under `calls/rust/patches/` must be regenerated/extended in the same commit (0002 covers the orientation fork-delta; add 0003 for new deltas).
- Battery rule: no per-frame logging. One-shot WARN/INFO markers only (precedent: `video decode ended:`, `video encode stop:`, `keyframe pressure`).
- Kotlin tool module bans raw `Context`/Services; engine code lives in `:server` (`calls/server/`).
- Build: `tools/build --dir calls :tool:assembleDebug` (never two builds concurrently).
- Device control policy: the user drives the LP3; emulator (`lightos` AVD) is the default verification target. LP3↔emulator video-call rig exists (see `calls-audit-2026-09-2{3,4,5}/`).

---

### Task 1: Transport-blip teardown owner (investigation gate)

**Files:**
- Read-only: `calls/rust/whatsapp-rust/src/voip/transport.rs`, `calls/rust/whatsapp-rust/src/client/app_state.rs`, `calls/rust/whatsapp-rust/src/client/node_io.rs`, `calls/rust/wa-bridge/src/lib.rs`
- Create: `calls/CALL-RESILIENCE-PLAN.md` § findings (append here)

**Interfaces:**
- Produces: the exact call site that closes the voip `net_conn` on WS loss, and whether the call's signaling state (`CallHandle`) survives a reconnect — required before Task 2's code can be written.

The 20:59:28 log shows the media conn dying WITH the WS drop (same second), so something explicitly tears the voip transport down on signaling loss. `transport.rs:274` already contains a teardown comment ("Close the association so `net_conn` (the DTLS/UDP socket) and the read/write loops are…") — start there.

- [ ] **Step 1: Find the net_conn teardown caller**

```bash
grep -n "Close the association\|fn stop\|fn shutdown\|fn teardown" \
  calls/rust/whatsapp-rust/src/voip/transport.rs
```
For each teardown fn found, list its callers:
```bash
grep -rn "<teardown_fn_name>" calls/rust/whatsapp-rust/src/ calls/rust/wa-bridge/src/
```
Expected: a chain from the client's transport-disconnect handler (`node_io.rs` logs `Transport disconnected; reconnecting`) or the call-end path to the voip transport stop.

- [ ] **Step 2: Check whether the CallHandle survives reconnect**

```bash
grep -n "Transport disconnected" -A 20 calls/rust/whatsapp-rust/src/client/node_io.rs
grep -n "call" calls/rust/whatsapp-rust/src/client/app_state.rs | grep -in "reconnect\|drop\|clear"
```
Question to answer in writing: does the active call's registry entry / `CallHandle` outlive the WS reconnect, or does a disconnect force `terminate`?

- [ ] **Step 3: Write the finding + chosen fix shape into this plan**

Append to this file under `## Task 1 findings`: (a) teardown owner file:line, (b) whether call state survives, (c) the minimal change that keeps `net_conn` alive across reconnects (or, if the media relay genuinely requires re-negotiation after a signaling reconnect, the re-signal sequence: which `<video state>` / keepalive stanzas must be re-sent on the new connection).

- [ ] **Step 4: Commit the findings**

```bash
git -C calls add CALL-RESILIENCE-PLAN.md
git -C calls commit -m "docs: pin transport-blip teardown owner (resilience task 1)"
```

### Task 2: Keep `net_conn` alive across a signaling reconnect (implementation, contingent on Task 1)

**Files:**
- Modify: the teardown owner found in Task 1 (expected: `calls/rust/whatsapp-rust/src/voip/transport.rs` or its caller)
- Modify: `calls/rust/patches/` (regenerate/add `whatsapp-rust-0003-*.patch`)
- Test: `calls/rust/whatsapp-rust/wacore/src/voip/` (existing `#[cfg(test)]` modules are the pattern — e.g. `driver.rs:2791` drives `VideoControl` through a real engine)

**Interfaces:**
- Consumes: Task 1's teardown owner.
- Produces: `stop()` is no longer invoked on signaling reconnect; a call in `VideoState` stays media-connected through a ≤5 s WS blip. Observable: during a forced WS drop (airplane-mode toggle on the emulator's network, or `adb shell cmd connectivity`), the LP3↔emulator call survives.

- [ ] **Step 1: Write the failing test**

In the wa-bridge/wacore test module that exercises the call lifecycle (pattern: `wa-bridge/src/lib.rs` `#[test]` fns around line 2252, which build a `WaBridge` with a `Sink` listener and drive a fake call), add a test that: attaches a call, asserts the voip transport is up, fires the client's transport-disconnect path, asserts the media `net_conn` is STILL writable (the sctp association has not been closed), then fires reconnect and asserts the call is still live. Sketch (adapt names to what Task 1 found — the exact disconnect entry point is Task 1's deliverable):

```rust
#[test]
fn media_transport_survives_signaling_reconnect() {
    let bridge = WaBridge::new(Box::new(Sink(Arc::default())));
    // … attach a call (see the existing announce_video_enabled test at lib.rs:~2355
    //   for the call-attach incantation) …
    // fire the same hook the "Transport disconnected" log site calls
    // assert: voip transport still open  →  send_video_au must NOT return NoActiveCall
    assert!(bridge.send_video_au(vec![0, 0, 0, 1, 0x65]).is_ok());
}
```

- [ ] **Step 2: Run it — must fail today**

```bash
cd calls/rust/wa-bridge && RUSTUP_TOOLCHAIN=nightly-2026-06-16 cargo test media_transport_survives -- --nocapture
```
Expected: FAIL (transport closed with the disconnect).

- [ ] **Step 3: Minimal fix — remove the media teardown from the disconnect path**

Gate the teardown-owner call found in Task 1 so it fires on call termination only, not on transport reconnect. Shape (adapt to the real signature):

```rust
// transport-loss handler: BEFORE
self.voip_transport.stop();          // kills net_conn
// AFTER: nothing — the sctp/UDP relay is independent of the WS;
// if the stack requires a keepalive after reconnect, re-send it here
// (Task 1 step 3 decides which).
```

- [ ] **Step 4: Run the test + the crate's suite**

```bash
RUSTUP_TOOLCHAIN=nightly-2026-06-16 cargo test
```
Expected: new test PASS, no regressions. Then `tools/build --dir calls :tool:assembleDebug` green.

- [ ] **Step 5: Regenerate the patch file + commit**

```bash
git -C calls add rust/whatsapp-rust rust/patches
git -C calls commit -m "calls: voip media transport survives signaling reconnect"
```

- [ ] **Step 6: Device verification (user-driven)**

LP3↔emulator call, toggle the emulator network down/up (or toggle WiFi on the LP3 for a real blip). Expected: call survives; video/audio resume within ~5 s. If the media path needs a re-signal after reconnect (video stalls though audio lives), record it — that becomes Task 2b (re-send `<video state=1>` on reconnect, mirroring `announce_video_enabled`).

### Task 3: Peer `device_orientation` survives a media-engine rebuild (peer off→on CW-90 fix)

**Files:**
- Modify: `calls/rust/whatsapp-rust/wacore/src/voip/registry.rs` (add a peer-orientation cell, mirroring the outbound one at `registry.rs:241-244`)
- Modify: `calls/rust/whatsapp-rust/wacore/src/voip/engine.rs:1591` (`set_peer_video_orientation` also stores into the cell), the `CallEngine::new` path (seed from the cell)
- Modify: `calls/rust/wa-bridge/src/lib.rs` (share the cell where the outbound `device_orientation_cell` is shared — `facade.rs:1802` is the outbound pattern)
- Modify: `calls/rust/patches/` (0003 delta)
- Test: `calls/rust/whatsapp-rust/wacore/src/voip/` unit test

**Interfaces:**
- Produces: rebuilt `CallEngine`s are stamped with the peer's last-known orientation, so `WaVideoFrame.orientation` is correct after the peer's off→on without any Kotlin change (the Kotlin sticky in `WaCallEngine.kt` stays as belt-and-suspenders).

- [ ] **Step 1: Write the failing test**

In the registry/engine test module (pattern: `engine.rs:5352` `group_video_does_not_inherit_the_direct_peer_orientation`):

```rust
#[test]
fn peer_orientation_survives_engine_rebuild() {
    let registry = test_registry();
    let cell = Arc::new(AtomicU8::new(0));
    registry.set_peer_video_orientation("CALL-ID-0001", 1, Arc::clone(&cell));
    // engine 1 learns the peer rotated to 2
    let mut eng1 = CallEngine::new_test(&registry, "CALL-ID-0001", 1);
    eng1.set_peer_video_orientation(2);
    assert_eq!(cell.load(Ordering::Relaxed), 2);
    // media plane rebuilds (peer stop→start) → engine 2 seeds from the cell
    let eng2 = CallEngine::new_test(&registry, "CALL-ID-0001", 1);
    assert_eq!(eng2.peer_video_orientation(), 2);
}
```

- [ ] **Step 2: Run it — must fail**

```bash
cd calls/rust/whatsapp-rust && RUSTUP_TOOLCHAIN=nightly-2026-06-16 \
  cargo test peer_orientation_survives -p wacore -- --nocapture
```
Expected: FAIL (no `set_peer_video_orientation` on the registry; engine 2 seeds 0 — the observed bug).

- [ ] **Step 3: Implement**

Mirror the outbound cell exactly (`registry.rs:1793-1816` is the template — `set_device_orientation`/`device_orientation`): add `peer_video_orientation: Option<Arc<AtomicU8>>` to the registry entry, `set_peer_video_orientation(call_id, generation, cell)` + `peer_video_orientation(call_id, generation) -> u8` accessors; `CallEngine` construction reads the cell into `peer_video_orientation` (`engine.rs:929` currently hardcodes 0); `set_peer_video_orientation` (`engine.rs:1591`) writes through to the cell when present. Share the cell from the facade the way `video_shared.device_orientation_cell()` is shared (`facade.rs:1802`).

- [ ] **Step 4: Run the suite + build**

```bash
RUSTUP_TOOLCHAIN=nightly-2026-06-16 cargo test && tools/build --dir calls :tool:assembleDebug
```

- [ ] **Step 5: Patch file + commit**

```bash
git -C calls add rust/whatsapp-rust rust/patches
git -C calls commit -m "calls: peer orientation survives media-engine rebuild"
```

- [ ] **Step 6: Device verification (user-driven)**

LP3↔emulator call: peer camera off→on → incoming video returns at the SAME rotation (no CW-90 flash), both when the peer never rotated and after a genuine mid-call rotation.

### Task 4: Our camera off→on resumes sending (no peer-cycle needed)

**Files:**
- Modify: `calls/rust/wa-bridge/src/lib.rs` (`send_video_au` error surfacing / source re-arm on `announce_video_enabled`)
- Modify: `calls/rust/whatsapp-rust/src/voip/facade.rs` (whatever Task 4 step 1 pins as the non-rearmed path)
- Modify: `calls/server/src/main/kotlin/com/lightphone/calls/server/WaCallEngine.kt:1020` (surface the swallowed error)
- Test: `calls/rust/wa-bridge/src/lib.rs` tests (pattern: `send_video_au_*` at line ~2252)

**Interfaces:**
- Consumes: `WaBridge::send_video_au` (`lib.rs:1405`), `signal_video_stopped` (`lib.rs:1518`), `announce_video_enabled` (`lib.rs:1559`), Kotlin sink `bridge.sendVideoAu(au)`.
- Produces: after `signal_video_stopped` → `announce_video_enabled`, AUs pushed via `send_video_au` reach the engine again without any peer-side state change.

**Evidence:** user report 2026-10-13 — our camera off→on sends nothing; it recovers only after the PEER cycles video (their 6→1 rebuilds the media plane). Working hypothesis: the engine-side consumer of `video_tx` (bounded(4)) is dropped/closed on our `signal_video_stopped`, so post-announce AUs hit `TrySendError::Closed` → `Err(NoActiveCall)` → Kotlin's `getOrDefault(false)` swallows it silently (`WaCallEngine.kt:1020`).

- [ ] **Step 1: Write the failing test**

Extend the `send_video_au` test module (`wa-bridge/src/lib.rs:~2252`, which drives a real call handle):

```rust
#[test]
fn video_source_rearms_after_stop_and_announce() {
    let bridge = /* real-call attach, see the announce_video_enabled test at lib.rs:~2355 */;
    assert!(bridge.send_video_au(au()).is_ok());          // streaming works
    bridge.signal_video_stopped().unwrap();               // camera off, mode kept
    bridge.announce_video_enabled().unwrap();             // camera on
    // the exact re-arm call Task 4 pins (start_video or nothing) goes here
    assert!(bridge.send_video_au(au()).is_ok(), "AU must reach the engine after our own off→on");
}
```

- [ ] **Step 2: Run it — expected FAIL**

```bash
cd calls/rust/wa-bridge && RUSTUP_TOOLCHAIN=nightly-2026-06-16 cargo test video_source_rearms -- --nocapture
```
If it PASSES at the bridge level, the bug is one layer up (engine drops the source on Stopped) — move the test to the facade/driver level and re-run before fixing.

- [ ] **Step 3: Fix — re-arm the source on announce (or keep the channel alive)**

Preferred shape: `announce_video_enabled` re-opens/reattaches the engine's video source so the existing `video_tx` keeps working; alternative: `signal_video_stopped` leaves the source channel open (frames simply don't arrive — the engine already tolerates silence). Adapt after step 2's diagnosis; no speculative code.

- [ ] **Step 4: Kotlin: stop swallowing the failure**

`WaCallEngine.kt:1020`:

```kotlin
sink = { au ->
    runCatching { bridge.sendVideoAu(au) }
        .onFailure { Log.w(TAG, "video AU send failed: ${it.message}") } // one-shot marker, not per-frame spam: only logs on error
        .getOrDefault(false)
},
```

- [ ] **Step 5: Suite + build + patch + commit**

```bash
RUSTUP_TOOLCHAIN=nightly-2026-06-16 cargo test && tools/build --dir calls :tool:assembleDebug
git -C calls add rust/whatsapp-rust rust/patches server
git -C calls commit -m "calls: our camera off->on resumes outbound video"
```

- [ ] **Step 6: Device verification (user-driven)**

LP3↔emulator call: toggle OUR camera off→on → the emulator callee sees our video return within ~1 s, no peer-side cycling needed. Then the combined drill: our off→on AND peer off→on in the same call, checking rotation (Task 3) and resume (Task 4) together.

---

## Verification matrix (final gate)

| Scenario | Expected |
|---|---|
| WS blip (network toggle) mid-call | Call survives, media resumes ≤5 s |
| Peer camera off→on | Black on stop, correct rotation on resume |
| Our camera off→on | Peer sees our video again ≤1 s, no peer cycle needed |
| Genuine peer rotation portrait↔landscape | Rotation applies immediately, (k+1)×90 CW |
| Battery sanity | No new per-frame logs (logcat check: no `video AU send failed` when healthy) |

---

## Task 1 findings (2026-10-13) — transport-blip teardown owner

Evidence recap: 20:59:28 call died as WS read error (os error 103) → "Transport disconnected; reconnecting" → reconnect OK 1 s later ("Handshake complete (IK)") → `webrtc_sctp::association: [wa-voip] failed to write packets on net_conn: conn is closed` ×9. The ×9 is not an independent media failure — the signaling disconnect path explicitly closes the media conn.

### (a) Teardown owner — exact call chain

1. `whatsapp-rust/src/client/node_io.rs:252` — `warn!("Transport disconnected: {reason}; reconnecting.")`; `node_io.rs:253` returns `Err(ReadLoopError::Transport(reason))` from `read_messages_loop`.
2. `whatsapp-rust/src/client/lifecycle.rs:545` — `Client::run()` consumes it in the `Err(e)` arm (`lifecycle.rs:616-626`, converts to an unexpected-disconnect reason), then calls `self.cleanup_connection_state().await` at **`lifecycle.rs:629`**.
3. `cleanup_connection_state` → `cleanup_connection_state_inner` (`lifecycle.rs:1100`). At `lifecycle.rs:1153` it clears `is_connected`, then at **`lifecycle.rs:1156-1162`** (feature `voip-runtime`):
   - **`self.call_registry.abort_all()`** — `lifecycle.rs:1158`
   - **`crate::voip::facade::drain_pending_outgoing_on_disconnect(self)`** — `lifecycle.rs:1161` (impl `whatsapp-rust/src/voip/facade.rs:1582-1593`, drains `pending_outgoing_calls` and notifies each `ended`).

   The in-code rationale (`lifecycle.rs:1154-1155`): *"Tear down every in-flight VoIP call: the relay socket and signaling are connection-scoped, so a call can't survive a disconnect/reconnect."*
4. `CallRegistry::abort_all` — `whatsapp-rust/wacore/src/voip/registry.rs:2715-2728`: clears ringing + pending group controls, then **drains every `CallEntry`** off-lock; each entry's Drop tears the call down.
5. `CallEntry::drop` (`registry.rs:369-376`) notifies `group_update_event` and fires `video_teardown`; field drops do the rest:
   - `media_task: Option<AbortHandle>` (`registry.rs:227`) drops → `AbortHandle::drop` (`wacore/src/runtime.rs:147-150`) **aborts the spawned `run_call` driver task**.
   - `on_terminal: Option<EndedNotify>` (`registry.rs:282`, set via `set_ended_notify`, `registry.rs:1728-1746`) drops → fires the consumer's ended-wake.
6. The aborted future is `run_call` (`wacore/src/voip/driver.rs:821`; doc `driver.rs:815-820`): aborting drops it → drops `transport: Arc<dyn RelayTransport>` → **`RelayMediaChannel::drop`** (`whatsapp-rust/src/voip/transport.rs:119-135`) spawns `assoc.close()` on the runtime (the async twin is `RelayMediaChannel::disconnect`, `transport.rs:268-282`, teardown comment at **`transport.rs:274`**, reached from the driver's own exit path `driver.rs:1375 transport.disconnect().await`).
7. `assoc.close()` closes the SCTP association **including `net_conn`** (the DTLS/UDP socket, `Arc<dyn webrtc_util::Conn>`, built at `transport.rs:184`). webrtc-sctp 0.17.2's association write loop then logs `failed to write packets on net_conn: conn is closed` (`webrtc-sctp-0.17.2/src/association/mod.rs:517`) — the ×9 in the log. The run loop's reconnect (`lifecycle.rs:641-669`, immediate on the first pass) succeeds 1 s later, but the call is already gone.

**Owner, one line:** `Client::cleanup_connection_state_inner` → `call_registry.abort_all()` at `whatsapp-rust/src/client/lifecycle.rs:1158` (with `drain_pending_outgoing_on_disconnect` at `lifecycle.rs:1161`) aborts the media task, whose transport Drop closes `net_conn` (`transport.rs:119-135` / `transport.rs:274-281`).

### (b) Does call state survive reconnect?

**No.** A signaling disconnect force-terminates every active call:

- The registry entry is **removed** (map drained in `abort_all`), so `generation_of`/`snapshot` return `None` afterwards.
- The media task is **aborted** (AbortHandle drop), which drops the engine + `RelayMediaChannel` and closes the UDP/DTLS/SCTP `net_conn`.
- The `CallEventQueue` (`event_tx`, `registry.rs:238`) is dropped → the consumer's event channel closes; `on_terminal` fires the shared `EndedFlag` (`facade.rs:3012-3031`), so `CallHandle::wait_ended()` resolves.
- A consumer-held `CallHandle` clone (`facade.rs:3037-3059`) survives as a Rust object but is terminally dead — there is no re-registration or re-attach path. Dormant (pre-relay) outgoing calls are drained and notified by `drain_pending_outgoing_on_disconnect` (`facade.rs:1582`).
- This is deliberate, pinned by the test `cleanup_during_connect_gap_aborts_the_spawned_task` (`facade.rs:6620-6621`) and the comment at `facade.rs:6616-6619`.

### (c) Minimal fix shape for Task 2

**Chosen shape: keep `net_conn` alive across the reconnect — gate the teardown, don't re-signal.**

- **Gate the exact call:** `self.call_registry.abort_all()` at `whatsapp-rust/src/client/lifecycle.rs:1158` (and the `drain_pending_outgoing_on_disconnect` at `lifecycle.rs:1161`) must be skipped on the *transient blip* path and kept on *terminal* paths. `cleanup_connection_state` has exactly **two callers**: the run loop at `lifecycle.rs:629` and `Client::disconnect()` at `lifecycle.rs:943`. Note that `reconnect()` (`lifecycle.rs:1006-1008`) and `reconnect_immediately()` (`lifecycle.rs:1041-1043`) only call `transport.disconnect().await` — they do **not** call `cleanup_connection_state` directly; they funnel into the `:629` caller with `intentional_reconnect` / `expected_disconnect` set (the loop classifies those exits as `Ok(ReadLoopExit::Expected)` → `unexpected_disconnect == None`, `lifecycle.rs:601-627`). So the *transient* predicate must be evaluated **inside the `:629` path** — skip the `voip-runtime` teardown block iff the read-loop exit was unexpected (`unexpected_disconnect.is_some()`, i.e. not `Expected`/`ServerRecycle`-during-expected) **and** the client is still running (`is_running == true`, false after `Client::disconnect()`/`stop()`) **and** auto-reconnect is enabled (`enable_auto_reconnect == true`). The auto-reconnect condition is not optional: an *unexpected* exit with `enable_auto_reconnect == false` reaches the `:629` cleanup with `is_running` still **true** (`stop_supervision_loop()` only runs at `lifecycle.rs:643`, *after* cleanup), so without it the predicate classifies that terminal exit transient → teardown skipped → the loop breaks at `:644` and `run()` exits via `lifecycle.rs:695-697` without another `cleanup_connection_state`, leaving a live registry entry + open `net_conn` with dead signaling. This correctly treats:
  - a genuine blip (`ReadLoopError::Transport`, auto-reconnect on) → transient, call survives;
  - `reconnect_immediately()` (sets `expected_disconnect = true`, `lifecycle.rs:1026`) → **transient, call survives** — a deliberate reconnect must NOT abort the call;
  - `Client::disconnect()` (`lifecycle.rs:897` → `:943`, sets `is_running = false`) → terminal, teardown runs.
  - Unexpected exit with `enable_auto_reconnect == false` (`lifecycle.rs:641-644` breaks the loop) → terminal, teardown runs — see the hole note above.
  Rider: if the shutdown notifier fires during backoff following a transient skip, `run()` exits (`lifecycle.rs:688-697`) with the call still registered — covered in practice because a proper `Client::disconnect()` always runs cleanup at `lifecycle.rs:943`.
  Mitigating fact (review finding): wa-bridge never calls `reconnect`/`reconnect_immediately` (no matches in `wa-bridge/src/lib.rs`), so the only real blip path today is the unexpected run-loop error at `lifecycle.rs:629` — Task 2 must either treat `reconnect_immediately` as transient (per the predicate above) or explicitly document it as unused mid-call before shipping the narrower `!expected_disconnect`-only gate.
- **Why no re-signal is needed for a short blip:** the media `net_conn` is a per-call UDP socket to the relay endpoint built by `RelayMediaChannelFactory` (`transport.rs:184`, `transport.rs:316`) — independent of the signaling WS. SRTP/WARP keying was negotiated before the blip; nothing on the media path reads WS state. Once the WS reconnects (IK handshake, ~1 s), signaling stanzas flow again on the same `Client`/registry the engine already holds (engine reaches signaling via the client it was constructed with), so the call can continue without renegotiation.
- **Task-2 must-haves (constraints, from this investigation):**
  1. Terminal teardown must still reap calls: if the reconnect ultimately fails (`enable_auto_reconnect` off, `disconnect()` called, pairing lost), the gated block must run — otherwise a dead call leaks a UDP socket and a registry entry.
  2. Stanzas sent by the peer during the gap are lost (no offline queue for call signaling): a peer hangup/re-invite landing inside the blip will not be seen after reconnect. Accept for ≤ a few-second blips; a longer outage needs an explicit "connection lost, call may be stale" surface event (Kotlin side) rather than silent continuation.
  3. The `facade.rs:6616-6621` contract (cleanup during the relay `connect()` gap aborts the just-spawned task) changes on the transient path — that test and the `lifecycle.rs:1154-1155` rationale comment must be updated to the new semantics.
- **Also pinned (minor, so Task 2 doesn't re-derive):**
  - The driver already has a **relay-level reconnect path** independent of the WS: `prepare_relay_reconnect` (`wacore/src/voip/driver.rs:678`, called at `driver.rs:962` and `driver.rs:1685`) drains the send queue/preserves video state, then `current_transport.reconnect(endpoint)` (`driver.rs:998`) dials a fresh relay channel via `RelayMediaChannel::reconnect` → `RelayMediaChannelFactory::connect` (`whatsapp-rust/src/voip/transport.rs:284-294`). Task 2's keep-alive work should build on this path, not invent a second one.
  - `CallRegistry::abort_all`'s doc comment (`wacore/src/voip/registry.rs:2713-2714`, *"Call this from your own disconnect/reconnect teardown; it is not wired into the client."*) is **stale** — it IS wired into the client at `lifecycle.rs:1158`; Task 2 should fix the comment when touching the gate.
