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

## 2. Incoming call only rings after the tool is launched

With the process dead, an incoming offer is missed (no bot running) until the
user opens Calls once. Investigate: keep the process/bot alive via the existing
`WaSyncService` FGS across reboots (START_STICKY alone was not enough
post-install), or a `componentToRelaunch`-style wake. Battery-stanced: the sync
FGS already runs — check why the bot was not up (process killed by system?
FGS not restarted?).

## 3. Volume rocker during a call

Relayed volume keys surface LightOS's ringer-only panel — call
(STREAM_VOICE_CALL) volume is not adjustable in-call. Options: in-app
volume handling during an active call (audiobooks pattern), or request a
LightOS service method.

## 4. UI polish, pending user confirmation

- Call-log glyph offset `-0.35gu` — bottoms should read aligned; nudge if it
  reads too low/high (measured baseline: glyph bottom == digit baseline).
- Incoming-call centering + duration gap — confirmed or adjust.

## 5. Battery / lifecycle audit (standing priority)

Audit `WaSyncService` + `WaCallService` FGS CPU/wakeups on the LP3 now that the
relay and ringer run at bootstrap (the standing doze-disabled caveat applies).
