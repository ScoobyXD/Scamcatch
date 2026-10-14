"""ScamCatch live voice for scamcatch.io: AWS Lambda behind CloudFront /api/*.

Mirrors POST /api/voice/session in website/scamcatch/server.py. Deployed as index.py by infra/deploy_voice.py,
next to prompts.json (the voice constants from AIInstructions.kt). "Click to hear explanation" scripts come from
the site itself (/voice-pages.json), so they go live with every website publish. The OpenAI key stays in AWS Secrets Manager
and Lambda memory; it never reaches the browser, logs, or error text.
"""
from __future__ import annotations

import base64
import datetime
import hmac
import json
import os
import re
import time
import urllib.error
import urllib.request

import boto3
from botocore.exceptions import BotoCoreError, ClientError

LIVE_SESSIONS_URL = "https://api.openai.com/v1/live/sessions"
VOICE_PATH = "/api/voice/session"
MAX_REQUEST_BYTES = 32 * 1024
MAX_RESPONSE_BYTES = 128 * 1024
MAX_EVIDENCE_CHARS = 6000  # Same bound as LiveVoice and server.py.
VOICES = ("alloy", "ash", "ballad", "cedar", "coral", "echo", "fable", "marin", "nova", "onyx", "sage", "shimmer", "verse")
OPENAI_KEY = re.compile(r"sk-[A-Za-z0-9_-]{20,}")
HEADER_SAFE = re.compile(r"[\x21-\x7e]{1,2048}")
SAFE_ID = re.compile(r"[a-z][a-z0-9_]*(?:[.\[][a-z0-9_]+\]?)*")
KEY_UNAVAILABLE = "Live voice can’t read its key right now."
PAGES_PATH = "/voice-pages.json"   # "Click to hear explanation" scripts, published with the site
PAGES_SECONDS = 60                  # how long a warm function reuses the scripts before reading them again
MAX_PAGES_BYTES = 64 * 1024
_pages = {"at": 0.0, "scripts": None}

with open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "prompts.json"), encoding="utf-8") as prompts_file:
    PROMPTS = json.load(prompts_file)

ORIGIN_TOKEN = os.environ["ORIGIN_TOKEN"]          # CloudFront adds it; direct hits on the function URL lack it.
ALLOWED_ORIGINS = {value.strip() for value in os.environ["ALLOWED_ORIGINS"].split(",") if value.strip()}
TABLE = os.environ["USAGE_TABLE"]
DAILY_LIMIT = int(os.environ.get("DAILY_LIMIT", "50"))
START_GAP_SECONDS = int(os.environ.get("START_GAP_SECONDS", "10"))
SECRET_ID = os.environ["SECRET_ID"]
SECRET_REGION = os.environ["SECRET_REGION"]
JSON_FIELD = os.environ.get("SECRET_JSON_FIELD", "").strip()

DYNAMODB = boto3.client("dynamodb")
SECRETS = boto3.client("secretsmanager", region_name=SECRET_REGION)
_key = None


class VoiceError(Exception):
    """public goes to the browser; detail goes to CloudWatch. Neither may contain the key."""

    def __init__(self, status, public, detail=None):
        super().__init__(public)
        self.status, self.public, self.detail = status, public, detail


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *_arguments, **_keywords):
        return None


OPENER = urllib.request.build_opener(NoRedirect)


def respond(status, body):
    return {
        "statusCode": status,
        "headers": {"Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store",
                    "X-Content-Type-Options": "nosniff"},
        "body": json.dumps(body),
    }


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


def read_key():
    global _key
    if _key:
        return _key
    try:
        text = SECRETS.get_secret_value(SecretId=SECRET_ID).get("SecretString") or ""
    except (ClientError, BotoCoreError) as error:
        code = error.response.get("Error", {}).get("Code", "error") if isinstance(error, ClientError) else type(error).__name__
        raise VoiceError(503, KEY_UNAVAILABLE, f"Secrets Manager could not read the voice key ({code}).") from None
    key = secret_key(text.strip(), JSON_FIELD)
    if not key:
        raise VoiceError(503, KEY_UNAVAILABLE, "The voice secret has no OpenAI key.")
    _key = key
    return key


def evidence_text(context):
    """Frame the demo chat like the app's screen evidence: prefix, flagged signal, captured text, 6,000-character bound."""
    conversation = context.get("conversation") if isinstance(context, dict) else None
    flagged = context.get("flaggedMessage") if isinstance(context, dict) else None
    if not (isinstance(conversation, list) and 0 < len(conversation) <= 60 and isinstance(flagged, str) and flagged.strip()
            and all(isinstance(item, dict) and isinstance(item.get("name"), str) and isinstance(item.get("text"), str) for item in conversation)):
        raise VoiceError(400, "Voice needs the demo conversation to start.")
    captured = "\n".join(f"{item['name'].strip()}: {item['text'].strip()}" for item in conversation)
    evidence = json.dumps({"flaggedMessage": flagged.strip(), "capturedText": captured}, ensure_ascii=False, separators=(",", ":"))
    if len(evidence) > MAX_EVIDENCE_CHARS:
        evidence = evidence[:MAX_EVIDENCE_CHARS] + PROMPTS["VOICE_TRUNCATED_EVIDENCE_SUFFIX"]
    return PROMPTS["VOICE_EVIDENCE_PREFIX"] + evidence


def page_scripts(data):
    """voice-pages.json as (instructions, {page id: notes}), each list of lines joined; None when it isn't in that shape."""
    def text(value):
        return "\n".join(value) if isinstance(value, list) and value and all(isinstance(line, str) for line in value) else None
    pages = data.get("pages") if isinstance(data, dict) else None
    instructions = text(data.get("instructions")) if isinstance(data, dict) else None
    notes = {page: text(value) for page, value in pages.items()} if isinstance(pages, dict) else {}
    return (instructions, notes) if instructions and notes and all(notes.values()) else None


def load_pages(site):
    """The site's voice-pages.json, reused for PAGES_SECONDS. A failed read keeps the last good copy."""
    now = time.time()
    if _pages["scripts"] and now - _pages["at"] < PAGES_SECONDS:
        return _pages["scripts"]
    try:
        request = urllib.request.Request(site + PAGES_PATH, headers={"User-Agent": "ScamCatch voice"})
        with OPENER.open(request, timeout=5) as response:
            raw = response.read(MAX_PAGES_BYTES + 1)
        scripts = page_scripts(json.loads(raw)) if len(raw) <= MAX_PAGES_BYTES else None
    except (urllib.error.URLError, OSError, ValueError):
        scripts = None
    if scripts:
        _pages.update(at=now, scripts=scripts)
    elif not _pages["scripts"]:
        raise VoiceError(503, "Voice can’t load this page’s explanation right now.", f"Could not read {PAGES_PATH} from {site}.")
    return _pages["scripts"]


def session_input(context, site):
    """Instructions and first input: a page explanation when context names a page, otherwise the demo chat as evidence."""
    page = context.get("page") if isinstance(context, dict) else None
    if page is None:
        return PROMPTS["VOICE_INSTRUCTIONS"], evidence_text(context)
    if not isinstance(page, str):
        raise VoiceError(400, "Voice can’t explain that page.")
    instructions, notes = load_pages(site)
    if page not in notes:
        raise VoiceError(400, "Voice can’t explain that page.")
    return instructions, notes[page]


def reserve_call():
    """Count the call before creating it: at most DAILY_LIMIT calls per UTC day, one new call every START_GAP_SECONDS."""
    now = int(time.time())
    day = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%d")
    try:
        DYNAMODB.update_item(
            TableName=TABLE, Key={"day": {"S": day}},
            UpdateExpression="SET #calls = if_not_exists(#calls, :zero) + :one, #last = :now, #expires = :expires",
            ConditionExpression="(attribute_not_exists(#calls) OR #calls < :max) AND (attribute_not_exists(#last) OR #last <= :cutoff)",
            ExpressionAttributeNames={"#calls": "calls", "#last": "last_start", "#expires": "expires_at"},
            ExpressionAttributeValues={":zero": {"N": "0"}, ":one": {"N": "1"}, ":now": {"N": str(now)},
                                       ":expires": {"N": str(now + 7 * 86400)}, ":max": {"N": str(DAILY_LIMIT)},
                                       ":cutoff": {"N": str(now - START_GAP_SECONDS)}})
    except ClientError as error:
        code = error.response.get("Error", {}).get("Code", "error")
        if code != "ConditionalCheckFailedException":
            raise VoiceError(503, "Live voice isn’t available right now.", f"Usage table error ({code}).") from None
        try:
            item = DYNAMODB.get_item(TableName=TABLE, Key={"day": {"S": day}}, ConsistentRead=True).get("Item") or {}
            calls = int(item.get("calls", {}).get("N", "0"))
        except (ClientError, BotoCoreError, ValueError):
            calls = 0
        if calls >= DAILY_LIMIT:
            raise VoiceError(429, "Today’s live demo calls are used up. Please try again tomorrow.",
                             f"Daily limit of {DAILY_LIMIT} calls reached.") from None
        raise VoiceError(429, "Please wait a few seconds before calling again.") from None
    except BotoCoreError as error:
        raise VoiceError(503, "Live voice isn’t available right now.", f"Usage table error ({type(error).__name__}).") from None


def provider_error(status, result):
    global _key
    error = result.get("error") if isinstance(result, dict) else None
    error = error if isinstance(error, dict) else {}
    # Provider messages can quote request data or a masked key. Keep only bounded identifiers.
    identifiers = [value for value in (error.get("code"), error.get("type"), error.get("param"))
                   if isinstance(value, str) and len(value) <= 96 and SAFE_ID.fullmatch(value)]
    if status == 401:
        _key = None  # Read the secret again on the next call, in case the key was rotated.
    public = {401: "OpenAI rejected the voice key.", 403: "OpenAI denied access to the voice model.",
              429: "OpenAI voice is at its rate or usage limit. Try again later."}.get(status)
    public = public or ("OpenAI had a problem starting voice. Try again later." if status >= 500 else "OpenAI couldn’t start the voice.")
    detail = f"OpenAI returned HTTP {status}" + (f" ({'; '.join(identifiers)})." if identifiers else ".")
    return VoiceError(502, public, detail)


def create_session(key, offer, instructions, evidence, voice):
    session = {
        "model": PROMPTS["VOICE_MODEL"], "store": False, "instructions": instructions,
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
        raise VoiceError(502, "The voice server couldn’t reach OpenAI.", "Could not reach OpenAI.") from None
    try:
        result = json.loads(raw) if len(raw) <= MAX_RESPONSE_BYTES else None
    except ValueError:
        result = None
    if status not in (200, 201):
        raise provider_error(status, result)
    session_id = (result.get("session") or {}).get("id") if isinstance(result, dict) else None
    answer = (result.get("transport") or {}).get("sdp") if isinstance(result, dict) else None
    if not (isinstance(session_id, str) and session_id and isinstance(answer, str) and answer.startswith("v=0")):
        raise VoiceError(502, "OpenAI returned an unreadable voice connection.", "OpenAI returned no session ID or SDP answer.")
    return answer


def start(body, site):
    offer = body.get("sdp")
    if not (isinstance(offer, str) and offer.startswith("v=0") and len(offer) <= 20000):
        raise VoiceError(400, "Voice needs a WebRTC offer to start.")
    voice = body.get("voice", PROMPTS["VOICE_NAME"])
    if voice not in VOICES:
        raise VoiceError(400, "That voice name isn't one of OpenAI's built-in voices.")
    instructions, evidence = session_input(body.get("context"), site)
    # Each accepted start may create a billable session, so it is counted before OpenAI is called, and failures count too.
    reserve_call()
    key = read_key()
    answer = create_session(key, offer, instructions, evidence, voice)
    return {"sdp": answer, "greeting": PROMPTS["VOICE_GREETING"], "noBackendCommentary": PROMPTS["VOICE_NO_BACKEND_COMMENTARY"]}


def self_check():
    """Run only by direct invoke from infra/deploy_voice.py: confirms the key and the usage table without starting a call."""
    try:
        read_key()
        key = "ok"
    except VoiceError as error:
        key = error.detail or error.public
    try:
        day = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%d")
        item = DYNAMODB.get_item(TableName=TABLE, Key={"day": {"S": day}}).get("Item") or {}
        usage = int(item.get("calls", {}).get("N", "0"))
    except (ClientError, BotoCoreError, ValueError) as error:
        usage = f"usage table error ({type(error).__name__})"
    return {"key": key, "callsToday": usage, "dailyLimit": DAILY_LIMIT}


def handler(event, _context):
    # Function URL events always carry requestContext, so visitors can't reach the self-check.
    if event.get("scamcatchCheck") is True and "requestContext" not in event:
        return self_check()
    headers = {str(name).lower(): value for name, value in (event.get("headers") or {}).items()}
    if not hmac.compare_digest(headers.get("x-scamcatch-origin", ""), ORIGIN_TOKEN):
        return respond(403, {"error": "Not found."})
    method = ((event.get("requestContext") or {}).get("http") or {}).get("method", "")
    if event.get("rawPath") != VOICE_PATH:
        return respond(404, {"error": "Not found."})
    if method != "POST":
        return respond(405, {"error": "Use POST."})
    if headers.get("origin") not in ALLOWED_ORIGINS or headers.get("sec-fetch-site", "same-origin") != "same-origin":
        return respond(403, {"error": "This endpoint accepts same-origin requests only."})
    if headers.get("content-type", "").split(";")[0].strip() != "application/json":
        return respond(400, {"error": "Send a small JSON request."})
    raw = event.get("body") or ""
    try:
        data = base64.b64decode(raw) if event.get("isBase64Encoded") else raw.encode("utf-8")
    except (ValueError, TypeError):
        return respond(400, {"error": "Send a small JSON request."})
    if not 0 < len(data) <= MAX_REQUEST_BYTES:
        return respond(413 if len(data) > MAX_REQUEST_BYTES else 400, {"error": "Send a small JSON request."})
    try:
        body = json.loads(data)
        if not isinstance(body, dict):
            raise ValueError
        answer = start(body, headers["origin"])   # the visitor's site, already checked against ALLOWED_ORIGINS
    except VoiceError as error:
        if error.detail:
            print(f"Voice: {error.detail}", flush=True)
        return respond(error.status, {"error": error.public})
    except (ValueError, UnicodeDecodeError):
        return respond(400, {"error": "Send a small JSON request."})
    print("Voice: session created.", flush=True)
    return respond(200, answer)
