# Calls

Calls is a phone app for the Light Phone III that works with WhatsApp. Link it
to your WhatsApp account once — the same way WhatsApp Web links to your phone —
and after that real WhatsApp calls ring on the Light Phone.

Today Calls handles **1:1 voice calls**: incoming and outgoing, end-to-end
encrypted, interoperating with the official WhatsApp apps on the other end.

Roadmap:

- **Video calls** — the call transport already supports it; the remaining work
  is camera and video rendering on the phone.
- **Group calls** — the underlying library already implements them; exposing
  them in the tool's UI is the remaining work.

## How it works

Calls is a single Android app built from three layers:

- **A Rust core** ([whatsapp-rust](https://github.com/oxidezap/whatsapp-rust))
  does the heavy lifting: it connects to WhatsApp as a linked device and
  implements the entire call path — signaling, encryption, the voice codec,
  and relay transport. It is pure Rust with a permissive (MIT) license.
- **A thin bridge** (UniFFI) exposes that core to the Android side: call
  commands go in, call events and raw microphone/speaker audio come out.
- **The Kotlin app** handles everything the user touches: the screens, the
  microphone and speaker, battery-friendly background connection, and call
  history. It follows the [light-sdk](https://github.com/lightphone/light-sdk)
  tool model for LightOS.

## Disclosures

- Calls is an **unofficial** client. It is not affiliated with, endorsed by, or
  connected to WhatsApp or Meta.
- Per the underlying library's own disclaimer: *"Using custom WhatsApp clients
  may violate Meta's Terms of Service and could result in account suspension.
  Use at your own risk."*
- Calls links to your account as a companion device and processes your call
  audio locally on the phone. No call content, credentials, or session data
  leave the device except toward WhatsApp's own servers.

## Building

Prerequisites:

- JDK 17 toolchain and the Android SDK — use the workspace `tools/env.sh`
  (or install your own; `compileSdk` 36, `minSdk` 34).
- Android NDK `29.0.14206865`.
- rustup with the `aarch64-linux-android` and `x86_64-linux-android` targets,
  plus `cargo-ndk` (`cargo install cargo-ndk`).
- **A sibling checkout of the light-sdk** — the Gradle build composite-includes
  it as `../light-sdk`:

  ```bash
  git clone https://github.com/lightphone/light-sdk   # next to this repo
  ```

Then, from the workspace root (or anywhere with `gradlew` + env active):

```bash
tools/build --dir calls :app:assembleDebug
```

The `:server` `buildRust` task cross-compiles the `wa-bridge` crate
(cargo-ndk, both ABIs) and regenerates the UniFFI Kotlin bindings into
`server/src/main/java/com/lightphone/calls/server/bridge/`. The committed
`.so` files under `server/src/main/jniLibs/` and the generated bridge Kotlin
are deliberate — they are checked in so the project builds without a Rust
toolchain once native artifacts are up to date.

`rust-toolchain.toml` pins a nightly toolchain per-crate (required by
`wacore-binary`'s `simd` feature); stable Rust fails to compile the bridge.

## Install / run (emulator)

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.lightphone.calls/com.thelightphone.sdk.LightActivity
```

See `SPEC.md` for the architecture and protocol notes.
