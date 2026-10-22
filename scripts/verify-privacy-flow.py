"""One synthetic socket/HTTP/filesystem integration check. No phone, AI, or key access."""
import io
import json
import socket
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "dashboard"))
import server

# Replace only external ADB and credential access. Product HTTP, bridge parsing,
# run handling, persistence, and exports execute unchanged over real sockets.
server.credential_store.AVAILABLE = False
(ROOT / "tmp").mkdir(exist_ok=True)
folder = Path(tempfile.mkdtemp(prefix="privacy-flow-", dir=ROOT / "tmp")).resolve()
assert folder.is_relative_to((ROOT / "tmp").resolve())
legacy = folder / "legacy"
legacy.mkdir()
(legacy / "run.json").write_text("LEGACY_PRIVATE_CANARY_NOT_JSON", encoding="utf-8")
phone = socket.socket()
phone.bind(("127.0.0.1", 0))
phone.listen()
run_id = None
connection_ready = threading.Event()
send_lock = threading.Lock()
state = {"consentGranted": True, "monitoringEnabled": True, "serviceConnected": True,
         "approvedAppCount": 1, "jevReady": True, "voiceReady": True, "runMode": "dashboard",
         "currentApp": "PRIVATE_APP_CANARY", "currentPackage": "example.synthetic",
         "counters": {"requests": 1, "screenshots": 1, "ocrCompleted": 1}}
peer = None


def send(kind, data=None, identity=None):
    connection_ready.wait(5)
    event = {"protocol": 1, "runId": identity if identity is not None else run_id,
             "type": kind, "timestampMs": 1792638000000, "data": data or {}, "state": dict(state)}
    with send_lock:
        peer.sendall(server.encode(event) + b"\n")


def phone_loop():
    global peer, run_id
    peer, _ = phone.accept()
    connection_ready.set()
    with peer.makefile("rb") as stream:
        for raw in stream:
            command = json.loads(raw)
            if command["command"] == "snapshot":
                send("snapshot")
            elif command["command"] == "start":
                run_id = command["runId"]
                state["runId"] = run_id
                send("run_started", {"voiceScript": {"id": "synthetic", "instructions": "PRIVATE_SCRIPT_CANARY"}})
                send("ocr_completed", {"captureId": 1, "text": "PRIVATE_OCR_CANARY", "windowId": 99,
                                       "packageName": "example.synthetic", "appLabel": "PRIVATE_APP_CANARY"})
                send("jev_completed", {"captureId": 1, "status": "completed", "flagged": True,
                                       "threshold": 0.7, "answers": {"crypto": {"type": "noul", "noul": 0.9}}})
            elif command["command"] == "stop":
                state["runId"] = None
                send("run_stopped", {"reason": "user_stopped"})


class SyntheticTransportConsole(server.Console):
    def adb_call(self, *arguments, **kwargs):
        if arguments == ("devices",):
            return "List of devices attached\nprivacy-synthetic\tdevice"
        if arguments[:2] == ("shell", "pidof"):
            return "1"
        if arguments[:2] == ("forward", "tcp:0"):
            return str(phone.getsockname()[1])
        if arguments[:2] == ("forward", "--remove"):
            return ""
        raise RuntimeError("Unexpected synthetic transport command.")


console = SyntheticTransportConsole(folder)
httpd = server.ConsoleServer(("127.0.0.1", 0), server.Handler)
httpd.daemon_threads = True
httpd.console = console
base = f"http://127.0.0.1:{httpd.server_port}"
threading.Thread(target=phone_loop, daemon=True).start()
threading.Thread(target=httpd.serve_forever, daemon=True).start()


def request(path, body=None):
    headers = {"Origin": base, "X-Antiscam-Token": console.token}
    data = None
    if body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(base + path, data=data, headers=headers)
    try:
        response = urllib.request.urlopen(req, timeout=5)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        assert response.headers.get("Cache-Control") == "no-store"
        return response.status, response.read()


def snapshot():
    status, body = request("/api/session")
    assert status == 200
    return json.loads(body)


def until(check):
    deadline = time.monotonic() + 5
    while time.monotonic() < deadline:
        if check():
            return
        time.sleep(0.02)
    raise AssertionError("Integration condition did not complete.")


try:
    assert snapshot()["run"] is None
    assert request("/api/export")[0] == 409
    console.refresh_devices(auto_connect=True)
    until(lambda: snapshot()["connection"] == "connected")
    assert request("/api/command", {"command": "start"})[0] == 200
    until(lambda: len(snapshot()["ocr"]) == 1 and snapshot()["ocr"][0].get("jev", {}).get("status") == "completed")
    assert set(folder.iterdir()) == {legacy}
    assert b"PRIVATE_OCR_CANARY" not in request("/api/session")[1]

    rejected = [
        {"scamType": "synthetic", "deviceSerial": "PRIVATE_DEVICE_CANARY"},
        {"involvedAccount": {"platform": "synthetic", "id": "person", "name": "PRIVATE_NAME_CANARY"}},
        {"scamCount": True, "involvedAccount": {"platform": "synthetic", "id": "person"}},
        {"scamCount": 3}, {"scamMessage": "x" * 4097}, {"occurredAt": "2026-99-22T12:00:00Z"},
    ]
    for record in rejected:
        send("scam_incident", record)
    until(lambda: snapshot()["incidentError"] is not None)
    assert not (folder / "incidents-v1.json").exists()

    approved = {"involvedAccount": {"platform": "synthetic", "id": "person-1"},
                "flaggedAccount": {"platform": "synthetic", "id": "sender-1"},
                "occurredAt": "2026-10-22T12:00:00Z", "scamMessage": "Synthetic message only.",
                "scamType": "synthetic-example", "scamCount": 2}
    send("scam_incident", approved)
    until(lambda: snapshot()["incidentCount"] == 1 and snapshot()["incidentError"] is None)
    path = folder / "incidents-v1.json"
    assert json.loads(path.read_text(encoding="utf-8")) == [approved]
    assert set(folder.iterdir()) == {legacy, path}
    original = path.read_bytes()
    status, payload = request("/api/export")
    assert status == 200
    with zipfile.ZipFile(io.BytesIO(payload)) as archive:
        assert archive.namelist() == ["incidents.json"]
        assert json.loads(archive.read("incidents.json")) == [approved]
        assert archive.getinfo("incidents.json").date_time == (1980, 1, 1, 0, 0, 0)

    path.write_text(json.dumps([{**approved, "diagnostics": "PRIVATE_EXPORT_CANARY"}]), encoding="utf-8")
    status, payload = request("/api/export")
    assert status == 409 and b"PRIVATE_EXPORT_CANARY" not in payload
    path.write_bytes(original)
    assert request("/api/command", {"command": "stop"})[0] == 200
    until(lambda: snapshot()["run"]["status"] == "stopped")
    send("scam_incident", approved)
    send("ocr_completed", {"captureId": 2, "text": "PRIVATE_LATE_CANARY"})
    until(lambda: len(snapshot()["ocr"]) == 2)
    assert path.read_bytes() == original
    assert (legacy / "run.json").read_text(encoding="utf-8") == "LEGACY_PRIVATE_CANARY_NOT_JSON"
    restored = SyntheticTransportConsole(folder).snapshot()
    assert restored["run"] is None and restored["activity"] == [] and restored["ocr"] == []
    assert restored["incidentCount"] == 1
    print("PASS: live diagnostics and late events create no diagnostic files; legacy files remain untouched.")
    print("PASS: approved incident fields persist and export exactly; extra/nested/type/date/size violations reject.")
    print("PASS: export rejects a modified file; no rejected content appears in its response; restart restores no diagnostics.")
finally:
    console.close()
    httpd.shutdown()
    httpd.server_close()
    if peer:
        peer.close()
    phone.close()
