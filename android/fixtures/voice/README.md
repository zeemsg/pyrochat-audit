# Voice-note waveform fixtures (iOS ⇄ Android)

Both apps render a voice note as 32 ASCII bars: iOS `Waveform.bars(for:data:)`
(`ios/PyroChat/Core/VoiceRecorder.swift:146-181`) and its port
`com.pyrochat.app.platform.Waveform` (`android/app/src/main/java/com/pyrochat/app/platform/Waveform.kt`).
The bar arithmetic is identical (per-bar RMS over `n / 32` frames of channel 0, peak-normalised,
0.12 floor); what this directory pins down is the **PCM the arithmetic sees** — the decoders
differ (`AVAudioFile`/CoreAudio vs `MediaExtractor`+`MediaCodec`), and above all in how the AAC
encoder priming/padding is trimmed. The proof runs in both encoding directions:

| file | encoded by | bars computed on iOS | bars computed on Android |
|---|---|---|---|
| `tone-1s-24k-mono-32k.m4a` | Android `MediaCodec` AAC-LC + `MediaMuxer` (`AacFixture.kt`: 440 Hz, 0.8 amplitude for 0.5 s then 0.2) | `tone-1s-24k-mono-32k.ios-bars.json` | `tone-1s-24k-mono-32k.android-bars.json` |
| `ios-tone-1s.m4a` | CoreAudio through `AVAudioFile(forWriting:settings:)` with EXACTLY the `VoiceRecorder` settings (`VoiceRecorder.swift:31-36`: `kAudioFormatMPEG4AAC`, 24 000 Hz, mono, 32 000 bps; 440 Hz at −6 dBFS, silent 0.4–0.6 s) | `ios-tone-1s.ios-bars.json` | `ios-tone-1s.android-bars.json` |

Every JSON carries the SHA-256 of the `.m4a` it was computed for — the tests refuse to compare
bars across different bytes. `*.ios-bars.json` also records CoreAudio's
`kAudioFilePropertyPacketTableInfo` (`coreAudioPacketTable`), the ground truth for the trim rule.

## The decode rule (what made the bars differ)

CoreAudio trims the AAC encoder delay before `AVAudioFile` hands out frames, Android's
`MediaCodec` does not — so before the fix Android saw ~2 bars of leading silence iOS never
shows, and because the bucket width is `n / 32` every other bar shifted too. `Waveform.kt`
(`GaplessTrim`, decode section only) now mirrors CoreAudio exactly:

1. **Gapless metadata present.** A real iOS voice note (AVAudioRecorder/CoreAudio writer) carries
   an `iTunSMPB` atom in `udta/meta/ilst` — and **no `elst`**. `ios-tone-1s.m4a`:
   priming `0x840` = 2112, remainder `0x200` = 512, valid `0x5DC0` = 24 000 frames.
   `MediaExtractor` surfaces `iTunSMPB` (and a single-entry `elst`) as the track-format keys
   `encoder-delay` / `encoder-padding`; Android trims exactly those frames.
2. **No metadata.** `MediaMuxer` writes neither atom. CoreAudio then *assumes* the standard
   AAC-LC encoder delay of **2112 frames and 0 remainder** (`afinfo tone-1s-24k-mono-32k.m4a`:
   `24512 valid frames + 2112 priming + 0 remainder = 26624`). Android applies the same
   constant for `audio/mp4a-latm` when the keys are absent.
3. The keys are **removed from the format before `MediaCodec.configure`** so the trim happens in
   exactly one place, whatever the codec (ACodec / CCodec / OEM) would have done on its own.

The remaining difference is decoder rounding (Android emits 16-bit PCM, CoreAudio Float32);
`WaveformInteropTest` asserts the decoded frame count is identical and every bar agrees within
**1e-3** (0.02 pt at the 18 pt bar height of `ChatView.swift:421`).

## Measured

Run 2026-09-17 — iOS: `AVAudioFile (CoreAudio), iOS simulator Version 26.2 (Build 23C54)`; Android: `MediaExtractor+MediaCodec API 36, c2.android.aac.decoder` (emulator-5554).

| file | frames on iOS | frames on Android before → after | max per-bar delta before → after |
|---|---|---|---|
| `tone-1s-24k-mono-32k.m4a` (Android-encoded) | 24512 | 26624 → 24512 | 0.879 (bar 0; 31 of 32 bars > 1e-3) → 4.5e-06 (bar 11) |
| `ios-tone-1s.m4a` (iOS-encoded) | 24000 | 24000 → 24000 | 6.5e-06 (bar 31; 0 of 32 bars > 1e-3) → 6.5e-06 (bar 31) |

Sample-level check of the pre-fix PCM dumps (`bars.py` lag search, MSE at the best lag):
the Android decode of `tone-1s-24k-mono-32k.m4a` was the iOS decode shifted by exactly
**2112 frames** (MSE 1.0e-10), `ios-tone-1s.m4a` aligned at lag 0 (MSE 6.9e-11) — the
emulator's Codec2 decoder already applies the extractor's `encoder-delay`/`encoder-padding`
keys on its own; `Waveform.kt` now does it explicitly so an OEM codec that does not is equal too
(a double trim would have shown as 21 376 frames; the test asserts 24 000).

Per-bar |Android − iOS| BEFORE the fix (bars 0…31):

| file | deltas |
|---|---|
| `tone-1s-24k-mono-32k.m4a` | 0.8792, 0.8594, 0.2666, 0.0043, 0.0158, 0.0075, 0.0118, 0.0068, 0.0119, 0.0096, 0.0110, 0.0116, 0.0144, 0.0207, 0.0259, 0.2606, 0.6572, 0.0127, 0.0017, 0.0029, 0.0027, 0.0031, 0.0017, 0.0027, 0.0024, 0.0041, 0.0025, 0.0036, 0.0013, 0.0032, 0.0003, 0.0097 |
| `ios-tone-1s.m4a` | 0.0000, 0.0000, 0.0000, 0.0000, 0.0000, 0.0000, 0.0000, 0.0000, 0.0000, 0.0000, 0.0000, 0.0000, 0.0000, 0.0000, 0.0000, 0.0000, 0.0000, 0.0000, 0.0000, 0.0000, 0.0000, 0.0000, 0.0000, 0.0000, 0.0000, 0.0000, 0.0000, 0.0000, 0.0000, 0.0000, 0.0000, 0.0000 |

After the fix every bar of both files agrees within 7e-6 (`deltasVsIos` in the
`*.android-bars.json` files). One existing assertion encoded the untrimmed behaviour —
`WaveformTest.loudHalfIsLouderThanQuietHalf` expected the tail bucket above the floor; with
the trim that bucket is mostly encoder padding and reads 0.12 on BOTH platforms, so the test
now excludes the last bar.

## Regenerating / running

iOS (writes the `*.ios-bars.json` files; `ios-tone-1s.m4a` is encoded only when absent from
the directory, otherwise the existing file is re-verified — a re-encode is not byte-stable
because CoreAudio stamps the creation time into `mvhd`, and the Android bars are pinned to one
SHA-256, so copy the committed one in unless you mean to regenerate the pair. The test host
hangs on files under `~/Desktop` (TCC), so use a directory outside it and copy the results back):

```
cd ios && xcodegen generate && pod install
mkdir -p /tmp/wave && cp android/fixtures/voice/*.m4a android/fixtures/voice/*.android-bars.json /tmp/wave/
TEST_RUNNER_PYRO_WAVE_DIR=/tmp/wave xcodebuild test -workspace PyroChat.xcworkspace -scheme PyroChat \
  -destination 'id=<simulator udid>' -only-testing:PyroChatTests/WaveformFixtureTests
cp /tmp/wave/*.ios-bars.json /tmp/wave/ios-tone-1s.m4a android/fixtures/voice/
```

Android (`WaveformInteropTest` reads the fixtures from the app's external files ROOT and SKIPS
when they are missing — `connectedAndroidTest` uninstalls the app and wipes that directory
after every run, so push first, and push into the root: a directory created by `adb shell
mkdir` belongs to `shell` and the app cannot enter it; it writes `*.android-bars.json` next
to them):

```
adb push android/fixtures/voice/. /sdcard/Android/data/com.pyrochat.app/files/
cd android && source ./env.sh && ./gradlew :app:connectedDebugAndroidTest --tests '*WaveformInteropTest*'
# to keep the outputs, run the class via am instrument after :app:installDebug :app:installDebugAndroidTest and
adb pull /sdcard/Android/data/com.pyrochat.app/files/ .
```

`tone-1s-24k-mono-32k.m4a` itself is produced by `WaveformTest.exportsFixtureAndBarsForIos`
(`app/src/androidTest/.../AacFixture.kt`) and pulled the same way.
