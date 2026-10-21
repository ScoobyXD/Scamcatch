"""ScamCatch local website and live voice endpoint. Python 3.11+, standard library only."""
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import unquote, urlsplit


HERE = Path(__file__).resolve().parent
PUBLIC = (HERE / "public").resolve()
DEPLOYMENT = HERE / "infra" / "deployment.json"
INSTRUCTIONS = HERE.parents[1] / "app" / "ai-instructions" / "AIInstructions.kt"
sys.path.insert(0, str(HERE.parents[1] / "scripts"))
from voice_scripts import load_voice_scripts

VOICE_SCRIPTS_FILE = HERE.parents[1] / "app" / "src" / "main" / "assets" / "voice-scripts.md"
LIVE_SESSIONS_URL = "https://api.openai.com/v1/live/sessions"
VOICE_PATH = "/api/voice/session"
MAX_REQUEST_BYTES = 32 * 1024
MAX_RESPONSE_BYTES = 128 * 1024
MAX_EVIDENCE_CHARS = 6000  # Same bound as LiveVoice.
# OpenAI's built-in voices. A request may pick one with "voice"; VOICE_NAME stays the default.
VOICES = ("alloy", "ash", "ballad", "cedar", "coral", "echo", "fable", "marin", "nova", "onyx", "sage", "shimmer", "verse")
START_GAP_SECONDS = 10
PROMPTS = ("VOICE_MODEL", "VOICE_NAME", "VOICE_GREETING", "VOICE_EVIDENCE_PREFIX",
           "VOICE_TRUNCATED_EVIDENCE_SUFFIX", "VOICE_NO_BACKEND_COMMENTARY")
# "Click to hear explanation" scripts, published with the site. Read on every page call, so edits need no restart.
PAGES_FILE = PUBLIC / "voice-pages.json"
TYPES = {".html": "text/html; charset=utf-8", ".css": "text/css; charset=utf-8", ".js": "text/javascript; charset=utf-8",
         ".svg": "image/svg+xml", ".woff2": "font/woff2", ".png": "image/png", ".jpg": "image/jpeg",
         ".webp": "image/webp", ".ico": "image/x-icon"}
LITERAL = r'"""[\s\S]*?"""|"(?:[^"\\\n]|\\.)*"'
CONSTANT = re.compile(rf'const val (\w+)(?:\s*:\s*String)?\s*=\s*((?:{LITERAL})(?:\s*\+\s*(?:{LITERAL}))*)(?!\s*[.+])')
ESCAPE = re.compile(r"\\(u[0-9A-Fa-f]{4}|.)")
ESCAPES = {"t": "\t", "b": "\b", "n": "\n", "r": "\r", "'": "'", '"': '"', "\\": "\\", "$": "$"}
TEMPLATE = re.compile(r"(?<!\\)\$[A-Za-z_{]")
OPENAI_KEY = re.compile(r"sk-[A-Za-z0-9_-]{20,}")
HEADER_SAFE = re.compile(r"[\x21-\x7e]{1,2048}")
SAFE_ID = re.compile(r"[a-z][a-z0-9_]*(?:[.\[][a-z0-9_]+\]?)*")
NOT_READY = "Live voice isn’t set up on this computer yet."
KEY_UNAVAILABLE = "Live voice can’t read its key right now."


class VoiceError(Exception):
    """public goes to the browser; detail goes to this console. Neither may contain the key."""

    def __init__(self, status, public, detail=None):
        super().__init__(public)
        self.status, self.public, self.detail = status, public, detail


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *_arguments, **_keywords):
        return None


OPENER = urllib.request.build_opener(NoRedirect)


def kotlin_string(name, expression):
    parts = []
    for literal in re.finditer(LITERAL, expression):
        text = literal[0]
        if TEMPLATE.search(text):
            raise ValueError(f"{name} uses a Kotlin string template.")
        parts.append(text[3:-3] if text.startswith('"""') else ESCAPE.sub(lambda match: unescape(name, match[1]), text[1:-1]))
    return "".join(parts)


def unescape(name, code):
    if len(code) == 5:
        return chr(int(code[1:], 16))
    if code not in ESCAPES:
        raise ValueError(f"{name} has an unsupported escape.")
    return ESCAPES[code]


def load_prompts():
    """Load the two website scripts from the phone library, independent of its active selection."""
    source = INSTRUCTIONS.read_text(encoding="utf-8")
    found = {match[1]: kotlin_string(match[1], match[2]) for match in CONSTANT.finditer(source) if match[1] in PROMPTS}
    _, catalog = load_voice_scripts(VOICE_SCRIPTS_FILE)
    website_ids = ("baseline", "uses_scam_language")
    found["VOICE_SCRIPTS"] = {script["id"]: script["instructions"] for script in catalog["scripts"]
                             if script["id"] in website_ids}
    if any(script_id not in found["VOICE_SCRIPTS"] for script_id in website_ids):
        raise ValueError("Keep baseline and uses_scam_language in voice-scripts.md for the website demo.")
    missing = [name for name in PROMPTS if not found.get(name)]
    if missing:
        raise ValueError(f"{INSTRUCTIONS.name} has no plain string constant for {', '.join(missing)}.")
    return found


def secret_key(text, field):
    """Accept a raw key or a JSON secret: the configured field, or the first value that looks like an OpenAI key."""
    if not field and not text.startswith("{"):
        return text if OPENAI_KEY.fullmatch(text) else None
    try:
        values = json.loads(text)
    except ValueError:
        return None
    if not isinstance(values, dict):
        return None
    if field:
        value = values.get(field)
        return value.strip() if isinstance(value, str) and HEADER_SAFE.fullmatch(value.strip()) else None
    return next((value.strip() for value in values.values() if isinstance(value, str) and OPENAI_KEY.fullmatch(value.strip())), None)


def evidence_text(context, prompts):
    """Frame the demo chat like the app's screen evidence: prefix, flagged signal, captured text, 6,000-character bound."""
    conversation = context.get("conversation") if isinstance(context, dict) else None
    flagged = context.get("flaggedMessage") if isinstance(context, dict) else None
    if not (isinstance(conversation, list) and 0 < len(conversation) <= 60 and isinstance(flagged, str) and flagged.strip()
            and all(isinstance(item, dict) and isinstance(item.get("name"), str) and isinstance(item.get("text"), str) for item in conversation)):
        raise VoiceError(400, "Voice needs the demo conversation to start.")
    captured = "\n".join(f"{item['name'].strip()}: {item['text'].strip()}" for item in conversation)
    evidence = json.dumps({"flaggedMessage": flagged.strip(), "capturedText": captured}, ensure_ascii=False, separators=(",", ":"))
    if len(evidence) > MAX_EVIDENCE_CHARS:
        evidence = evidence[:MAX_EVIDENCE_CHARS] + prompts["VOICE_TRUNCATED_EVIDENCE_SUFFIX"]
    return prompts["VOICE_EVIDENCE_PREFIX"] + evidence


def page_scripts(data):
    """voice-pages.json as (instructions, {page id: notes}), each list of lines joined; None when it isn't in that shape."""
    def text(value):
        return "\n".join(value) if isinstance(value, list) and value and all(isinstance(line, str) for line in value) else None
    pages = data.get("pages") if isinstance(data, dict) else None
    instructions = text(data.get("instructions")) if isinstance(data, dict) else None
    notes = {page: text(value) for page, value in pages.items()} if isinstance(pages, dict) else {}
    return (instructions, notes) if instructions and notes and all(notes.values()) else None


def session_input(context, prompts):
    """Instructions and first input: a page explanation when context names a page, otherwise the demo chat as evidence."""
    page = context.get("page") if isinstance(context, dict) else None
    if page is None:
        script_id = context.get("scriptId", "baseline") if isinstance(context, dict) else "baseline"
        if not isinstance(script_id, str) or script_id not in prompts["VOICE_SCRIPTS"]:
            raise VoiceError(400, "Choose a supported voice script.")
        return prompts["VOICE_SCRIPTS"][script_id], evidence_text(context, prompts)
    try:
        scripts = page_scripts(json.loads(PAGES_FILE.read_text(encoding="utf-8")))
    except (OSError, ValueError):
        scripts = None
    if not scripts:
        raise VoiceError(503, "Voice can’t load this page’s explanation right now.", f"{PAGES_FILE.name} is missing or not in the expected shape.")
    instructions, notes = scripts
    if not (isinstance(page, str) and page in notes):
        raise VoiceError(400, "Voice can’t explain that page.")
    return instructions, notes[page]


class Voice:
    def __init__(self):
        self.lock = threading.Lock()
        self.key = None
        self.last_start = -START_GAP_SECONDS
        self.prompts = self.problem = None
        try:
            self.prompts = load_prompts()
            deployment = json.loads(DEPLOYMENT.read_text(encoding="utf-8"))
            secret = deployment.get("voiceKeySecret") or {}
            self.profile = str(deployment.get("awsProfile") or "").strip()
            self.secret_id = str(secret.get("secretId") or "").strip()
            self.region = str(secret.get("region") or "").strip()
            self.field = str(secret.get("jsonField") or "").strip()
        except (OSError, ValueError, AttributeError) as error:
            self.problem = f"Live voice is off: {error}"
            return
        if not (self.profile and self.secret_id and self.region):
            self.problem = ("Live voice is off. Fill in voiceKeySecret.secretId and voiceKeySecret.region "
                            "in website/scamcatch/infra/deployment.json, then restart this window.")

    def describe(self):
        if self.problem:
            return self.problem
        return (f"Live voice: {self.prompts['VOICE_MODEL']} / {self.prompts['VOICE_NAME']}. "
                f"Key from AWS secret {self.secret_id} ({self.region}, profile {self.profile}).")

    def preload(self):
        with self.lock:
            try:
                self.read_key()
                print("Live voice key loaded into memory.", flush=True)
            except VoiceError as error:
                print(error.detail or error.public, flush=True)

    def read_key(self):
        if self.key:
            return self.key
        aws = shutil.which("aws")
        if not aws:
            raise VoiceError(503, KEY_UNAVAILABLE, f"AWS CLI not found. Install AWS CLI v2, sign in to profile {self.profile}, then restart this window.")
        command = [aws, "secretsmanager", "get-secret-value", "--secret-id", self.secret_id, "--region", self.region,
                   "--profile", self.profile, "--query", "SecretString", "--output", "text"]
        try:
            result = subprocess.run(command, capture_output=True, encoding="utf-8", errors="replace", timeout=30,
                                    env={**os.environ, "AWS_PAGER": ""}, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        except (OSError, subprocess.TimeoutExpired):
            raise VoiceError(503, KEY_UNAVAILABLE, "The AWS CLI did not respond while reading the voice key.") from None
        if result.returncode:
            # AWS CLI errors name the secret and the failure, never the secret value.
            reason = next((line.strip() for line in reversed(result.stderr.splitlines()) if line.strip()), "no error text")
            raise VoiceError(503, KEY_UNAVAILABLE, f"AWS could not read secret {self.secret_id}: {reason[:300]}")
        key = secret_key(result.stdout.strip(), self.field)
        if not key:
            where = f"field {self.field}" if self.field else "a raw key or a JSON value that starts with sk-"
            raise VoiceError(503, KEY_UNAVAILABLE, f"Secret {self.secret_id} has no OpenAI key. Expected {where}.")
        self.key = key
        return key

    def start(self, body):
        offer = body.get("sdp")
        if not (isinstance(offer, str) and offer.startswith("v=0") and len(offer) <= 20000):
            raise VoiceError(400, "Voice needs a WebRTC offer to start.")
        if self.problem:
            raise VoiceError(503, NOT_READY, self.problem)
        voice = body.get("voice", self.prompts["VOICE_NAME"])
        if voice not in VOICES:
            raise VoiceError(400, "That voice name isn't one of OpenAI's built-in voices.")
        instructions, evidence = session_input(body.get("context"), self.prompts)
        with self.lock:
            now = time.monotonic()
            if now - self.last_start < START_GAP_SECONDS:
                raise VoiceError(429, "Please wait a few seconds before calling again.")
            # Each accepted start may create a billable session, so failures also wait out the gap.
            self.last_start = now
            key = self.read_key()
        answer = self.create_session(key, offer, instructions, evidence, voice)
        return {"sdp": answer, "greeting": self.prompts["VOICE_GREETING"],
                "noBackendCommentary": self.prompts["VOICE_NO_BACKEND_COMMENTARY"]}

    def create_session(self, key, offer, instructions, evidence, voice):
        session = {
            "model": self.prompts["VOICE_MODEL"], "store": False, "instructions": instructions,
            "delegation": {"type": "client"}, "audio": {"output": {"voice": voice}},
            "input": [{"type": "message", "role": "user", "content": [{"type": "input_text", "text": evidence}]}],
        }
        payload = json.dumps({"session": session, "transport": {"type": "webrtc", "sdp": offer}}).encode("utf-8")
        request = urllib.request.Request(LIVE_SESSIONS_URL, data=payload, method="POST",
                                         headers={"Authorization": f"Bearer {key}", "Content-Type": "application/json"})
        # No retry: a retried request can create a second billable session.
        try:
            with OPENER.open(request, timeout=25) as response:
                status, raw = response.status, response.read(MAX_RESPONSE_BYTES + 1)
        except urllib.error.HTTPError as error:
            status = error.code
            try:
                raw = error.read(MAX_RESPONSE_BYTES + 1)
            except OSError:
                raw = b""
            finally:
                error.close()
        except (urllib.error.URLError, OSError, ValueError):
            raise VoiceError(502, "The voice server couldn’t reach OpenAI. Check the internet connection.", "Could not reach OpenAI.") from None
        try:
            result = json.loads(raw) if len(raw) <= MAX_RESPONSE_BYTES else None
        except ValueError:
            result = None
        if status not in (200, 201):
            raise self.provider_error(status, result)
        session_id = (result.get("session") or {}).get("id") if isinstance(result, dict) else None
        answer = (result.get("transport") or {}).get("sdp") if isinstance(result, dict) else None
        if not (isinstance(session_id, str) and session_id and isinstance(answer, str) and answer.startswith("v=0")):
            raise VoiceError(502, "OpenAI returned an unreadable voice connection.", "OpenAI returned no session ID or SDP answer.")
        return answer

    def provider_error(self, status, result):
        error = result.get("error") if isinstance(result, dict) else None
        error = error if isinstance(error, dict) else {}
        # Provider messages can quote request data or a masked key. Keep only bounded identifiers.
        identifiers = [value for value in (error.get("code"), error.get("type"), error.get("param"))
                       if isinstance(value, str) and len(value) <= 96 and SAFE_ID.fullmatch(value)]
        if status == 401:
            with self.lock:
                self.key = None  # Read the secret again on the next call, in case the key was rotated.
        public = {401: "OpenAI rejected the voice key.", 403: "OpenAI denied access to the voice model.",
                  429: "OpenAI voice is at its rate or usage limit. Try again later."}.get(status)
        public = public or ("OpenAI had a problem starting voice. Try again later." if status >= 500 else "OpenAI couldn’t start the voice.")
        detail = f"OpenAI returned HTTP {status}" + (f" ({'; '.join(identifiers)})." if identifiers else ".")
        return VoiceError(502, public, detail)


class Server(ThreadingHTTPServer):
    # Windows SO_REUSEADDR lets a second server share a busy port. Fail clearly instead.
    allow_reuse_address = os.name != "nt"
    daemon_threads = True


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *_arguments):
        # Request bodies, SDP, and chat text must not enter the console.
        pass

    def guard(self, post=False):
        port = self.server.server_port
        host = self.headers.get("Host", "")
        origin = self.headers.get("Origin")
        if host not in {f"127.0.0.1:{port}", f"localhost:{port}"}:
            self.respond(403, {"error": "This server accepts local requests only."})
            return False
        same_origin = origin == f"http://{host}" and self.headers.get("Sec-Fetch-Site", "same-origin") == "same-origin"
        if (origin and origin != f"http://{host}") or (post and not same_origin):
            self.respond(403, {"error": "This server accepts same-origin requests only."})
            return False
        return True

    def send_headers(self, status, content_type, length):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(length))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("X-Frame-Options", "DENY")
        self.send_header("Referrer-Policy", "strict-origin-when-cross-origin")
        self.end_headers()

    def respond(self, status, body, include_body=True):
        payload = json.dumps(body).encode("utf-8")
        self.send_headers(status, "application/json; charset=utf-8", len(payload))
        if include_body:
            self.wfile.write(payload)

    def do_GET(self):
        self.serve_file()

    def do_HEAD(self):
        self.serve_file(include_body=False)

    def serve_file(self, include_body=True):
        if not self.guard():
            return
        try:
            relative = unquote(urlsplit(self.path).path).lstrip("/") or "index.html"
            target = (PUBLIC / relative).resolve()
            visible = PUBLIC in target.parents and not any(part.startswith(".") for part in target.relative_to(PUBLIC).parts)
            found = visible and target.suffix.lower() in TYPES and target.is_file()
        except (OSError, ValueError):
            found = False
        if not found:
            self.respond(404, {"error": "Not found."}, include_body)
            return
        payload = target.read_bytes()
        self.send_headers(200, TYPES[target.suffix.lower()], len(payload))
        if include_body:
            self.wfile.write(payload)

    def do_POST(self):
        if not self.guard(post=True):
            self.close_connection = True
            return
        if urlsplit(self.path).path != VOICE_PATH:
            self.close_connection = True
            self.respond(404, {"error": "Not found."})
            return
        try:
            size = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            size = 0
        if not 0 < size <= MAX_REQUEST_BYTES or self.headers.get("Content-Type", "").split(";")[0].strip() != "application/json":
            self.close_connection = True
            self.respond(413 if size > MAX_REQUEST_BYTES else 400, {"error": "Send a small JSON request."})
            return
        try:
            body = json.loads(self.rfile.read(size))
            if not isinstance(body, dict):
                raise ValueError
            answer = self.server.voice.start(body)
        except VoiceError as error:
            if error.detail:
                print(f"Voice: {error.detail}", flush=True)
            self.respond(error.status, {"error": error.public})
            return
        except (ValueError, UnicodeDecodeError):
            self.respond(400, {"error": "Send a small JSON request."})
            return
        print("Voice: session created.", flush=True)
        self.respond(200, answer)


def main():
    parser = argparse.ArgumentParser(description="ScamCatch local website with live voice")
    parser.add_argument("--port", type=int, default=5180)
    args = parser.parse_args()
    voice = Voice()
    try:
        server = Server(("127.0.0.1", args.port), Handler)
    except OSError:
        print(f"Port {args.port} is busy. Close the other ScamCatch website window, then start this again.", flush=True)
        raise SystemExit(1) from None
    server.voice = voice
    print(f"ScamCatch website: http://localhost:{server.server_port}/  (Ctrl+C or close this window to stop)", flush=True)
    print(voice.describe(), flush=True)
    if not voice.problem:
        threading.Thread(target=voice.preload, daemon=True).start()
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
