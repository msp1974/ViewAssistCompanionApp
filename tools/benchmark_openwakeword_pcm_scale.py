#!/usr/bin/env python3
"""Compare PCM-scale and normalised input with upstream OpenWakeWord.

The benchmark uses VACA's bundled TFLite models and upstream OpenWakeWord's
actual streaming buffers/cadence. Both runs use the same deterministic startup
state and WAV. The normalised run changes only the frontend dtype/scale guard
to emulate VACA's current `Short / 32768` float input.

Run from the repository root:

  uv run --python 3.11 \
    --with 'openwakeword @ git+https://github.com/dscripka/openWakeWord@368c03716d1e92591906a84949bc477f3a834455' \
    --with ai-edge-litert==2.1.6 --with numpy==2.3.5 --with requests --with tqdm \
    python tools/benchmark_openwakeword_pcm_scale.py
"""

from __future__ import annotations

import time
import types
import wave
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import numpy as np
from openwakeword.model import Model

ROOT = Path(__file__).resolve().parents[1]
MODEL_DIR = ROOT / "app/src/main/assets/openwakeword"
FIXTURE = ROOT / "tools/fixtures/hey_mycroft_test.wav"
HOP_SAMPLES = 1_280
SAMPLE_RATE = 16_000
RANDOM_SEED = 7


@dataclass(frozen=True)
class Result:
    mode: str
    max_score: float
    scores: tuple[float, ...]
    mean_tick_ms: float
    mel_min: float
    mel_max: float
    hits_at_05: int
    hits_at_09: int
    hits_at_099: int


def read_fixture() -> np.ndarray:
    with wave.open(str(FIXTURE), "rb") as wav:
        if wav.getframerate() != SAMPLE_RATE or wav.getnchannels() != 1 or wav.getsampwidth() != 2:
            raise ValueError("Fixture must be mono 16 kHz PCM16")
        return np.frombuffer(wav.readframes(wav.getnframes()), dtype="<i2")


def create_model() -> Model:
    # Upstream seeds feature history from random PCM. Use the same startup state
    # in both runs so input scale is the only experimental variable.
    np.random.seed(RANDOM_SEED)
    model = Model(
        wakeword_models=[str(MODEL_DIR / "wakeWords/hey_mycroft.tflite")],
        inference_framework="tflite",
        melspec_model_path=str(MODEL_DIR / "melspectrogram.tflite"),
        embedding_model_path=str(MODEL_DIR / "embedding_model.tflite"),
    )

    # Upstream intentionally rejects non-int16 input before casting it to float.
    # VACA calls the TFLite frontend directly with floats, so use the same float
    # entry point for both benchmark modes and vary only sample magnitude.
    def get_melspectrogram(
        preprocessor: Any,
        audio: np.ndarray,
        melspec_transform=lambda values: values / 10.0 + 2.0,
    ) -> np.ndarray:
        values = np.asarray(audio, dtype=np.float32)
        values = values[None, :] if values.ndim < 2 else values
        outputs = preprocessor.melspec_model_predict(values)
        return melspec_transform(np.squeeze(outputs[0]))

    model.preprocessor._get_melspectrogram = types.MethodType(
        get_melspectrogram,
        model.preprocessor,
    )

    return model


def run(mode: str, fixture_pcm: np.ndarray) -> Result:
    normalised = mode == "normalised"
    model = create_model()
    audio = fixture_pcm.astype(np.float32)
    if normalised:
        audio /= 32_768.0
    scores: list[float] = []
    tick_times: list[float] = []
    mel_min = float("inf")
    mel_max = float("-inf")

    for offset in range(0, audio.size - HOP_SAMPLES + 1, HOP_SAMPLES):
        started = time.perf_counter()
        prediction = model.predict(audio[offset : offset + HOP_SAMPLES])
        tick_times.append((time.perf_counter() - started) * 1_000.0)
        scores.append(float(prediction["hey_mycroft"]))
        mel_min = min(mel_min, float(model.preprocessor.melspectrogram_buffer.min()))
        mel_max = max(mel_max, float(model.preprocessor.melspectrogram_buffer.max()))

    return Result(
        mode=mode,
        max_score=max(scores),
        scores=tuple(scores),
        mean_tick_ms=float(np.mean(tick_times)),
        mel_min=mel_min,
        mel_max=mel_max,
        hits_at_05=sum(score >= 0.5 for score in scores),
        hits_at_09=sum(score >= 0.9 for score in scores),
        hits_at_099=sum(score >= 0.99 for score in scores),
    )


def main() -> None:
    fixture_pcm = read_fixture()
    results = [run("pcm-scale", fixture_pcm), run("normalised", fixture_pcm)]

    print("mode        max_score  >=.50  >=.90  >=.99  mel_min  mel_max  mean_tick_ms  score_trace")
    for result in results:
        trace = ",".join(f"{score:.4f}" for score in result.scores)
        print(
            f"{result.mode:11} {result.max_score:9.6f} "
            f"{result.hits_at_05:5d} {result.hits_at_09:5d} {result.hits_at_099:5d} "
            f"{result.mel_min:8.3f} {result.mel_max:8.3f} "
            f"{result.mean_tick_ms:13.3f}  [{trace}]"
        )

    pcm_result, normalised_result = results
    if pcm_result.max_score < 0.9:
        raise SystemExit("FAIL: PCM-scale input did not detect the positive fixture")
    if pcm_result.max_score - normalised_result.max_score < 0.25:
        raise SystemExit("FAIL: input scales did not produce a material score separation")
    if pcm_result.hits_at_09 == 0 or normalised_result.hits_at_09 != 0:
        raise SystemExit("FAIL: expected only PCM-scale input to cross the 0.9 threshold")

    print("PASS: preserving PCM16 magnitude restores the upstream model contract")


if __name__ == "__main__":
    main()
