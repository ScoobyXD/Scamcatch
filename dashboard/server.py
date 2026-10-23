"""Local Antiscam live console. Only validated incident fields can reach disk."""
from __future__ import annotations

import argparse
import io
import json
import math
import os
from pathlib import Path
import queue
import re
import secrets
import shutil
import socket
import subprocess
import sys
import threading
import time
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit
import webbrowser
import zipfile

import credential_store
from incident_records import IncidentStore, RECORDING_POLICY


HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
sys.path.insert(0, str(ROOT / "scripts"))
from voice_scripts import load_voice_scripts

VOICE_SCRIPTS_FILE = ROOT / "app" / "src" / "main" / "assets" / "voice-scripts.md"
ACTIVE = {"starting", "active", "stopping"}
SAFE_ID = re.compile(r"^[A-Za-z0-9_-]{1,100}$")
JEV_EVENTS = {"jev_started", "jev_completed", "jev_failed", "jev_skipped", "jev_canceled"}
LATE_RESULTS = {"screenshot_received", "capture_failed", "capture_discarded", "ocr_started", "ocr_completed", "ocr_failed", "voice_audio_summary"} | JEV_EVENTS
KEY_COMMANDS = {"/api/jev-key": "jev_key", "/api/openai-key": "openai_key"}
COUNTER_FIELDS = ("requests", "screenshots", "failed", "ocrCompleted", "ocrFailed", "skipped")
EVENT_FIELDS = (
    "captureId", "windowId", "packageName", "appLabel", "captureScope", "sourceAttribution",
    "trigger", "kind", "eventType", "value", "status", "durationMs", "delayMs", "characters",
    "regionCount", "maskedRegionCount", "model", "questionSetVersion", "threshold", "flagged", "runMode", "phase",
    "voice", "scriptId", "scriptRevision", "transport", "httpStatus", "finalizationConfirmed",
    "usageSeconds", "rtpBytesReceived", "rtpBytesSent", "rtpPacketsReceived", "rtpPacketsLost",
    "rtpJitterSeconds", "jitterBufferDelaySeconds", "jitterBufferEmittedCount", "concealedSamples",
    "totalSamplesReceived", "remoteAudioEnergy", "remoteAudioDurationSeconds",
)
STATE_FIELDS = (
    "runId", "runMode", "dashboardConnected", "currentApp", "currentPackage", "lastExternalApp",
    "typing", "lastInteraction", "captureStatus", "ocrStatus", "voiceReady", "voiceConsentGranted",
    "warningActive", "voiceStatus", "jevReady", "jevStatus", "jevFlagged", "jevCaptureId",
    "captureScope", "consentGranted", "monitoringEnabled", "serviceConnected", "appVersion",
    "androidApi", "approvedAppCount", "standaloneRunRequested", "protectionServiceConnected",
)
SAFE_REASONS = {
    "Android needs more time between screenshots.",
    "Screenshot access is unavailable. Check Android accessibility settings.",
    "The display is unavailable.", "The target window is no longer available.",
    "This app protects its screen. Antiscam cannot capture it.",
    "Android could not prepare the screenshot for OCR.",
    "Android rejected the screenshot request. Check accessibility access.",
    "The local text model could not read this screenshot.", "The local text model could not start.",
    "No development API key is loaded.", "OCR returned no text.",
    "OCR text exceeds the development request limit.", "The Jev request timed out.",
    "The Jev request could not reach the service.", "Jev returned an unreadable or invalid response.",
    "TypeSafe rejected the development API key or its access.", "TypeSafe rate-limited this request.",
    "TypeSafe returned a redirect. The request was not forwarded.", "TypeSafe is temporarily unavailable.",
    "Jev returned an empty response.", "Jev returned an oversized response.",
    "Jev returned an unexpected model version.", "Jev did not return the configured Noul answers.",
    "Jev returned an unexpected number of Noul answers.", "Jev returned a missing or invalid Noul answer.",
    "Android blocked microphone access. Check microphone permission.",
    "This phone could not load the voice audio engine.", "Voice could not continue its audio connection.",
    "Voice could not use the phone's microphone.", "Voice could not use the phone's speaker.",
    "Stop the phone run in Antiscam.", "Stop the current run before changing voice scripts.",
    "Wait for the phone to finish saving voice scripts.", "Wait for a valid voice script to load before starting a run.",
}
EVENT_REASONS = {
    "capture_failed": "Capture failed. Check accessibility access and the app's screen restrictions.",
    "capture_skipped": "The screen or selected app did not pass the capture checks.",
    "capture_discarded": "Capture stopped because permission or the active screen changed.",
    "ocr_failed": "Local text recognition failed.",
    "jev_failed": "Jev analysis failed. Check the API key and Internet connection.",
    "voice_failed": "Voice failed. Check microphone permission, Internet access, and the provider error code.",
    "command_error": "The phone rejected the command. Check phone setup, run state, and the selected voice script.",
}


def safe_reason(value, event_type=None):
    if isinstance(value, str) and (value in SAFE_REASONS or re.fullmatch(r"[a-z][a-z0-9_]{0,95}", value)):
        return value
    return EVENT_REASONS.get(event_type)


def scalar_fields(value, fields):
    """Filter transient dashboard state. This is never a persistence schema."""
    if not isinstance(value, dict):
        return {}
    return {key: item for key in fields if key in value
            and ((item := value[key]) is None or isinstance(item, (str, int, float, bool)))
            and (not isinstance(item, float) or math.isfinite(item))}


def numeric_fields(value, fields):
    return {key: item for key, item in scalar_fields(value, fields).items()
            if type(item) in (int, float)}


def script_metadata(value, instructions=False):
    return scalar_fields(value, ("id", "name", "revision", "instructions") if instructions
                         else ("id", "name", "revision"))


def jev_metadata(value):
    result = scalar_fields(value, ("status", "model", "questionSetVersion", "threshold", "durationMs", "flagged"))
    if not isinstance(value, dict):
        return result
    reason = safe_reason(value.get("reason"), "jev_failed" if value.get("status") == "failed" else None)
    if reason:
        result["reason"] = reason
    questions = value.get("questions")
    if isinstance(questions, dict):
        # Question configuration is static. Never retain nested context or captured evidence.
        result["questions"] = {key: scalar_fields(question, ("type", "instructions"))
                               for key, question in questions.items() if SAFE_ID.fullmatch(key)}
    answers = value.get("answers")
    if isinstance(answers, dict):
        result["answers"] = {key: {"type": "noul", "noul": answer["noul"]}
                             for key, answer in answers.items()
                             if SAFE_ID.fullmatch(key) and isinstance(answer, dict)
                             and answer.get("type") == "noul" and type(answer.get("noul")) in (int, float)
                             and math.isfinite(answer["noul"]) and 0 <= answer["noul"] <= 1}
    return result


def state_metadata(value):
    result = scalar_fields(value, STATE_FIELDS)
    if not isinstance(value, dict):
        return result
    result["counters"] = numeric_fields(value.get("counters"), COUNTER_FIELDS)
    result["voiceScript"] = script_metadata(value.get("voiceScript"))
    scripts = value.get("voiceScripts")
    if isinstance(scripts, dict):
        result["voiceScripts"] = scalar_fields(scripts, ("available", "ready", "working", "error", "message", "selectedId"))
        result["voiceScripts"]["scripts"] = [script_metadata(item) for item in scripts.get("scripts", [])
                                              if isinstance(item, dict)]
    keys = value.get("phoneKeys")
    if isinstance(keys, dict):
        result["phoneKeys"] = {provider: scalar_fields(keys.get(provider), ("working", "saved", "message", "error"))
                               for provider in ("jev", "openai")}
    return result


def event_metadata(event):
    result = scalar_fields(event, ("protocol", "seq", "timestampMs", "elapsedMs", "runId", "type"))
    source = event.get("data")
    if not isinstance(source, dict):
        source = {}
    data = scalar_fields(source, EVENT_FIELDS)
    for key in ("code", "errorType", "param", "command"):
        identifier = source.get(key)
        if isinstance(identifier, str) and len(identifier) <= 96 and re.fullmatch(r"[a-z][a-z0-9_]*(?:[.\[][a-z0-9_]+\]?)*", identifier):
            data[key] = identifier
    # Older phones can still send raw OCR. Count it, then discard it before storage or UI delivery.
    if "characters" not in data and isinstance(source.get("text"), str):
        data["characters"] = len(source["text"])
    if "regionCount" not in data and isinstance(source.get("textRegions"), list):
        data["regionCount"] = len(source["textRegions"])
    if isinstance(source.get("maskedRegions"), list):
        data["maskedRegionCount"] = len(source["maskedRegions"])
    if result.get("type") in JEV_EVENTS:
        data.update(jev_metadata(source))
    if isinstance(source.get("voiceScript"), dict):
        data["voiceScript"] = script_metadata(source["voiceScript"], instructions=True)
    # Provider and script errors can quote input. Keep known messages or safe status explanations.
    reason = safe_reason(source.get("reason"), result.get("type"))
    if reason:
        data["reason"] = reason
    result["data"] = data
    result["state"] = state_metadata(event.get("state"))
    return result


def utc_now():
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds")


def encode(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


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
        self.incidents = IncidentStore(runs)
        self.incident_count = 0
        self.incident_error = None
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
        self.subscribers = set()
        self.bridge = None
        self.forward_port = None
        self.connector = None
        self.connector_stop = threading.Event()
        self.closed = threading.Event()
        self.recording_error = None
        self.key_storage = {"available": credential_store.AVAILABLE, "saved": {}, "error": None}
        try:
            self.incident_count = len(self.incidents.read())
        except (OSError, ValueError):
            self.incident_error = "Saved incidents are unavailable. No data was loaded."

    def snapshot(self):
        with self.lock:
            return {
                "token": self.token,
                "connection": self.connection, "message": self.message,
                "devices": self.devices, "selectedDevice": self.selected_device,
                "state": self.state, "run": self.run, "ocr": self.ocr,
                "activity": self.activity,
                "keyStorage": self.key_storage,
                "recordingPolicy": RECORDING_POLICY, "incidentCount": self.incident_count,
                "incidentError": self.incident_error,
            }

    def publish(self, kind="status", data=None):
        with self.lock:
            if data is None:
                data = {"connection": self.connection, "message": self.message,
                        "devices": self.devices, "selectedDevice": self.selected_device,
                        "state": self.state, "run": self.run, "keyStorage": self.key_storage,
                        "recordingPolicy": RECORDING_POLICY, "incidentCount": self.incident_count,
                        "incidentError": self.incident_error}
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
            if self.connection == "connected" and self.state.get("runId") and self.state.get("runMode") != "standalone":
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
            remember_on_phone = body.get("rememberOnPhone", False)
            if not all(isinstance(value, bool) for value in (use_saved, remember, remember_on_phone)):
                raise ValueError("Choose whether to remember this key on this PC and phone.")
            phone_key = self.state.get("phoneKeys", {}).get(provider)
            if remember_on_phone and not isinstance(phone_key, dict):
                raise ValueError("Update Antiscam Dev before saving keys on the phone from this console.")
            if isinstance(phone_key, dict) and phone_key.get("working"):
                raise ValueError("Wait for the phone to finish updating this key.")
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
                self.send_command({"command": command, "apiKey": api_key, "rememberOnPhone": remember_on_phone})
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
            if event.get("type") == "scam_incident":
                if (not self.run or self.run["status"] != "active"
                        or event.get("runId") != self.run["id"]
                        or not all(self.state.get(key) for key in ("consentGranted", "monitoringEnabled", "serviceConnected"))):
                    return
                try:
                    self.incident_count = self.incidents.append(event.get("data"))
                    self.incident_error = None
                except ValueError:
                    self.incident_error = "Incident rejected. No data was saved."
                self.publish()
                return
            event = event_metadata(event)
            state = event.get("state")
            if isinstance(state, dict):
                reconnected = self.connection != "connected"
                self.state = state
                self.connection = "connected"
                self.message = ("Phone run active. Stop it in Antiscam before starting a desktop run."
                                if state.get("runMode") == "standalone"
                                else "Device connected. Start a run when the setup checks are complete.")
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
            if run:
                active = run is self.run and run["status"] in ACTIVE
                if kind == "run_started":
                    run["status"] = "active"
                    if isinstance(data.get("voiceScript"), dict):
                        run["voiceScript"] = data["voiceScript"]
                if kind == "ocr_completed":
                    self.receive_ocr(event, run)
                if kind in JEV_EVENTS:
                    self.receive_jev(event, run)
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
            if kind == "command_error":
                self.message = str(data.get("reason") or "The device rejected the command.")
                if self.run and self.run["status"] == "starting":
                    self.finish("failed", self.message)
                elif self.run and self.run["status"] == "stopping":
                    self.run["status"] = "active"
            # A reconnect can reveal an orphaned desktop run. Phone runs keep their local owner.
            if (kind == "snapshot" and self.state.get("runId") and self.state.get("runMode") != "standalone"
                    and not (self.run and self.run["status"] in ACTIVE)):
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
                "code": data.get("code"), "errorType": data.get("errorType"), "param": data.get("param"),
                "command": data.get("command"), "httpStatus": data.get("httpStatus"),
                "captureScope": data.get("captureScope"), "sourceLabel": data.get("sourceLabel"),
                "sourceAttribution": data.get("sourceAttribution"),
                "appLabel": data.get("appLabel") or state.get("currentApp"),
                "packageName": data.get("packageName") or state.get("currentPackage")}

    def update_voice_scripts(self, body):
        with self.lock:
            if self.connection != "connected" or not self.bridge or body.get("serial") != self.selected_device:
                raise ValueError("Connect the selected phone before changing voice scripts.")
            if self.state.get("runId") or (self.run and self.run["status"] in ACTIVE):
                raise ValueError("Stop the current run before changing voice scripts.")
            phone = self.state.get("voiceScripts")
            if not isinstance(phone, dict) or not phone.get("available"):
                raise ValueError("Update Antiscam Dev to use voice scripts.")
            if phone.get("working"):
                raise ValueError("Wait for the phone to finish saving voice scripts.")
            markdown, catalog = load_voice_scripts(VOICE_SCRIPTS_FILE)
            action = body.get("action")
            if action == "sync":
                self.send_command({"command": "voice_scripts", "markdown": markdown})
            elif action == "select":
                script = next((entry for entry in catalog["scripts"] if entry["id"] == body.get("scriptId")), None)
                if script is None:
                    raise ValueError("Choose a script from the current file.")
                if not any(entry.get("id") == script["id"] and entry.get("revision") == script["revision"]
                           for entry in phone.get("scripts", [])):
                    raise ValueError("Copy the updated script file to the phone before selecting this version.")
                self.send_command({"command": "voice_script_select", "scriptId": script["id"], "revision": script["revision"]})
            else:
                raise ValueError("Choose sync or select for voice scripts.")

    def start_run(self):
        with self.lock:
            if self.connection != "connected":
                raise ValueError("Connect the emulator first.")
            if self.run and self.run["status"] in ACTIVE:
                raise ValueError("A run is already active.")
            if self.state.get("runId"):
                if self.state.get("runMode") == "standalone":
                    raise ValueError("Stop the phone run in Antiscam before starting a desktop run.")
                raise ValueError("The device still has an active run. Wait for Stop to finish.")
            if not all(self.state.get(key) for key in ("consentGranted", "monitoringEnabled", "serviceConnected")):
                raise ValueError("Allow capture and cloud text analysis, resume capture, and enable the accessibility service in Antiscam Dev.")
            if type(self.state.get("approvedAppCount")) is not int:
                raise ValueError("Update Antiscam on the phone to enable approved-app capture.")
            if self.state["approvedAppCount"] < 1:
                raise ValueError("Choose approved apps in Antiscam on the phone before starting a run.")
            run_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "_" + secrets.token_hex(4)
            self.run = {"id": run_id, "status": "starting", "startedAt": utc_now(), "endedAt": None,
                        "device": self.selected_device, "captureScope": self.state.get("captureScope", "foreground"),
                        "appVersion": self.state.get("appVersion"), "androidApi": self.state.get("androidApi"),
                        "counters": {}, "ocr": [], "reason": None, "jevExpected": True,
                        "recordingPolicy": RECORDING_POLICY}
            self.ocr = []
            self.activity = []
            self.publish("snapshot", self.snapshot())
            try:
                self.send_command({"command": "start", "runId": run_id})
            except ValueError:
                self.finish("failed", "The Start command did not reach the device.")
                self.publish()
                raise

    def stop_run(self):
        with self.lock:
            if not self.run or self.run["status"] not in {"active", "starting"}:
                raise ValueError("There is no active run to stop.")
            self.run["status"] = "stopping"
            self.publish()
            try:
                self.send_command({"command": "stop"})
            except ValueError:
                self.finish("interrupted", "The Stop command could not reach the device.")
                self.publish()
                raise

    def receive_ocr(self, event, run):
        # Live summaries stay in memory. They are not incident records.
        data = event["data"]
        capture_id = str(data.get("captureId", event.get("seq", len(run["ocr"]) + 1)))
        item = {"captureId": capture_id, "timestampMs": event.get("timestampMs"),
                "elapsedMs": event.get("elapsedMs"), "durationMs": data.get("durationMs"),
                "packageName": data.get("packageName"), "appLabel": data.get("appLabel"),
                "windowId": data.get("windowId"), "characters": data.get("characters"),
                "regionCount": data.get("regionCount"), "maskedRegionCount": data.get("maskedRegionCount"),
                "captureScope": data.get("captureScope"), "sourceAttribution": data.get("sourceAttribution")}
        run["ocr"].append(item)
        if run is self.run:
            self.ocr.append(item)
            self.publish("ocr", item)

    def receive_jev(self, event, run):
        item = next((item for item in run["ocr"] if item["captureId"] == str(event["data"].get("captureId", ""))), None)
        if item is None:
            return
        previous = item.get("jev")
        if previous and previous.get("status") not in {"pending", "canceled"}:
            return
        # Numeric detector output is live status, never a confirmed scam count or type.
        item["jev"] = jev_metadata(event["data"])
        self.publish("jev", {"captureId": item["captureId"], "jev": item["jev"]})

    def finish(self, status, reason):
        self.run["status"] = status
        self.run["endedAt"] = utc_now()
        self.run["reason"] = reason
        if self.state.get("counters"):
            self.run["counters"] = self.state["counters"]

    def recording_failed(self):
        """Stop capture after an incident-storage failure. Never save diagnostic fallback files."""
        with self.lock:
            self.recording_error = "Incident storage failed. Check disk access, then reconnect. Live diagnostics were not saved."
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
            if self.run and self.run["status"] in ACTIVE:
                self.run["status"] = "interrupted"
                self.run["endedAt"] = utc_now()
                self.run["reason"] = self.recording_error
                self.run["recordingError"] = True
                self.run["counters"] = self.state.get("counters") or self.run.get("counters", {})
            self.publish()

    def export(self):
        with self.lock:
            # Revalidate the file, including nested fields, before any bytes leave the server.
            records = self.incidents.read()
            if not records:
                raise ValueError("No approved incident records are available to export.")
            content = io.BytesIO()
            with zipfile.ZipFile(content, "w", zipfile.ZIP_DEFLATED) as archive:
                # A fixed member date avoids adding the export time to the retained record.
                member = zipfile.ZipInfo("incidents.json", date_time=(1980, 1, 1, 0, 0, 0))
                member.compress_type = zipfile.ZIP_DEFLATED
                archive.writestr(member, encode(records))
            return content.getvalue()

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


class ConsoleServer(ThreadingHTTPServer):
    def handle_error(self, request, client_address):
        # The default handler prints client addresses and exception values to stderr.
        # A failing request must not create an unvalidated diagnostic record.
        pass


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
        elif path == "/api/voice-scripts":
            try:
                _, catalog = load_voice_scripts(VOICE_SCRIPTS_FILE)
                self.respond(200, {"path": str(VOICE_SCRIPTS_FILE), **catalog})
            except (OSError, ValueError) as error:
                self.respond(409, {"error": str(error) if isinstance(error, ValueError) else "The voice script file could not be read."})
        elif path == "/api/events":
            self.stream()
        elif path == "/api/export":
            try:
                payload = self.console.export()
                self.send_headers(200, "application/zip", len(payload))
                self.send_header("Content-Disposition", 'attachment; filename="scamcatch-incidents.zip"')
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
            elif path == "/api/voice-scripts":
                self.console.update_voice_scripts(body)
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
    parser.add_argument("--runs-directory", type=Path, default=ROOT / "runs", help="Directory for validated incident records. Legacy run files are never read.")
    parser.add_argument("--no-browser", action="store_true")
    parser.add_argument("--open-only", action="store_true", help="Open an existing dashboard in its own browser window.")
    args = parser.parse_args()
    if args.open_only:
        open_browser(f"http://127.0.0.1:{args.port}/")
        return
    console = Console(args.runs_directory)
    server = ConsoleServer(("127.0.0.1", args.port), Handler)
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
