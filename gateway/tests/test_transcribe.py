import base64
import io
import struct
import wave

import pytest

from notifyd.transcribe import _decode_wav


def _wav(rate=16000, ch=1, samples=(0, 16384, -16384)):
    b = io.BytesIO()
    with wave.open(b, "wb") as w:
        w.setnchannels(ch); w.setsampwidth(2); w.setframerate(rate)
        w.writeframes(struct.pack(f"<{len(samples)}h", *samples))
    return b.getvalue()


def test_decode_scales_to_float():
    pytest.importorskip("numpy")  # numpy ships with the optional `voice` extra
    a = _decode_wav(_wav())
    assert list(a) == [0.0, 0.5, -0.5]


def test_rejects_wrong_format():
    pytest.importorskip("numpy")
    with pytest.raises(ValueError):
        _decode_wav(_wav(rate=44100))


def test_device_selection_and_unload(monkeypatch):
    import sys
    import types
    from notifyd import transcribe as t
    fake = types.SimpleNamespace(get_cuda_device_count=lambda: 1)
    monkeypatch.setitem(sys.modules, "ctranslate2", fake)
    assert t.pick_device() == ("cuda", "float32")
    fake.get_cuda_device_count = lambda: 0
    assert t.pick_device() == ("cpu", "int8")
    t._model = object()
    t.unload()
    assert t._model is None and t.IDLE_UNLOAD_S == 300
