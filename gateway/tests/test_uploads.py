import base64

import pytest

from notifyd import uploads


def test_upload_in_chunks_lands_outside_repos(tmp_path, monkeypatch):
    monkeypatch.setattr(uploads, "ROOT", tmp_path / "up")
    data = b"\x89PNG" + bytes(range(256)) * 10
    uid = uploads.begin("../../etc/Screenshot 2026.png", len(data))["upload_id"]
    for i, start in enumerate(range(0, len(data), 1000)):
        uploads.chunk(uid, i, base64.b64encode(data[start:start + 1000]).decode())
    r = uploads.end(uid)
    assert r["size"] == len(data) and r["path"].endswith("Screenshot 2026.png") and str(tmp_path / "up") in r["path"]
    assert open(r["path"], "rb").read() == data
    uid2 = uploads.begin("Screenshot 2026.png")["upload_id"]  # same name again: a new file, not an overwrite
    uploads.chunk(uid2, 0, base64.b64encode(b"x").decode())
    assert uploads.end(uid2)["path"].endswith("Screenshot 2026-2.png")


def test_upload_rejects_out_of_order_too_large_and_incomplete(tmp_path, monkeypatch):
    monkeypatch.setattr(uploads, "ROOT", tmp_path / "up")
    with pytest.raises(ValueError):
        uploads.begin("big.bin", uploads.MAX_BYTES + 1)
    uid = uploads.begin("a.txt", 10)["upload_id"]
    with pytest.raises(ValueError):
        uploads.chunk(uid, 1, "eA==")
    uploads.chunk(uid, 0, base64.b64encode(b"short").decode())
    with pytest.raises(ValueError):
        uploads.end(uid)  # 5 of 10 bytes
    assert not list((tmp_path / "up").rglob("*.part"))
