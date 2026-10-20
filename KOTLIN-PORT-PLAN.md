# Kotlin-native port — possibility plan

**Status:** possibility only — not started. Date: 2026-10-16.
**Premise:** the Rust `:server` (.so + UniFFI) can never reach the Light Tool Library
submission pipeline — it extracts only `tool/` and builds against upstream light-sdk.
A pure-Kotlin stack is the only route from sideload to Library. This doc sizes and
plans that port; the shipping Rust stack (`SPEC.md`, live-call gates passed 2026-09-19)
remains the reference implementation and fallback.

## 0. Decision gates — before any porting code

The port is ~4 months; these are a few emails. Run them first — a "no" anywhere
kills the project cheaply.

1. **Upstream dependency allowlist.** Submission builds against Light's upstream
   `allowed-dependencies.txt`; our fork's lines are LOCAL PATCHES only. Needed
   upstream: `org.signal` (libsignal-android — native AAR; precedent: upstream-
   allowlisted `anki-android-backend` is also a native lib), desugaring,
   `com.squareup.wire` or protobuf-javalite, a WS/HTTP client (okhttp/ktor).
   Path: publish the Kotlin WhatsApp stack as a Maven artifact
   (precedent: `com.github.fenleon:light-keyboard` is upstream-allowlisted) and
   file the allowlist ask. Ask via `light-comment.txt`.
2. **Platform capability.** `RECORD_AUDIO` is in the tool permission allowlist
   (`LightToolMetadata.kt`) and `detached-audio` capability ships
   `LightAudioService` for background audio. Open question: does a live-call
   foreground-service pattern fit the vetted-tools model the way Audiobooks'
   playback does?
3. **Light's policy on a calls tool.** WhatsApp voice calling on the "calm
   device" is a judgment call for Light, not a technical one. Same ask as (1).

## 1. Port surface (measured 2026-10-16, non-test lines)

| Chunk | Source | Lines | Port mode |
|---|---|---|---|
| Call engine | `whatsapp-rust` `src/voip` + `wacore/src/voip` (facade, engine, registry, media glue) | 55K | hand port, Rust source as spec |
| Session foundation | `src/socket`, `src/handshake`, `src/client`, `wacore` session/protocol/pair/prekeys/store | 44K | hand port; libsignal-android replaces the ~15–20K crypto core |
| Media stack | `whatsmeow-voip` Go MLow/RTP/crypto port | 17K | Go→Kotlin port, **test vectors already exist** |
| Wire schema | `waproto` | 1.1K proto | codegen (protobuf/wire + whatsmeow `.proto` files) |

**Net new Kotlin: ~85–100K lines.** The existing `calls/` Kotlin layer (~9K:
AudioRecord/AudioTrack pacing, MediaCodec, CSD-prepend, event mapping) carries
over almost unchanged — the rewrite replaces only the `:server` Rust .so behind
the same seams.

Context: open-madrid is ~15K non-test lines (harder per line — hand-rolled
crypto/registration, no reference); this is ~5–6x the volume but mostly
mechanical, with two working oracles (whatsapp-rust + our own Go port).

## 2. Phased plan

| Phase | Scope | Estimate |
|---|---|---|
| **P0 — gates + spike** | Light asks (§0); pairing + Noise XX handshake + WS keepalive in Kotlin against the emulator, no real account. Proves the hardest unknown (session layer) before commitment. | 2 wk |
| **P1 — session layer** | Linked-device pairing (QR/pair-code), Noise handshake, socket + keepalive, signal sessions via libsignal-android, local session store (Room — tool-model sanctioned). | 2–4 wk |
| **P2 — wire + proto** | Binary node-tree codec (small, whatsmeow-documented); waproto codegen; stanza/IQ request-reply plumbing. | 1–2 wk |
| **P3 — call signaling engine** | Port voip facade/engine as a Kotlin state machine (~15K core lines: offer/answer, ringing, accept/reject, ICE relay allocation, video upgrade tokens), cross-checked against the Go port's behavior. | 3–5 wk |
| **P4 — media** | MLow codec + RTP + jitter buffer Go→Kotlin (test vectors gate it); mic/playout 60 ms pacing on Kotlin threads (same pattern as today); audio first, video (M3) after. | 3–6 wk |
| **P5 — integration + gates** | Re-run the M-gate battery from `SPEC.md`: double-talk AEC loopback on LP3, relay allocation, live calls, video on-device. | 2–4 wk |
| **P6 — packaging + submission** | Extract the stack into the publishable artifact; `tools/gate calls` against pristine upstream; Tool Library submission prep. | 1–2 wk |

**Total: ~3–4 months focused work** to live-call parity.

## 3. Risks

- **Protocol churn (the real cost).** WhatsApp breaks unofficial clients
  periodically; upstream whatsapp-rust absorbs those fixes and we pull them
  today. A Kotlin port means we absorb them alone, permanently. This dwarfs
  the initial build cost over any 2-year horizon.
- **Ban exposure.** Unofficial clients violate WhatsApp ToS (see `SPEC.md` §0
  account-model note). A from-scratch client has a fresher fingerprint than
  whatsapp-rust; same mitigation — secondary number, no auto-retry.
- **Policy risk.** §0 gate 3 — Light may simply decline a calls tool.
- **AEC/HAL parity.** Not port-specific (same `setCommunicationDevice()` +
  double-talk loopback risk as the Rust build, `SPEC.md` §3) but re-verified
  in P5 regardless.

## 4. What we'd keep

- The `calls/` Kotlin layer as-is behind the existing seams (audio/video glue,
  event enum, pacing model).
- The Rust build as the live oracle for debugging a porting bug (diff
  behaviors side by side).
- `whatsmeow-voip` as the media reference — it was drafted from the same Rust
  stack and carries test vectors.
- The tool model itself: P6 targets a single scanned `tool/` module with an
  artifact dep — no `:server`, which is the entire point.
