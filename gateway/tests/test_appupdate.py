import gzip
import hashlib
import io
import json

from notifyd import appupdate


def _build(tmp_path, data=b"PK\x03\x04 fake apk " * 1000, code=412345, name="2.0-20261006-0900"):
    out = tmp_path / "build"
    out.mkdir()
    (out / "app-debug.apk").write_bytes(data)
    (out / "output-metadata.json").write_text(json.dumps({"elements": [
        {"type": "SINGLE", "versionCode": code, "versionName": name, "outputFile": "app-debug.apk"}]}))
    return out / "app-debug.apk", data


def test_publish_info_and_stream(tmp_path, monkeypatch):
    monkeypatch.setattr(appupdate, "STATE_DIR", tmp_path / "state")
    assert appupdate.info() is None
    assert appupdate.stream(io.BytesIO()) == 1  # nothing published
    apk, data = _build(tmp_path)
    i = appupdate.publish(apk)
    assert i["version_code"] == 412345 and i["size"] == len(data) and i["sha256"] == hashlib.sha256(data).hexdigest()
    assert i["gz_size"] < i["size"] and appupdate.info()["version_name"] == "2.0-20261006-0900"
    buf = io.BytesIO()
    assert appupdate.stream(buf) == 0
    assert gzip.decompress(buf.getvalue()) == data


def test_ssh_entry_allows_apk(tmp_path, monkeypatch):
    from notifyd import ssh_entry
    monkeypatch.setattr(appupdate, "STATE_DIR", tmp_path / "state")
    monkeypatch.setenv("SSH_ORIGINAL_COMMAND", "apk")
    assert ssh_entry.main() == 1  # allowed verb, but nothing published yet
    monkeypatch.setenv("SSH_ORIGINAL_COMMAND", "apk extra")
    assert ssh_entry.main() == 1
