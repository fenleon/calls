# PLAN — Calls next steps

Status lives here; findings/history in the root `WORKLOG.md` (2026-09-22 entries).

## 1. Outgoing hang-up does not stop the peer's ringing (top priority)

Symptom: cancel (×) a still-ringing outgoing call on the LP3 → the callee's phone
keeps ringing until its own no-answer timeout (~45–60s, shows missed call).
Incoming decline (`reject`) **works** — the peer stops ringing.

Evidence (LP3 stanza captures, 2026-09-22 — rust logging now live):
- Our terminate is shape-identical to a real client's caller-cancel:
  `<call to=peer-lid id=…><terminate call-id call-creator=own-lid-dev6/></call>`,
  sent successfully (`terminate ok`), local teardown correct.
- The server sends **no reply** to a terminate — nothing to correlate.
- JID-form ruled out: the peer's decline of our outgoing call carries
  `call-creator = 5909992489192@lid (dev 6)` — the lib's offer already uses
  `call_creator = own_lid` (`facade.rs`, `start()`).
- **Leading hypothesis — per-device fan-out:** call signaling routes per device
  (see `TerminateParams` doc in `wacore/src/stanza/call.rs`); our terminate goes
  to the callee's user-level LID (device 0) while the offer fanned out to each
  callee device (`ring_devices`).

Next: store the callee `devices`/`ring_devices` on the `CallHandle` at attach;
`terminate()` sends one terminate per device JID. Verify on the LP3
(dial → cancel while ringing → peer stops). Fallback: diff against a WhatsApp
Web cancel wire-capture.

**Test rig (from the next session):** two independent linked clients —
the user's second WhatsApp account linked on the **LP3**, another WhatsApp
account linked on the **emulator** — calling each other on demand. This gives
a repeatable outgoing-cancel repro with full rust logs on the emulator side
(`adb logcat` works there, unlike the LP3): place from LP3 → cancel while
ringing → check whether the emulator receives the per-device terminates;
same in the reverse direction. Regression matrix: answer + decline both
directions, ringer + haptics both sides (emulator has no com.lightos — relay
no-ops there, LP3 is the source of truth for relay/haptics).

## 2. Incoming call only rings after the tool is launched

With the process dead, an incoming offer is missed (no bot running) until the
user opens Calls once. Investigate: keep the process/bot alive via the existing
`WaSyncService` FGS across reboots (START_STICKY alone was not enough
post-install), or a `componentToRelaunch`-style wake. Battery-stanced: the sync
FGS already runs — check why the bot was not up (process killed by system?
FGS not restarted?).

Recovery half RESOLVED (2026-09-25, see §7): when the launch itself catches a
still-live offer, it now routes — home routes from the engine's `pendingOffer`
state instead of the event stream, so the mid-launch offer survives the late
subscription. The FGS-not-restarted-across-reboot half stays open.

## 3. Volume rocker during a call

Relayed volume keys surface LightOS's ringer-only panel — call
(STREAM_VOICE_CALL) volume is not adjustable in-call. Options: in-app
volume handling during an active call (audiobooks pattern), or request a
LightOS service method.

## 4. UI polish, pending user confirmation

- Call-log glyph offset `-0.35gu` — bottoms should read aligned; nudge if it
  reads too low/high (measured baseline: glyph bottom == digit baseline).
- Incoming-call centering + duration gap — confirmed or adjust.

## 5. M3 — 1:1 video calls

Planned in `VIDEO-PLAN.md` (phases V0–V4, grounded in the vendored lib's real
video API — SPEC §2/§3.3 corrected 2026-09-23). §1/§2 above land first.

## 6. Battery / lifecycle audit (standing priority)

Audit `WaSyncService` + `WaCallService` FGS CPU/wakeups on the LP3 now that the
relay and ringer run at bootstrap (the standing doze-disabled caveat applies).

## 7. Stuck "call ended" panel (fixed 2026-09-24)

User report (LP3): after missing a call with the app closed/idle, the next app
entry showed a fullscreen "call ended" panel that never dismissed; only a
restart cleared it.

**Mechanics verified** (light-sdk `sdk/client`):

- `WaCallEngine.events` is a `StateFlow<WaEvent?>` whose value is set for every
  event (engine tail + the direct fake/pairing writes) and never cleared after
  `CallEnded` — every fresh collector instantly replays the last event.
- `LightGlobalRoute.request` is a process-wide `StateFlow<Request?>` holding the
  LAST push; `LightActivity` consumes it once (sets it null) on collection
  (`LightActivity.kt:135-137`). Routes do NOT survive process death —
  `onCreate` always rebuilds the stack from the initial screen
  (`LightActivity.kt:126-129`) — but they DO survive app exit while the process
  lives (`singleTask` activity, stopped-not-destroyed).
- `goBack()` no-ops when `backStack.size <= 1` (`LightActivity.kt:95`); a
  routed panel always sits above the root, so the pop itself was never the
  failing step — the failing step was the stale state that put a dead call's
  panel + stale `CallEnded` replay in front of the user.

**Failure chain**: offer arrives while the tool is closed (process kept alive
by the FGS) → home's always-alive VM pushes the panel; the offer then dies and
`CallEnded` becomes the StateFlow's last value. If the terminal event is lost
(bridge respawn) or arrives out of sight, a later app entry replays the stale
`IncomingCall`/`CallEnded` to the fresh collectors: home routes a panel for a
long-dead offer, and the panel instantly flashes "call ended" from the replay —
with nothing left to dismiss it from.

**Fix** (commit `b028269`):

- `WaCallEngine` now publishes to a replay-free `liveEvents` `SharedFlow`
  (replay=0) alongside the StateFlow. Screens that must never act on stale
  deliveries collect `liveEvents`; consumers that need the replay keep the
  StateFlow — the 60 s placement wait (`place()` + `parkEvents()`), the
  pairing screen's QR rotations, and the active-call screen's `CallStarted`
  replay are untouched.
- `IncomingCallScreen` collects `liveEvents` and closes if the engine already
  dropped the offer before the panel subscribed (covers the route→subscription
  gap the replay used to cover).
- `HomeViewModel` routes `IncomingCall`/`Ringing`/`CallStarted` only while the
  engine still holds the call (`currentCallPeer` check) — a stale replay can no
  longer route a dead panel.
- Engine-side clearing of the StateFlow was considered and rejected: clearing
  `_events` after `CallEnded` can conflate the value away before a suspended
  collector resumes, swallowing the very event `place()`'s 60 s wait and the
  live panels depend on.

**Repro** (emulator, `calls-audit-2026-09-24/stuck-panel/`): force-stop
mid-ring → relaunch shows a clean home (no route restoration after process
death — `03-`/`11-*.xml`); demo call → HOME → re-enter restores the ringing
panel from the live stack (`06-`, pre-fix; `12-`, post-fix — live offers still
restore). Post-fix regression run: answer → hang up → flash auto-dismisses to
home (`08-`/`09-`), re-entry after a dead call is clean (`10-`), decline path
works (`13-`).

Note for the uncommitted WIP in the tree: its demo-expiry tail writes
`_events.value = CallEnded("missed")` directly — switch it to
`WaCallEngine.publish(...)` (or make it private and route through `publish`)
so the demo expiry also reaches `liveEvents` collectors.

**Fix-up (2026-09-25, cold-start regression in the original fix):** routing the
panel from the events stream was fragile in BOTH directions — replay delivers
stale events (the stuck flash) and, worse for cold starts, a mid-launch offer
was routinely ALREADY replaced in the StateFlow's last value (history-sync
imports, the caller's terminate) before home's late-subscribing collector read
it, so the panel never appeared (LP3 2026-09-25 00:34 live trace). Home now
routes from `WaCallEngine.pendingOffer` — a `StateFlow<WaEvent.IncomingCall?>`
that holds the offer for exactly as long as it lives (set in the reducer's
`IncomingCall` branch, cleared on accept/`CallStarted`, reject, `MissedCall`,
`CallEnded`, `dropCurrentCall`, and in the fake-call direct-publish paths).
`IncomingCallScreen` anchors its staleness close on `pendingOffer` (guarded so
it never pops the active-call screen that replaces it on accept); the
placement wait, `parkEvents`, pairing QR replay and the active-call screen
stay on the untouched `events` StateFlow. Emulator evidence
(`calls-audit-2026-09-24/cold-start/`): demo panel routes (a1); with the
activity destroyed but the process kept alive by the FGS, a fresh launch
routes the offer set before the VM existed (a2); with the offer cleared, a
fresh launch shows clean home (a3); accept → active panel intact (a4),
hang-up → flash dismisses to home (a5).

## 8. M3 video — live-call feedback (2026-09-25, LP3 ↔ official WhatsApp)

Working as of this session: signaling correct both directions (LP3 signals 3 = front
sensor 270; raw base confirmed — the official device renders the LP3's stream upright);
receiver rotation now visually applies (TextureView, def3119); cold-start incoming
routing works when the bot is up. Open items below, ordered roughly by severity.

1. **Fresh install: no incoming call at all.** *(code landed 2026-09-25; live
   verify pending)* Root: the sync FGS only ever started from the
   ContentProvider — nothing restarted it after `adb install -r` or a reboot
   (START_STICKY doesn't survive either). Fix: `WaBootReceiver`
   (`BOOT_COMPLETED` + `MY_PACKAGE_REPLACED` → `WaSyncService.tryStart`); a
   first-ever install still needs one app launch (Android sends the app
   nothing on first install). WS-connect-ready vs offer-arrival timestamps
   still to be logged on the next live repro.
2. *(renumbered from §3 below — see video-from-start)*
3. **Video-from-start toward official.** *(root cause found 2026-09-25; fix
   landed; live verify pending)* The offer parser is CORRECT —
   the captured official iPhone offer carries `<video enc=…/>` and parses
   `is_video=true`. Answering a video offer seeds `self_state=Enabled`
   whether or not a local plane attached, which the old registry refused on
   later toggles ("video transition already in progress", lp3-retest.log
   11:33:10) — fixed in the registry (item 8). The audio-only answer
   fallback (`WaCallEngine.kt:338` / bridge `accept_call` gate) is by design.
4. **Camera off→on toggle never re-establishes.** *(superseded 2026-09-25
   evening by the WhatsApp video model, §8.9 below)* The original toggle
   race (stop/start halves on `Dispatchers.IO`, stale echo teardown) was
   fixed (captured-link stop + `videoEpoch` guard), but the whole toggle
   semantics changed with the model: the toggle is now the CAMERA on/off
   inside an open video mode, not a mode switch.
5. **Rotation: three of four directions reorientate; clockwise doesn't.**
   *(RESOLVED 2026-09-25, calibration from the captures — no code change)*
   The 0→1 in the 11:09 capture is not a rotation: official's video module
   was off (state-0, default orient 0) and came back ON at portrait
   (official's home signal = 1) — nothing was ever on screen to reorient,
   and no wire 2 ever appeared in that call. Calibration (official sender):
   portrait=1, ±90°=2/3, 270°=4 (UNMASKED — the rust `&0x03` mask is
   required and correct). The committed convention is right: sender
   `(front + ui) mod 4` raw, receiver `rotationZ = orientation * 90f` raw,
   no complement anywhere. LP3-side mid-call rotation is dead code on-device
   (LightOS is portrait-locked — the DisplayListener never fires).
6. **Aspect ratio: squash/stretch instead of crop.** *(fix landed
   2026-09-25; verified on the emulator decode-test screen)* Frame size from
   the decoder's output format (`VideoDecodeLink.onFrameSize` →
   `peerFrameSize`; `getDefaultBufferSize` is not public API) and both the
   call screen and the decode-test screen apply an aspect-preserving cover
   scale (`max(w/sw, h/sh)`, center pivot, rotation swap on odd steps). No
   stretch at any rotation.
7. **Low FPS when the mic is on.** Open — needs live measurement (media_stats
   counters + `OutboundMediaDropped` while toggling mic on the LP3).
8. **Our-initiated video never sends.** *(REFRAME 2026-09-25: the initiator
   flow has NEVER worked — the morning "working" video was the acceptor
   role only.)* The subagent diff of lp3-retest.log vs lp3-verify.log:
   every morning LP3-initiated call had its encoder killed ~500 ms in by the
   old state-6 handling (encode stop "0/1 AUs"), before the acceptor's gate
   ever opened; the two-way morning video was peer-initiated (LP3 =
   acceptor, `VideoControl::Enable` from the start). After the registry +
   toggle fixes, the initiator streams valid H.264 (AU dump ffprobe-clean,
   NAL layout identical to the V1-validated capture) after the peer's
   auto-accept (state 4) — and the official responder STILL doesn't decode
   it: PLI storm (~450 ms), never renders, never sends its state-1, reverts
   (~8 s) with `<terminate>`. Experiments: (a) registry plane-gate fix
   (removed the false "transition already in progress"), (b) ungate the
   initiator immediately (EnableAwaitingAccept → Enable, facade.rs:3591) —
   NO difference, so the gate window is not the culprit; the gap is
   handshake-side (hypothesis: the official responder needs the initiator's
   full request shape — the official's own state-11 carries
   `dec="H264,H265,AV1"` + `screen_width/height`, ours carries
   `dec="H264"` only; `voip_settings="video"` is already sent).
9. **WhatsApp video model (2026-09-25 evening, user insight — implemented,
   build green, NOT yet on-device).** Our "upgrade?" prompt was nonstandard
   and blocked handshakes (the rig test died in state 9 =
   UpgradeCancelByTimeout while the prompt sat unanswered). WhatsApp: no
   prompt — the upgrade auto-accepts, video MODE opens (peer's video
   renders), each side's CAMERA is independent. Implemented: rust facade
   `signal_video_stopped()` (state-6 camera-off that KEEPS the plane, vs
   `stop_video` full teardown) + bridge wrapper; Kotlin `videoMode` flag —
   peer state 3/11+token auto-accepts silently (acceptVideo machinery,
   no encode), 4 opens the mode, 1/6 (peer camera) only affect decode,
   reject/cancel/timeout collapse the mode; toggle = camera on (announce +
   media, no re-upgrade when the mode is open) / off (signal_video_stopped,
   decode keeps running); `UpgradeRequested` state, the prompt row and
   `acceptVideo`/`declineVideo` removed.

Next session: install the WhatsApp-model build on the LP3 + emulator
(APK built, NOT installed), then: (1) rig test — emulator places audio
call, LP3 answers + taps video (initiator); the emulator now auto-accepts —
if IT decodes the LP3's initiator stream, our sender is fine and the
official responder needs the stanza-shape enrichment (mirror the official's
`dec` + screen dims on state-11); if not, our initiator sender path is
buggy and debuggable locally. (2) Then the official phone matrix: LP3
initiator, video-from-start both directions, camera toggles both sides.
(3) Then item 7 (mic-on FPS) and item 6 rotation checks.
