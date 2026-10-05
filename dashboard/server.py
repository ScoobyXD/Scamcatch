"""Local Antiscam run recorder. Python 3.11+, standard library only."""
from __future__ import annotations

import argparse
import io
import json
import os
from pathlib import Path
import queue
import re
import secrets
import shutil
import socket
import subprocess
import threading
import time
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit
import webbrowser
import zipfile

import credential_store


HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
ACTIVE = {"starting", "active", "stopping"}
SAFE_ID = re.compile(r"^[A-Za-z0-9_-]{1,100}$")
JEV_EVENTS = {"jev_started", "jev_completed", "jev_failed", "jev_skipped", "jev_canceled"}
LATE_RESULTS = {"screenshot_received", "capture_failed", "capture_discarded", "ocr_started", "ocr_completed", "ocr_failed", "voice_audio_summary"} | JEV_EVENTS
KEY_COMMANDS = {"/api/jev-key": "jev_key", "/api/openai-key": "openai_key"}


def utc_now():
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds")


def encode(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


def write_json(path, value):
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_bytes(json.dumps(value, ensure_ascii=False, indent=2).encode("utf-8"))
    temporary.replace(path)


def find_adb():
    candidates = []
    for variable in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        if os.environ.get(variable):
            candidates.append(Path(os.environ[variable]) / "platform-tools" / "adb.exe")
    candidates.extend([
        Path.home() / "Android" / "Sdk" / "platform-tools" / "adb.exe",
        Path(os.environ.get("LOCALAPPDATA", str(Path.home()))) / "Android" / "Sdk" / "platform-tools" / "adb.exe",
    ])
    for candidate in candidates:
        if candidate.is_file():
            return str(candidate)
    return shutil.which("adb")


class Console:
    def __init__(self, runs):
        self.runs = runs.resolve()
        self.runs.mkdir(parents=True, exist_ok=True)
        self.lock = threading.RLock()
        self.send_lock = threading.Lock()
        self.token = secrets.token_urlsafe(32)
        self.adb = find_adb()
        self.devices = []
        self.selected_device = None
        self.connection = "disconnected"
        self.message = "Connect a phone or start an emulator, then select Refresh devices."
        self.state = {}
        self.run = None
        self.ocr = []
        self.activity = []
        self.event_file = None
        self.recording_run = False
        self.subscribers = set()
        self.bridge = None
        self.forward_port = None
        self.connector = None
        self.connector_stop = threading.Event()
        self.closed = threading.Event()
        self.recording_error = None
        self.key_storage = {"available": credential_store.AVAILABLE, "saved": {}, "error": None}
        self.load_latest()

    def load_latest(self):
        # Only generated run folders may be read. Never follow a user-supplied path.
        candidates = sorted((p for p in self.runs.iterdir() if p.is_dir() and SAFE_ID.fullmatch(p.name)), reverse=True)
        for folder in candidates:
            if folder.is_symlink() or folder.resolve().parent != self.runs:
                continue
            try:
                run = json.loads((folder / "run.json").read_text(encoding="utf-8"))
                if run.get("id") != folder.name:
                    continue
                self.run = run
                self.ocr = []
                for item in run.get("ocr", []):
                    relative = Path(item["file"])
                    path = folder / relative
                    if relative.parent != Path("ocr") or path.is_symlink() or path.resolve().parent != (folder / "ocr").resolve():
                        continue
                    self.ocr.append({**item, "text": path.read_text(encoding="utf-8")})
                events = folder / "events.jsonl"
                self.activity = []
                if events.is_file() and not events.is_symlink():
                    with events.open(encoding="utf-8") as source:
                        for line in source:
                            try:
                                event = json.loads(line)
                                if event.get("type") not in {"snapshot", "state_changed"}:
                                    self.activity.append(self.activity_entry(event))
                                if event.get("type") in JEV_EVENTS and isinstance(event.get("data"), dict):
                                    # An event can reach disk before its joined run metadata.
                                    self.save_jev(event, persist=False)
                            except (ValueError, TypeError, AttributeError):
                                # A partial final line does not hide the rest of a saved run.
                                continue
                if run.get("status") in ACTIVE:
                    self.recording_run = True
                    try:
                        for record in self.ocr:
                            if record.get("jev"):
                                self.write_ocr_record(record)
                        if not events.is_symlink():
                            self.event_file = events.open("a", encoding="utf-8", newline="\n")
                        self.finish("interrupted", "The recorder closed before Stop was confirmed.")
                    except OSError:
                        self.recording_failed()
                return
            except (OSError, ValueError, KeyError, TypeError):
                self.run = None
                self.ocr = []
                self.activity = []

    def snapshot(self):
        with self.lock:
            return {
                "token": self.token,
                "connection": self.connection, "message": self.message,
                "devices": self.devices, "selectedDevice": self.selected_device,
                "state": self.state, "run": self.run, "ocr": self.ocr,
                "activity": self.activity,
                "keyStorage": self.key_storage,
            }

    def publish(self, kind="status", data=None):
        with self.lock:
            if data is None:
                data = {"connection": self.connection, "message": self.message,
                        "devices": self.devices, "selectedDevice": self.selected_device,
                        "state": self.state, "run": self.run, "keyStorage": self.key_storage}
            # Freeze each update before the mutable run receives another event.
            message = encode({"kind": kind, "data": data})
            for subscriber in tuple(self.subscribers):
                try:
                    subscriber.put_nowait(message)
                except queue.Full:
                    # A slow browser reconnects and receives the complete latest snapshot.
                    while not subscriber.empty():
                        try:
                            subscriber.get_nowait()
                        except queue.Empty:
                            break
                    subscriber.put_nowait(None)

    def adb_call(self, *arguments, serial=None, allow_nonzero=False):
        if not self.adb:
            raise RuntimeError("ADB is unavailable. Install Android Platform Tools or set ANDROID_HOME, then restart the dashboard.")
        command = [self.adb]
        if serial:
            command += ["-s", serial]
        command += list(arguments)
        try:
            result = subprocess.run(command, capture_output=True, text=True, timeout=15,
                                    creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        except (OSError, subprocess.TimeoutExpired) as error:
            raise RuntimeError("ADB did not respond. Check the device connection.") from error
        if result.returncode and not allow_nonzero:
            raise RuntimeError("ADB could not complete the request. Check the device and its debugging permission.")
        return result.stdout.strip()

    def refresh_devices(self, auto_connect=False):
        try:
            output = self.adb_call("devices")
            devices = []
            for line in output.splitlines()[1:]:
                fields = line.split()
                if len(fields) >= 2:
                    devices.append({"serial": fields[0], "status": fields[1]})
            with self.lock:
                self.devices = devices
                if self.connection != "connected":
                    self.message = "Select a ready device and choose Connect." if devices else "No device found. Connect a phone or start an emulator, then select Refresh devices."
                self.publish()
            ready = [device for device in devices if device["status"] == "device"]
            if auto_connect and len(devices) == 1 and len(ready) == 1:
                self.connect_device(ready[0]["serial"])
        except RuntimeError as error:
            with self.lock:
                self.message = str(error)
                self.publish()

    def connect_device(self, serial):
        with self.lock:
            if self.run and self.run["status"] in ACTIVE:
                raise ValueError("Stop the current run before changing devices.")
            if self.closed.is_set():
                raise ValueError("The dashboard is closing.")
            if self.connection in {"connecting", "switching"}:
                raise ValueError("A connection attempt is already in progress.")
            if not any(device["serial"] == serial and device["status"] == "device" for device in self.devices):
                raise ValueError("Choose an available device. Refresh the device list if needed.")
            if self.connection == "connected" and self.selected_device == serial:
                return
            if self.connection == "connected" and self.state.get("runId"):
                raise ValueError("Wait for the current device to confirm Stop before changing devices.")
            previous_connector = self.connector
            self.connector_stop.set()
            self.connection = "switching"
            self.message = "Closing the previous device connection…"
            if self.bridge:
                try:
                    self.bridge.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass
                try:
                    self.bridge.close()
                except OSError:
                    pass
                self.bridge = None
            self.state = {}
            self.publish()
        # Let the old worker remove its forward before another worker can start.
        if previous_connector:
            previous_connector.join(timeout=35)
        with self.lock:
            if previous_connector and previous_connector.is_alive():
                self.connection = "disconnected"
                self.message = "The previous connection is still closing. Choose Connect again shortly."
                self.publish()
                raise ValueError(self.message)
            if self.closed.is_set():
                return
            self.selected_device = serial
            self.refresh_saved_keys()
            self.recording_error = None
            self.connection = "connecting"
            self.message = "Connecting to Antiscam Dev…"
            self.publish()
            self.connector_stop = threading.Event()
            self.connector = threading.Thread(target=self.bridge_loop, args=(serial, self.connector_stop), daemon=True)
            self.connector.start()

    def bridge_loop(self, serial, stopped):
        # Retry only a failed connection. No device or application state polling.
        started_app = False
        failures = 0
        while not self.closed.is_set() and not stopped.is_set() and failures < 8:
            sock = None
            port = None
            try:
                if not started_app:
                    # Avoid moving the app to the front when its process is already alive.
                    process = self.adb_call("shell", "pidof", "com.antiscam.app", serial=serial, allow_nonzero=True)
                    if stopped.is_set() or self.closed.is_set():
                        return
                    if not process:
                        self.adb_call("shell", "am", "start", "-n", "com.antiscam.app/.MainActivity", serial=serial)
                    started_app = True
                if stopped.is_set() or self.closed.is_set():
                    return
                port = int(self.adb_call("forward", "tcp:0", "localabstract:antiscam_run", serial=serial))
                if stopped.is_set() or self.closed.is_set():
                    return
                sock = socket.create_connection(("127.0.0.1", port), timeout=5)
                sock.settimeout(None)
                connected_at = time.monotonic()
                with self.lock:
                    if stopped.is_set() or self.closed.is_set():
                        return
                    self.bridge = sock
                    self.forward_port = port
                    self.connection = "connecting"
                    self.message = "Waiting for the Antiscam state…"
                    self.publish()
                    self.send_command({"command": "snapshot"})
                with sock.makefile("rb") as stream:
                    while not self.closed.is_set() and not stopped.is_set():
                        line = stream.readline(16 * 1024 * 1024 + 1)
                        if not line:
                            raise ConnectionError("The device stream closed.")
                        if len(line) > 16 * 1024 * 1024 or not line.endswith(b"\n"):
                            raise ValueError("The device sent an invalid event.")
                        event = json.loads(line)
                        if not isinstance(event, dict) or event.get("protocol") != 1:
                            raise ValueError("The installed app uses an unsupported run protocol.")
                        if time.monotonic() - connected_at >= 15:
                            failures = 0
                        with self.lock:
                            if stopped.is_set() or self.closed.is_set():
                                return
                            try:
                                self.receive(event)
                            except OSError:
                                self.recording_failed()
                                return
            except (OSError, RuntimeError, ValueError) as error:
                with self.lock:
                    if stopped.is_set() or self.closed.is_set() or self.recording_error:
                        return
                    self.bridge = None
                    self.connection = "disconnected"
                    if self.run and self.run["status"] in ACTIVE:
                        try:
                            self.finish("interrupted", "The device connection closed before Stop was confirmed.")
                        except OSError:
                            self.recording_failed()
                            return
                    self.message = "Device connection lost. Retrying…" if started_app else str(error)
                    self.publish()
            finally:
                if sock:
                    try:
                        sock.close()
                    except OSError:
                        pass
                if port:
                    try:
                        self.adb_call("forward", "--remove", f"tcp:{port}", serial=serial)
                    except RuntimeError:
                        pass
                with self.lock:
                    self.forward_port = None
            failures += 1
            if stopped.wait(min(0.5 * (2 ** (failures - 1)), 5)):
                return
        with self.lock:
            if stopped.is_set() or self.closed.is_set():
                return
            self.connection = "disconnected"
            self.message = "Connection stopped after repeated failures. Check Antiscam Dev, then choose Connect."
            self.publish()

    def send_command(self, value):
        with self.lock, self.send_lock:
            if not self.bridge:
                raise ValueError("The device is not connected.")
            try:
                self.bridge.sendall(encode(value) + b"\n")
            except OSError as error:
                raise ValueError("The device connection closed. The run remains incomplete until its status is confirmed.") from error

    def refresh_saved_keys(self):
        self.key_storage["saved"] = {}
        self.key_storage["error"] = None
        if not credential_store.AVAILABLE or not self.selected_device:
            return
        try:
            for command in KEY_COMMANDS.values():
                provider = command.removesuffix("_key")
                self.key_storage["saved"][provider] = credential_store.saved(self.selected_device, provider)
        except credential_store.CredentialStoreError:
            self.key_storage["error"] = "Windows could not check saved keys."

    def restore_saved_keys(self):
        # Called once per bridge connection. Never repeatedly retry a key rejected by the phone.
        self.refresh_saved_keys()
        for command in KEY_COMMANDS.values():
            provider = command.removesuffix("_key")
            ready = "jevReady" if provider == "jev" else "voiceReady"
            if self.state.get(ready) or not self.key_storage["saved"].get(provider):
                continue
            api_key = None
            try:
                api_key = credential_store.read(self.selected_device, provider)
                if api_key is not None:
                    self.send_command({"command": command, "apiKey": api_key})
            except (credential_store.CredentialStoreError, ValueError):
                self.key_storage["error"] = "A saved key could not be restored. Open its key dialog to try again."
            finally:
                del api_key

    def set_key(self, command, body):
        # Hold the device selection stable through storage and delivery.
        with self.lock:
            if self.connection != "connected" or not self.bridge:
                raise ValueError("Connect the selected phone before changing a key.")
            if body.get("serial") != self.selected_device:
                raise ValueError("The selected phone changed. Reopen the key dialog and try again.")
            provider = command.removesuffix("_key")
            use_saved = body.get("useSaved", False)
            remember = body.get("remember", False)
            if not isinstance(use_saved, bool) or not isinstance(remember, bool):
                raise ValueError("Choose whether to remember this key on this PC.")
            api_key = body.pop("apiKey", None)
            try:
                if use_saved:
                    if api_key is not None:
                        raise ValueError("Send a new key or use the saved key, not both.")
                    api_key = credential_store.read(self.selected_device, provider)
                    if api_key is None:
                        raise ValueError("No key is saved for this phone. Enter a key first.")
                else:
                    if not isinstance(api_key, str) or (api_key and not re.fullmatch(r"[\x21-\x7e]{1,2048}", api_key)):
                        raise ValueError("Use an API key of at most 2048 ASCII characters without spaces, or leave it empty to clear.")
                    if api_key and remember:
                        credential_store.save(self.selected_device, provider, api_key)
                    elif credential_store.AVAILABLE:
                        # A memory-only replacement must not restore an older saved key later.
                        credential_store.delete(self.selected_device, provider)
                self.send_command({"command": command, "apiKey": api_key})
            except credential_store.CredentialStoreError as error:
                raise ValueError(str(error)) from None
            finally:
                del api_key
                self.refresh_saved_keys()
                self.publish()

    def receive(self, event):
        with self.lock:
            if self.recording_error:
                return
            state = event.get("state")
            if isinstance(state, dict):
                reconnected = self.connection != "connected"
                self.state = state
                self.connection = "connected"
                self.message = "Device connected. Start a run when the setup checks are complete."
                if reconnected:
                    self.restore_saved_keys()
            kind = event.get("type", "event")
            data = event.get("data") or {}
            if not isinstance(data, dict):
                raise ValueError("The device sent invalid event data.")
            # Captured work and voice cleanup can finish after Stop or another run starts.
            matching = self.run and event.get("runId") == self.run["id"] and (
                self.run["status"] in ACTIVE or kind in LATE_RESULTS)
            run = self.run if matching else None
            if run is None and kind in LATE_RESULTS:
                run = self.saved_run(event.get("runId"))
            if run:
                active = run is self.run and run["status"] in ACTIVE
                self.append_event(event, run)
                if kind == "run_started":
                    run["status"] = "active"
                if kind == "ocr_completed":
                    self.save_ocr(event, run)
                if kind in JEV_EVENTS:
                    self.save_jev(event, run=run)
                if kind == "run_stopped":
                    self.finish("stopped", data.get("reason") or "Stop confirmed by the device.")
                else:
                    if active:
                        run["counters"] = self.state.get("counters", {})
                    else:
                        counter = {"screenshot_received": "screenshots", "capture_failed": "failed",
                                   "ocr_completed": "ocrCompleted", "ocr_failed": "ocrFailed"}.get(kind)
                        if counter:
                            counters = run.setdefault("counters", {})
                            counters[counter] = counters.get(counter, 0) + 1
                    self.persist_run(run)
                    if kind in JEV_EVENTS or not active:
                        self.write_summary(run)
            if kind == "command_error":
                self.message = str(data.get("reason") or "The device rejected the command.")
                if self.run and self.run["status"] == "starting":
                    self.finish("failed", self.message)
                elif self.run and self.run["status"] == "stopping":
                    self.run["status"] = "active"
                    self.persist_run()
            # A reconnect can reveal an orphaned run. Stop it without resuming its recording.
            if kind == "snapshot" and self.state.get("runId") and not (self.run and self.run["status"] in ACTIVE):
                self.send_command({"command": "stop"})
            if kind not in {"snapshot", "state_changed"} and (matching or kind == "command_error"):
                entry = self.activity_entry(event)
                self.activity.append(entry)
                self.publish("activity", entry)
            self.publish()

    @staticmethod
    def activity_entry(event):
        data = event.get("data") or {}
        state = event.get("state") or {}
        # The triggering app is context, not ownership of every display pixel.
        return {"type": event.get("type", "event"), "timestampMs": event.get("timestampMs"),
                "elapsedMs": event.get("elapsedMs"), "reason": data.get("reason"),
                "captureId": data.get("captureId"), "interaction": data.get("kind"),
                "value": data.get("value"), "trigger": data.get("trigger"),
                "durationMs": data.get("durationMs"), "phase": data.get("phase"),
                "captureScope": data.get("captureScope"), "sourceLabel": data.get("sourceLabel"),
                "sourceAttribution": data.get("sourceAttribution"),
                "appLabel": data.get("appLabel") or state.get("currentApp"),
                "packageName": data.get("packageName") or state.get("currentPackage")}

    def start_run(self):
        with self.lock:
            if self.connection != "connected":
                raise ValueError("Connect the emulator first.")
            if self.run and self.run["status"] in ACTIVE:
                raise ValueError("A run is already active.")
            if self.state.get("runId"):
                raise ValueError("The device still has an active run. Wait for Stop to finish.")
            if not all(self.state.get(key) for key in ("consentGranted", "monitoringEnabled", "serviceConnected")):
                raise ValueError("Allow capture and cloud text analysis, resume capture, and enable the accessibility service in Antiscam Dev.")
            run_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "_" + secrets.token_hex(4)
            folder = self.runs / run_id
            (folder / "ocr").mkdir(parents=True)
            self.run = {"id": run_id, "status": "starting", "startedAt": utc_now(), "endedAt": None,
                        "device": self.selected_device, "captureScope": self.state.get("captureScope", "foreground"),
                        "appVersion": self.state.get("appVersion"), "androidApi": self.state.get("androidApi"),
                        "counters": {}, "eventCount": 0, "ocr": [], "reason": None, "jevExpected": True,
                        "folder": str(folder)}
            self.ocr = []
            self.activity = []
            self.recording_run = True
            self.event_file = (folder / "events.jsonl").open("a", encoding="utf-8", newline="\n")
            self.persist_run()
            self.write_summary()
            self.publish("snapshot", self.snapshot())
            try:
                self.send_command({"command": "start", "runId": run_id, "recordOcr": True})
            except ValueError:
                self.finish("failed", "The Start command did not reach the device.")
                self.publish()
                raise

    def stop_run(self):
        with self.lock:
            if not self.run or self.run["status"] not in {"active", "starting"}:
                raise ValueError("There is no active run to stop.")
            self.run["status"] = "stopping"
            self.persist_run()
            self.publish()
            try:
                self.send_command({"command": "stop"})
            except ValueError:
                self.finish("interrupted", "The Stop command could not reach the device.")
                self.publish()
                raise

    def saved_run(self, run_id):
        if not isinstance(run_id, str) or not SAFE_ID.fullmatch(run_id):
            return None
        folder = self.runs / run_id
        path = folder / "run.json"
        if folder.is_symlink() or folder.resolve().parent != self.runs or path.is_symlink() or not path.is_file():
            return None
        ocr_folder = folder / "ocr"
        if ocr_folder.is_symlink() or ocr_folder.resolve().parent != folder.resolve():
            return None
        run = json.loads(path.read_text(encoding="utf-8"))
        return run if isinstance(run, dict) and run.get("id") == run_id else None

    def append_event(self, event, run=None):
        run = self.run if run is None else run
        line = json.dumps(event, ensure_ascii=False, separators=(",", ":")) + "\n"
        if run is self.run and self.event_file:
            self.event_file.write(line)
            self.event_file.flush()
        else:
            path = self.runs / run["id"] / "events.jsonl"
            if path.is_symlink():
                raise OSError("The run event file is a symbolic link.")
            with path.open("a", encoding="utf-8", newline="\n") as output:
                output.write(line)
        run["eventCount"] += 1

    def save_ocr(self, event, run=None):
        run = self.run if run is None else run
        data = event["data"]
        capture_id = str(data.get("captureId", event.get("seq", len(run["ocr"]) + 1)))
        safe_id = re.sub(r"[^A-Za-z0-9_-]", "_", capture_id)[:80] or "capture"
        filename = f"ocr/{safe_id}.txt"
        if any(item["file"] == filename for item in run["ocr"]):
            filename = f"ocr/{safe_id}-{len(run['ocr']) + 1}.txt"
        text = data.get("text", "")
        if not isinstance(text, str):
            raise ValueError("OCR text is not a string.")
        (self.runs / run["id"] / filename).write_text(text, encoding="utf-8", newline="")
        item = {"captureId": capture_id, "file": filename, "timestampMs": event.get("timestampMs"),
                "elapsedMs": event.get("elapsedMs"), "durationMs": data.get("durationMs"),
                "packageName": data.get("packageName"), "appLabel": data.get("appLabel"),
                "windowId": data.get("windowId"), "characters": len(text)}
        # Preserve keyboard masks and mask metadata from earlier app versions.
        for key in ("captureScope", "sourceAttribution", "sourceLabel", "textRegions", "maskedRegions"):
            if key in data:
                item[key] = data[key]
        run["ocr"].append(item)
        record = {**item, "text": text}
        self.write_ocr_record(record, run)
        if run is self.run:
            self.ocr.append(record)
            self.publish("ocr", record)

    def write_ocr_record(self, record, run=None):
        run = self.run if run is None else run
        path = (self.runs / run["id"] / record["file"]).with_suffix(".json")
        write_json(path, record)

    def save_jev(self, event, persist=True, run=None):
        run = self.run if run is None else run
        if not run or event.get("runId") != run["id"]:
            return
        data = event["data"]
        capture_id = str(data.get("captureId", ""))
        item = next((item for item in run["ocr"] if item["captureId"] == capture_id), None)
        if item is None:
            return
        previous = item.get("jev")
        if previous and previous.get("status") not in {"pending", "canceled"}:
            return
        if run is self.run:
            record = next((record for record in self.ocr if record["file"] == item["file"]), None)
        else:
            folder = self.runs / run["id"]
            relative = Path(item["file"])
            path = folder / relative
            if relative.parent != Path("ocr") or path.is_symlink() or path.resolve().parent != (folder / "ocr").resolve():
                return
            record = {**item, "text": path.read_text(encoding="utf-8")}
        if record is None:
            return
        # Android owns the questions, model, probabilities, and decision rule.
        jev = {key: value for key, value in data.items() if key != "captureId"}
        item["jev"] = jev
        record["jev"] = jev
        if persist:
            self.write_ocr_record(record, run)
        if run is self.run:
            self.publish("jev", {"captureId": capture_id, "jev": jev})

    def persist_run(self, run=None):
        run = self.run if run is None else run
        if run:
            write_json(self.runs / run["id"] / "run.json", run)

    def write_summary(self, run=None):
        run = self.run if run is None else run
        if not run:
            return
        counters = run.get("counters", {})
        scope = {"foreground": "Current foreground app", "display": "Visible default display"}.get(
            run.get("captureScope"), run.get("targetPackage") or "Unknown")
        lines = ["Antiscam run", f"Run: {run['id']}", f"Status: {run['status']}",
                 f"Started: {run['startedAt']}", f"Ended: {run.get('endedAt') or 'Not ended'}",
                 f"Device: {run.get('device') or 'Unknown'}", f"App version: {run.get('appVersion') or 'Unknown'}",
                 f"Android API: {run.get('androidApi') or 'Unknown'}",
                 f"Capture scope: {scope}",
                 f"Events saved: {run.get('eventCount', 0)}", f"OCR files: {len(run.get('ocr', []))}"]
        lines += [f"{label}: {counters.get(key, 0)}" for key, label in [("requests", "Capture requests"), ("screenshots", "Screenshots received"), ("failed", "Screenshot failures"), ("ocrCompleted", "OCR completed"), ("ocrFailed", "OCR failures"), ("skipped", "Captures skipped")]]
        analyses = [item["jev"] for item in run.get("ocr", []) if item.get("jev")]
        if analyses or run.get("jevExpected"):
            lines += [f"Jev {status}: {sum(analysis.get('status') == status for analysis in analyses)}"
                      for status in ("pending", "completed", "failed", "skipped", "canceled")]
            flagged = sum(analysis.get("status") == "completed" and analysis.get("flagged") is True for analysis in analyses)
            lines.append(f"Probably scam flags: {flagged}")
            lines.append("Flags are experiment results, not proof of fraud. Failed, skipped, and canceled checks are not safe results.")
        lines += [f"Reason: {run.get('reason') or 'Run in progress'}", "",
                  "Full OCR text and capture JSON are in ocr/. Jev questions, probabilities, model, and threshold are saved when supplied by Android.",
                  "Events are in events.jsonl. No screenshot files or Jev API keys are saved."]
        summary = self.runs / run["id"] / "summary.txt"
        temporary = summary.with_suffix(".txt.tmp")
        temporary.write_text("\n".join(lines) + "\n", encoding="utf-8")
        temporary.replace(summary)

    def finish(self, status, reason):
        self.run["status"] = status
        self.run["endedAt"] = utc_now()
        self.run["reason"] = reason
        if self.state.get("counters"):
            self.run["counters"] = self.state["counters"]
        if self.event_file:
            self.event_file.flush()
            self.event_file.close()
            self.event_file = None
        self.persist_run()
        self.write_summary()
        self.recording_run = False

    def recording_failed(self):
        """Stop capture even when the disk cannot accept the final run metadata."""
        with self.lock:
            self.recording_error = "Recording failed. Check disk space and run-folder access, then reconnect. Partial files may be incomplete."
            self.connection = "disconnected"
            self.message = self.recording_error
            try:
                self.send_command({"command": "stop"})
            except ValueError:
                pass
            if self.bridge:
                try:
                    self.bridge.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass
                try:
                    self.bridge.close()
                except OSError:
                    pass
                self.bridge = None
            if self.run and self.recording_run:
                self.run["status"] = "interrupted"
                self.run["endedAt"] = utc_now()
                self.run["reason"] = self.recording_error
                self.run["recordingError"] = True
                self.run["counters"] = self.state.get("counters") or self.run.get("counters", {})
            if self.event_file:
                try:
                    self.event_file.close()
                except OSError:
                    pass
                self.event_file = None
            # One file can remain writable when another file fails. Keep each best effort separate.
            for save in (self.persist_run, self.write_summary):
                try:
                    save()
                except OSError:
                    pass
            self.recording_run = False
            self.publish()

    def export(self):
        with self.lock:
            if not self.run:
                raise ValueError("Start a run before exporting.")
            self.write_summary()
            folder = self.runs / self.run["id"]
            content = io.BytesIO()
            with zipfile.ZipFile(content, "w", zipfile.ZIP_DEFLATED) as archive:
                names = ["summary.txt", "run.json", "events.jsonl"]
                for item in self.run["ocr"]:
                    names.extend((item["file"], str(Path(item["file"]).with_suffix(".json"))))
                for name in names:
                    path = folder / name
                    if path.is_file() and not path.is_symlink() and path.resolve().is_relative_to(folder.resolve()):
                        archive.write(path, f"{self.run['id']}/{name}")
            return self.run["id"], content.getvalue()

    def close(self):
        self.closed.set()
        self.connector_stop.set()
        with self.lock:
            if self.run and self.run["status"] in ACTIVE:
                try:
                    self.send_command({"command": "stop"})
                except ValueError:
                    pass
                try:
                    self.finish("interrupted", "The recorder closed before Stop was confirmed.")
                except OSError:
                    self.recording_failed()
            if self.bridge:
                try:
                    self.bridge.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass
                self.bridge.close()
                self.bridge = None


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *_arguments):
        # Request URLs, OCR content, and device output must not enter console logs.
        pass

    @property
    def console(self):
        return self.server.console

    def guard(self, mutation=False):
        expected = f"127.0.0.1:{self.server.server_port}"
        if self.headers.get("Host") != expected:
            self.respond(403, {"error": "Invalid dashboard host."})
            return False
        origin = self.headers.get("Origin")
        if (origin and origin != f"http://{expected}") or self.headers.get("Sec-Fetch-Site") == "cross-site":
            self.respond(403, {"error": "This dashboard accepts local same-origin requests only."})
            return False
        if mutation and (origin != f"http://{expected}" or not secrets.compare_digest(self.headers.get("X-Antiscam-Token", ""), self.console.token)):
            self.respond(403, {"error": "Invalid dashboard command token."})
            return False
        return True

    def send_headers(self, code, content_type, length=None):
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("Cross-Origin-Resource-Policy", "same-origin")
        self.send_header("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'")
        if length is not None:
            self.send_header("Content-Length", str(length))

    def respond(self, status, body):
        payload = encode(body)
        self.send_headers(status, "application/json; charset=utf-8", len(payload))
        self.end_headers()
        self.wfile.write(payload)

    def do_GET(self):
        if not self.guard():
            return
        path = urlsplit(self.path).path
        if path == "/api/session":
            self.respond(200, self.console.snapshot())
        elif path == "/api/events":
            self.stream()
        elif path == "/api/export":
            try:
                run_id, payload = self.console.export()
                self.send_headers(200, "application/zip", len(payload))
                self.send_header("Content-Disposition", f'attachment; filename="antiscam-{run_id}.zip"')
                self.end_headers()
                self.wfile.write(payload)
            except ValueError as error:
                self.respond(409, {"error": str(error)})
            except OSError:
                self.console.recording_failed()
                self.respond(500, {"error": self.console.recording_error})
        elif path in {"/", "/app.js", "/style.css"}:
            filename, mime = {"/": ("index.html", "text/html"), "/app.js": ("app.js", "text/javascript"), "/style.css": ("style.css", "text/css")}[path]
            payload = (HERE / filename).read_bytes()
            self.send_headers(200, mime + "; charset=utf-8", len(payload))
            self.end_headers()
            self.wfile.write(payload)
        else:
            self.respond(404, {"error": "Not found."})

    def do_POST(self):
        if not self.guard(mutation=True):
            self.close_connection = True
            return
        try:
            size = int(self.headers.get("Content-Length", "0"))
            if size < 0 or size > 8192 or self.headers.get("Content-Type", "").split(";")[0] != "application/json":
                raise ValueError("Send a small JSON request.")
            body = json.loads(self.rfile.read(size))
            if not isinstance(body, dict):
                raise ValueError("Send a JSON object.")
            path = urlsplit(self.path).path
            if path == "/api/devices":
                self.console.refresh_devices()
            elif path == "/api/connect":
                self.console.connect_device(body.get("serial"))
            elif path in KEY_COMMANDS:
                self.console.set_key(KEY_COMMANDS[path], body)
            elif path == "/api/command":
                command = body.get("command")
                if command == "start":
                    self.console.start_run()
                elif command == "stop":
                    self.console.stop_run()
                elif command in {"pause", "resume", "snapshot"}:
                    self.console.send_command({"command": command})
                else:
                    raise ValueError("Unknown command.")
            else:
                self.respond(404, {"error": "Not found."})
                return
            # The phone reports key readiness later through its state event.
            self.respond(200, {"ok": True})
        except (ValueError, RuntimeError, UnicodeDecodeError) as error:
            self.respond(409, {"error": str(error)})
        except OSError:
            self.console.recording_failed()
            self.respond(500, {"error": self.console.recording_error})

    def stream(self):
        subscriber = queue.Queue(maxsize=256)
        with self.console.lock:
            self.console.subscribers.add(subscriber)
            snapshot = encode({"kind": "snapshot", "data": self.console.snapshot()})
        try:
            self.send_headers(200, "text/event-stream; charset=utf-8")
            self.send_header("Connection", "keep-alive")
            self.end_headers()
            self.wfile.write(b"retry: 1500\n\ndata: " + snapshot + b"\n\n")
            self.wfile.flush()
            while not self.console.closed.is_set():
                try:
                    message = subscriber.get(timeout=20)
                except queue.Empty:
                    self.wfile.write(b": keepalive\n\n")
                    self.wfile.flush()
                    continue
                if message is None:
                    break
                self.wfile.write(b"data: " + message + b"\n\n")
                self.wfile.flush()
        except (OSError, ConnectionError):
            pass
        finally:
            with self.console.lock:
                self.console.subscribers.discard(subscriber)
            self.close_connection = True


def open_browser(url):
    if os.name == "nt":
        for base in (os.environ.get("PROGRAMFILES(X86)"), os.environ.get("PROGRAMFILES"), os.environ.get("LOCALAPPDATA")):
            if not base:
                continue
            for relative in ("Microsoft/Edge/Application/msedge.exe", "Google/Chrome/Application/chrome.exe"):
                executable = Path(base) / relative
                if executable.is_file():
                    subprocess.Popen([str(executable), f"--app={url}", "--new-window", "--window-size=1160,900"],
                                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                    return
    webbrowser.open(url, new=1)


def main():
    parser = argparse.ArgumentParser(description="Antiscam local run console")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--serial")
    parser.add_argument("--runs-directory", type=Path, default=ROOT / "runs", help="Directory for saved run records.")
    parser.add_argument("--no-browser", action="store_true")
    parser.add_argument("--open-only", action="store_true", help="Open an existing dashboard in its own browser window.")
    args = parser.parse_args()
    if args.open_only:
        open_browser(f"http://127.0.0.1:{args.port}/")
        return
    console = Console(args.runs_directory)
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    server.daemon_threads = True
    server.console = console
    url = f"http://127.0.0.1:{server.server_port}/"
    print(f"Antiscam run console: {url}", flush=True)

    def initialize():
        console.refresh_devices(auto_connect=not args.serial)
        if args.serial:
            try:
                console.connect_device(args.serial)
            except ValueError as error:
                with console.lock:
                    console.message = str(error)
                    console.publish()

    threading.Thread(target=initialize, daemon=True).start()
    if not args.no_browser:
        open_browser(url)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        console.close()
        server.server_close()


if __name__ == "__main__":
    main()
