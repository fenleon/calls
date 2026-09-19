# Calls

1:1 WhatsApp voice calling for the Light Phone III, built as a LightOS tool
(`com.lightphone.calls`) on the [light-sdk](https://github.com/lightphone/light-sdk).

- Linked-device pairing: scan the WhatsApp QR from **Settings → Linked devices**
  — the phone becomes a companion WhatsApp device, like WhatsApp Desktop.
- Calls run through an in-process Rust bridge ([whatsapp-rust](https://github.com/oxidezap/whatsapp-rust)
  `voip`, MIT) exported to Kotlin via UniFFI; sync and audio live in the tool's
  embedded server library.
- **Unofficial client — use a secondary number.** This is not an official
  WhatsApp client; accounts linked to it may violate WhatsApp's Terms of
  Service and be banned. Use a secondary/throwaway number, not your primary.

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
