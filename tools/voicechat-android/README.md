# Simple Voice Chat Android Multiplayer Patch

Mobile revision: **android-2.6.27-r1**. Targets the official Simple Voice Chat 2.6.27
Fabric 1.21.1 and Forge 1.12.2 artifacts. Intended for multiplayer voice on
Android arm64-v8a, Android 8.0/API 26 or newer, using an Android Java launcher.
Desktop clients can use the official mod. The voice network protocol, codec
settings, model weights, server code and mod metadata are unchanged.

## Mobile changes

- Adds Android Opus 1.5.2 / opus4j 2.1.3, with baseline ARM64 NEON, to avoid the
  Java codec fallback when the native library loads. RNNoise 2.1.3 and Speex4j
  1.0.3 remain available for noise suppression and automatic gain control.
- RNNoise JNI scratch buffers belong to each instance and are reused. There are
  no wrapper malloc/calloc calls during steady-state frame processing. Voice
  activity detection alone skips unnecessary PCM conversion and array writes.
- Checks wrapper/JNI allocation and I/O failures and releases partial objects.
  Buffer-model construction, state allocation, model parser calloc/realloc and
  error cleanup have targeted failure checks. This does not claim complete
  out-of-memory safety for every upstream native library.
- Preserves r2 fixes for model cleanup and invalid sparse indices/counts.
- On Android only, connecting to a server no longer checks optional MP3/LAME as
  a requirement for voice. Opus/RNNoise/Speex failures still report normally.
  MP3 recording remains unsupported; LAME capabilities and recording code are
  not falsified or replaced. Desktop connection checks keep original behavior.

Only 4 original classes change: the RNNoise, Speex and Opus library loaders and
ClientNativeManager. Eight entries are added: the platform helper, three Android
SO files, three dependency license texts, and combined source provenance. All
other original archive entries are preserved byte-for-byte. Desktop libraries
are retained, but these packages are supplied for the user's phone installation.

## Install on a phone

1. Choose the matching game version/loader under `dist/current/mods` and replace
   the old voicechat JAR in that phone game instance's `mods` directory. Keep only
   one voicechat JAR. Restart the game.
2. Grant the launcher microphone permission. Keep `use_natives=true` in the
   instance's `config/voicechat/voicechat-client.properties`; it is the official
   default and enables native Opus, denoising and automatic gain control.
3. If Fabric's OpenAL microphone is problematic, users of the repaired FCL Java
   Sound backend can try `java_microphone_implementation=true`. This is optional;
   no microphone setting is forced or overwritten. Forge 1.12.2 already uses
   Java Sound. A functioning launcher audio backend is still required.
4. Keep the existing compatible official server plugin/mod and PC mod. Test a
   two-way call with the PC, then several people speaking, muting/unmuting, and
   headset/background transitions. Phone Bluetooth and background behavior depend
   on the launcher and Android device.

No phone was connected during development. Android JNI execution, capture and
playback, latency, CPU use, heat and battery consumption still require phone tests.
Host tests demonstrate specific behavior, not measured phone performance gains.

## Reproducible build

Run from the SimpleVoiceChat root with PowerShell:

```powershell
./tools/voicechat-android/build-mobile.ps1 `
  -FabricOriginal <official-fabric-1.21.1-2.6.27.jar> `
  -ForgeOriginal <official-forge-1.12.2-2.6.27.jar> `
  -OutputDirectory <new-output-directory>
```

The builder checks the official original JAR hashes, downloads hash-pinned native
sources, uses a fresh build directory, compiles and strips all three Android
libraries, runs regression checks, and packages both variants. It refuses an
existing output directory. A completed directory is published only after both
packages and its receipt are ready; failed staging is marked `.partial`.
`-SkipDownload` uses already prepared source trees. Native build configuration has
no network downloads. `sources.json` inside each JAR records upstream revisions,
local patch hashes, and the Android native library hashes.

Default dependencies are the shared Android SDK/NDK under
`D:/CodeX/PluginsMaker/VersionSwitch/.build-tools/android-sdk`, NDK 27.0.12077973,
CMake 3.22.1/Ninja, Java 21, Java 8 for Forge smoke tests, Node, and ASM 9.8.
Override `-AndroidSdk`, `-Jdk`, `-Java8`, and `-Asm` as needed.
`VOICECHAT_ANDROID_SDK` is also respected by standalone native test scripts.
Do not reuse the obsolete moved `.tmp/voicechat-android/native-arm64` CMake cache.

## Validation

- `verify.ps1 -Original <original.jar> -Candidate <patched.jar>` checks exact
  archive differences, duplicate entries, all three platform selectors, packaged
  Android ELF dependencies/16 KB alignment, and all 38 JNI exports. It recompiles
  its Java harness and exercises original Windows RNNoise, Speex and Opus.
  Set `-Java` to Java 8 for Forge and `-Jdk` for javac/javap.
- `verify-test.ps1` with the same two arguments checks that duplicate entries,
  missing Opus files, corrupt libraries and missing JNI exports are rejected.
- `PatchVoicechatTest <scratch> <official-fabric.jar> <official-forge.jar>` uses
  Java 21 and ASM. It covers safe output publication, preservation, rejected
  input, retries, competing output, and the actual transformed connection checks.
- Set `VOICECHAT_RNNOISE_SOURCE` to the fresh build's `rnnoise/src`, then run
  `test-model.mjs <weights.bin>...` and `test-lifecycle.mjs <weights.bin>...`.
  These compile the real C model/initialization code to Wasm. They validate good
  models, six corruption cases and every allocation failure reached during the
  tested buffer-model initialization path. The model is extracted unchanged from
  each original JAR's `rnnoise/weights_blob.bin`.
- `node tools/voicechat-android/test-rnnoise-wrapper.mjs` compares the optimized
  and original JNI wrappers using deterministic DSP and JNI substitutes: 600
  alternating frames/arrays, 1000 steady-state calls with no wrapper allocation,
  VAD conversion skipping, injected failures, invalid input and cleanup.
- `node tools/voicechat-android/test-opus.mjs <jar>...` compiles the pinned Opus C
  core to Wasm and exchanges packets with Java/Concentus and the JAR's desktop
  native codec. It checks non-silent 100-frame streams in both directions,
  packet loss concealment and invalid packets/frame lengths. For Forge Java 8,
  set `VOICECHAT_JAVA`; `VOICECHAT_JAVAC` selects the compilation JDK.

The Wasm tests do not execute Android JNI or ARM NEON instructions. No performance
percentage is inferred from these tests.

## Files and rollback

- `dist/current/mods`: current 2.6.27 phone packages.
- `dist/backups/2.6.24-before-2.6.27`: previous 2.6.24 r3 packages.
- `dist/backups/2026-10-07-before-r3`: previous r2 packages for rollback.
- `dist/backups/2026-10-07-before-r2`: original Android patch before the audit.
- `tools/voicechat-android`: maintained build/patch source and regression tests.
- `.build-tools/voicechat-android` and `.build-tools/voicechat-opus`: pinned sources.
- `.tmp`: build intermediates and test evidence; not installation files.
- `_apk_decoded`: historical extracted mods/configuration, not this release.
