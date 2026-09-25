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

1. **Fresh install: no incoming call at all.** Before first app open post-install, an
   incoming call is missed; opening the app did not surface it either. Hypotheses:
   WaSyncService not started on fresh install (no POST_INSTALL trigger — §2 family);
   and/or the offer died before the first WS connect finished (bot up but not yet
   connected when the caller gave up). Needs: install-event receiver or FGS-start on
   update, plus log the WS-connect-ready timestamp vs offer arrival.
2. *(renumbered from §3 below — see video-from-start)*
3. **Video-from-start calls broken toward official.** Official initiates a video call
   → LP3 doesn't receive it as a video call; and toggling video on the LP3 in that
   call returns "video failed". Suspect `VideoNotOffered` handling + the incoming
   `is_video` accept path (bridge `accept_call(with_video)` + builder `.video(...)`)
   never exercised against a real official offer — unit-shaped, never live-tested.
   Note 9e5a342's residual: wacore `video_offer_node`/`video_accept_node` still
   hardcode device_orientation 0 (video-from-start signaling) — relevant here.
4. **Camera off→on toggle never re-establishes.** stop→start on the encode link:
   camera/encoder released on stop but the reopen path doesn't produce frames (LP3
   picture freezes, peer gets no video). V1's stop-race guards (`running` set
   synchronously) may be blocking the second start; or Camera2 reopen fails silently.
   Needs a toggle-cycle test on the emulator + fix.
5. **Rotation: three of four directions reorientate; clockwise doesn't.** Rotating the
   official device clockwise produced no reorientation on the LP3 (the 0→1 signal
   change DID arrive in the 11:09 capture, so the signal works — suspect the receiver
   composition `(front + ui)` mapping: one quadrant maps to the same value, or the
   re-announce debounce eats it). Also unconfirmed whether rotating the LP3 itself
   updates the official device. Calibrate the full 4×4 matrix (both devices' signals
   are in the captures) and fix the composition.
6. **Aspect ratio: squash/stretch instead of crop.** The graphicsLayer full-bleed
   scale stretches odd-step rotations; even steps stretch 4:3 → 1080×1240. Fix:
   aspect-preserving scale = max(target.w/src.w, target.h/src.h) with center crop
   (compute from the decoded size + rotation), on both the video surface and the
   diagnostics screen.
7. **Low FPS when the mic is on.** LP3's outgoing feed drops frames with mic active,
   perfect with mic off. Suspects: VOICE_COMMUNICATION HAL AEC load vs encoder on the
   G99, thread priority contention (AudioRecord URGENT_AUDIO vs camera/encoder
   handler), or shared HAL/camera conflict. Measure with the media_stats counters +
   `OutboundMediaDropped` while toggling mic.
8. **Our-initiated video never sends.** When the OTHER device initiates the upgrade,
   our video flows; when the LP3 enables video (outgoing upgrade via `startVideo`) or
   accepts a video-from-start call, our encoder never starts (peer gets no video).
   Points at the `videoConsent` gating: `acceptVideo`/`startVideo` set it and start
   the encode — but the real-flow entry points (post-state-11 accept, video-from-start
   answer) evidently don't reach the encode start. Trace `videoConsent` through the
   live flow with logs; the V2 consent fix overcorrected for these paths.

Next session: start at 8→3 (both are consent/accept-path bugs, likely one root),
then 4, then 5+6 (receiver rendering), then 7 (perf), then 1 (fresh-install FGS).
