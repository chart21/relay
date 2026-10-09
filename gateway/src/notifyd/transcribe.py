"""Speech-to-text on the PC for audio sent by the phone (faster-whisper, loaded lazily, unloaded when idle).

The phone always sends 16 kHz mono PCM16 WAV, so it is decoded directly (no PyAV/ffmpeg dependency).
CUDA is used when available (float32: the GTX 970 is Maxwell, no fast int8/fp16), else CPU int8. The model is
dropped after IDLE_UNLOAD_S so the GPU can fall back to its idle power state.
"""
from __future__ import annotations

import base64
import gc
import io
import threading
import wave

IDLE_UNLOAD_S = 300

_model = None
_lock = threading.RLock()
_timer: threading.Timer | None = None
_force_cpu = False


def _decode_wav(data: bytes):
    import numpy as np
    with wave.open(io.BytesIO(data)) as w:
        if w.getsampwidth() != 2 or w.getframerate() != 16000 or w.getnchannels() != 1:
            raise ValueError("expected 16 kHz mono 16-bit WAV")
        pcm = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16)
    return pcm.astype(np.float32) / 32768.0


def pick_device() -> tuple[str, str]:
    """-> (device, compute_type)"""
    try:
        import ctranslate2
        if ctranslate2.get_cuda_device_count() > 0:
            return "cuda", "float32"
    except Exception:
        pass
    return "cpu", "int8"


def unload() -> None:
    global _model, _timer
    with _lock:
        _model = None
        if _timer:
            _timer.cancel()
            _timer = None
    gc.collect()


def _arm() -> None:
    global _timer
    if _timer:
        _timer.cancel()
    _timer = threading.Timer(IDLE_UNLOAD_S, unload)
    _timer.daemon = True
    _timer.start()


def loaded() -> bool:
    return _model is not None


def warm(model_size: str = "small") -> bool:
    """Load the model ahead of the first transcription (the phone asks while it records). False: not available."""
    try:
        with _lock:
            _run(None, model_size)
        return True
    except Exception:
        return False


def transcribe(audio_b64: str, model_size: str = "small") -> str:
    audio = _decode_wav(base64.b64decode(audio_b64))
    with _lock:
        return _run(audio, model_size)


def _run(audio, model_size: str) -> str:
    """Loads the model if needed (falls back to the CPU once), transcribes audio unless it is None."""
    global _model, _force_cpu
    try:
        from faster_whisper import WhisperModel
    except ImportError as e:
        raise RuntimeError("faster-whisper not installed (uv sync --extra voice)") from e
    for attempt in (1, 2):
        device, compute = ("cpu", "int8") if _force_cpu else pick_device()
        try:
            if _model is None:
                _model = WhisperModel(model_size, device=device, compute_type=compute)
            if audio is None:
                return ""
            segments, _ = _model.transcribe(audio, vad_filter=True)
            return " ".join(s.text.strip() for s in segments).strip()
        except Exception:
            _model = None
            if device == "cpu" or attempt == 2:
                raise
            _force_cpu = True  # CUDA libraries missing/broken: stay on the CPU from now on
        finally:
            if _model is not None:
                _arm()
    return ""
