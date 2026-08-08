# OpenWakeWord PCM-scale input correction

## Objective

Restore the input contract expected by OpenWakeWord's upstream mel-spectrogram model while leaving VACA's other audio consumers unchanged.

OpenWakeWord's TFLite mel model accepts a float tensor, but the values retain signed PCM16 magnitude (approximately `-32768..32767`). VACA currently calls `MicrophoneInput.readFloat()`, which divides enhanced PCM16 samples by `32768` before inference. The model file bundled by VACA is byte-identical to upstream OpenWakeWord v0.5.1, whose preprocessing code explicitly requires PCM16 and casts it to float without normalising its magnitude.

This branch:

1. Reads enhanced PCM16 once in `OpenWakeWordEngine`.
2. Converts it to PCM-scale float values for OpenWakeWord.
3. Continues using normalised `-1..1` values for the diagnostic audio-level calculation.
4. Serialises the original PCM16 values directly for recording/streaming.
5. Does not change thresholds, smoothing, model files, buffering cadence, audio source, or other wake-word engines.

## Implementation

`AudioDSP.openWakeWordInput(ShortArray)` performs the model-specific conversion:

```text
Short.MIN_VALUE → -32768.0f
-1              → -1.0f
0               → 0.0f
1               → 1.0f
Short.MAX_VALUE → 32767.0f
```

The method name and KDoc intentionally distinguish this model input from `normaliseAudioBuffer()`, which remains valid for level meters and APIs that explicitly require `-1..1` audio.

## Unit tests

Run:

```bash
./gradlew :app:testDebugUnitTest --tests 'com.msp1974.vacompanion.audio.AudioDSPTest'
```

The tests verify both contracts:

- OpenWakeWord conversion preserves exact PCM16 magnitude.
- Existing normalised conversion remains unchanged.

## Reproducible model benchmark

The benchmark uses:

- VACA's committed `melspectrogram.tflite`, `embedding_model.tflite`, and `hey_mycroft.tflite`;
- upstream OpenWakeWord streaming code pinned at commit `368c03716d1e92591906a84949bc477f3a834455`;
- a deterministic random seed so both runs have identical upstream startup feature state;
- upstream's `hey_mycroft_test.wav` fixture;
- identical 1,280-sample/80 ms chunks;
- one changed variable: PCM-scale floats versus `PCM / 32768` floats.

Run:

```bash
uv run --python 3.11 \
  --with 'openwakeword @ git+https://github.com/dscripka/openWakeWord@368c03716d1e92591906a84949bc477f3a834455' \
  --with ai-edge-litert==2.1.6 --with numpy==2.3.5 --with requests --with tqdm \
  python tools/benchmark_openwakeword_pcm_scale.py
```

Measured on the development host:

| Input | Peak classifier score | Frames ≥ 0.50 | Frames ≥ 0.90 | Frames ≥ 0.99 | Mel range |
|---|---:|---:|---:|---:|---:|
| PCM-scale float | **1.000000** | 3 | 3 | 2 | `1.000..12.423` |
| Normalised float | **0.657429** | 1 | 0 | 0 | `-3.814..3.392` |

Score traces:

```text
PCM-scale: [0, 0, 0, 0, 0, 0, 0, 0, 0.9408, 1.0000, 1.0000]
Normalised: [0, 0, 0, 0, 0, 0, 0, 0, 0.0001, 0.0018, 0.6574]
```

### Interpretation

Normalisation did not make the positive fixture mathematically impossible to detect at every threshold: its final score crossed `0.5`. It did, however, materially change the mel distribution and reduce the peak score by about one third. In this deterministic replay it produced no frames above `0.9`, while PCM-scale input produced three.

This is especially relevant to VACA configurations using high thresholds, but the reason for the change is the upstream tensor contract—not tuning to one threshold or fixture. Thresholds should be recalibrated separately after restoring correct input scale.

This single positive fixture is a contract regression benchmark, not a complete accuracy study. A full evaluation should include multiple speakers, distances, background conditions, and hours of hard-negative audio.

## Benchmark fixture attribution

`tools/fixtures/hey_mycroft_test.wav` is copied from `dscripka/openWakeWord` at commit `368c03716d1e92591906a84949bc477f3a834455`, which is licensed under Apache-2.0. Its SHA-256 is:

```text
8d728ce1dbd54a97c6cddf0c80acec3113285407a967368f4abe476119c1a8c6
```

## Out of scope

The broader pipeline audit identified additional opportunities—partial-read accumulation, startup suppression, per-model trigger state, score precision, and interpreter allocation. They are intentionally excluded here so this branch demonstrates only the PCM scaling correction and can be reviewed independently.
