"""Deploy ScamCatch live voice to scamcatch.io. Python 3.11+, standard library only, plus the AWS CLI.

Run it with "Deploy Voice.cmd" in the repository root. It:
1. builds the Lambda package from infra/voice/handler.py, shared settings in AIInstructions.kt, and baseline in voice-scripts.md,
2. deploys the scamcatch-voice stack (Lambda function URL, daily call counter, permissions),
3. uploads the function code,
4. adds the /api/* route to the scamcatch-site CloudFront distribution,
5. checks https://scamcatch.io/api/voice/session without starting a billable call.
The OpenAI key stays in Secrets Manager. The route token is never printed or logged.
"""
from __future__ import annotations

import hashlib
import io
import json
import os
import secrets
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import zipfile
from datetime import datetime
from pathlib import Path

HERE = Path(__file__).resolve().parent              # website/scamcatch/infra
SITE_DIR = HERE.parent                              # website/scamcatch
DEPLOYMENT = HERE / "deployment.json"
HANDLER = HERE / "voice" / "handler.py"
VOICE_TEMPLATE = HERE / "voice.json"
SITE_TEMPLATE = HERE / "site.json"
LOG = HERE / "voice-deploy-log.txt"
VOICE_STACK = "scamcatch-voice"
FUNCTION_NAME = "scamcatch-voice"
DAILY_LIMIT = 50
# site.json as last deployed before the voice route (normalized JSON hash). The site step stops if the live
# stack template is neither this nor the current site.json, so it never overwrites changes made elsewhere.
BASE_SITE_SHA = "1cd25fbbd7a782cb9bd32d97aa6aad463ba8702dbcf057691fa31db8acba38ca"

lines = []


def log(message=""):
    print(message, flush=True)
    lines.append(f"{datetime.now():%Y-%m-%d %H:%M:%S}  {message}" if message else "")


def save_log():
    LOG.write_text("\n".join(lines) + "\n", encoding="utf-8")


class Failed(Exception):
    pass


def template_sha(template):
    return hashlib.sha256(json.dumps(template, sort_keys=True, separators=(",", ":")).encode("utf-8")).hexdigest()


class Aws:
    def __init__(self, profile, region):
        self.exe = shutil.which("aws")
        if not self.exe:
            raise Failed("The AWS CLI isn't installed or isn't on PATH.")
        self.profile, self.region = profile, region

    def run(self, *args, capture=True, check=True):
        command = [self.exe, *args, "--profile", self.profile, "--region", self.region]
        if capture:
            command += ["--output", "json"]
        result = subprocess.run(command, capture_output=capture, encoding="utf-8", errors="replace",
                                env={**os.environ, "AWS_PAGER": ""})
        if check and result.returncode:
            reason = ""
            if capture:
                reason = next((line.strip() for line in reversed(result.stderr.splitlines()) if line.strip()), "")
            raise Failed(f"aws {' '.join(args[:2])} failed. {reason[:400]}")
        if not capture:
            return result.returncode
        return json.loads(result.stdout) if result.stdout.strip() else None


def build_package():
    sys.path.insert(0, str(SITE_DIR))
    import server  # load_prompts reads shared settings and the Markdown baseline.

    prompts = server.load_prompts()
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("index.py", HANDLER.read_text(encoding="utf-8"))
        archive.writestr("prompts.json", json.dumps(prompts, ensure_ascii=False))
    path = Path(tempfile.gettempdir()) / "scamcatch-voice-package.zip"
    path.write_bytes(buffer.getvalue())
    log(f"Built the voice package ({len(buffer.getvalue()) // 1024} KB) with model {prompts['VOICE_MODEL']}, voice {prompts['VOICE_NAME']}.")
    return path


def origin_token(aws):
    try:
        found = aws.run("lambda", "get-function-configuration", "--function-name", FUNCTION_NAME,
                        "--query", "Environment.Variables.ORIGIN_TOKEN")
    except Failed:
        found = None
    if isinstance(found, str) and len(found) >= 32:
        log("Reusing the existing CloudFront route token.")
        return found
    log("Created a new CloudFront route token.")
    return secrets.token_urlsafe(32)


def stack_outputs(aws, stack):
    outputs = aws.run("cloudformation", "describe-stacks", "--stack-name", stack, "--query", "Stacks[0].Outputs") or []
    return {item["OutputKey"]: item["OutputValue"] for item in outputs}


def deploy_stack(aws, stack, template, parameters, capabilities=()):
    args = ["cloudformation", "deploy", "--stack-name", stack, "--template-file", str(template),
            "--no-fail-on-empty-changeset", "--parameter-overrides", *[f"{key}={value}" for key, value in parameters.items()]]
    if capabilities:
        args += ["--capabilities", *capabilities]
    if aws.run(*args, capture=False, check=False):
        raise Failed(f"Stack {stack} did not update. Open CloudFormation > {stack} > Events for the reason.")


def check_site_template(aws, stack):
    body = aws.run("cloudformation", "get-template", "--stack-name", stack, "--template-stage", "Original",
                   "--query", "TemplateBody")
    if isinstance(body, str):
        body = json.loads(body)
    live = template_sha(body)
    local = template_sha(json.loads(SITE_TEMPLATE.read_text(encoding="utf-8")))
    if live not in (BASE_SITE_SHA, local):
        raise Failed(f"The live {stack} template differs from site.json, so the /api route was not added. "
                     "Nothing on the website was changed.")


def post(url, body, origin):
    data = json.dumps(body).encode("utf-8")
    request = urllib.request.Request(url, data=data, method="POST",
                                     headers={"Content-Type": "application/json", "Origin": origin,
                                              "Sec-Fetch-Site": "same-origin", "User-Agent": "ScamCatch deploy check"})
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return response.status, response.read(4096).decode("utf-8", "replace")
    except urllib.error.HTTPError as error:
        return error.code, error.read(4096).decode("utf-8", "replace")
    except (urllib.error.URLError, OSError) as error:
        return 0, str(error)


def smoke_test(domain):
    url = f"https://{domain}/api/voice/session"
    log(f"Checking {url} (no call is started; this request has no WebRTC offer).")
    for attempt in range(1, 21):
        status, text = post(url, {"sdp": "check"}, f"https://{domain}")
        if status == 400 and "WebRTC offer" in text:
            log(f"Voice endpoint answers through CloudFront: HTTP {status}.")
            blocked, _ = post(url, {"sdp": "check"}, "https://example.com")
            log(f"Requests from other websites are refused: HTTP {blocked}." if blocked == 403
                else f"WARNING: a request from another website got HTTP {blocked}, expected 403.")
            return True
        log(f"  attempt {attempt}: HTTP {status}. Waiting for CloudFront to pick up the new route...")
        time.sleep(30)
    return False


def main():
    deployment = json.loads(DEPLOYMENT.read_text(encoding="utf-8"))
    profile = deployment["awsProfile"]
    region = deployment.get("awsRegion") or "us-east-1"
    secret = deployment.get("voiceKeySecret") or {}
    site_stack = deployment.get("stackName") or "scamcatch-site"
    domain = deployment.get("domain") or "scamcatch.io"
    if not secret.get("secretId") or not secret.get("region"):
        raise Failed("Fill in voiceKeySecret.secretId and voiceKeySecret.region in infra/deployment.json first.")
    aws = Aws(profile, region)

    log("1/5 Building the voice package")
    package = build_package()

    log("2/5 Deploying the voice stack (function, daily call counter, permissions)")
    token = origin_token(aws)
    deploy_stack(aws, VOICE_STACK, VOICE_TEMPLATE, {
        "OriginToken": token, "SecretArn": secret["secretId"], "SecretRegion": secret["region"],
        "SecretJsonField": secret.get("jsonField") or "", "DailyLimit": DAILY_LIMIT,
        "AllowedOrigins": f"https://{domain},https://www.{domain}",
    }, capabilities=("CAPABILITY_IAM",))
    outputs = stack_outputs(aws, VOICE_STACK)
    function_url = outputs["FunctionUrl"]
    origin_domain = function_url.split("://", 1)[-1].strip("/")
    log(f"Voice function URL host: {origin_domain}")

    log("3/5 Uploading the function code")
    aws.run("lambda", "update-function-code", "--function-name", FUNCTION_NAME, "--zip-file", f"fileb://{package}")
    aws.run("lambda", "wait", "function-updated", "--function-name", FUNCTION_NAME)
    package.unlink(missing_ok=True)
    log("Function code is live.")
    result_path = Path(tempfile.gettempdir()) / "scamcatch-voice-check.json"
    aws.run("lambda", "invoke", "--function-name", FUNCTION_NAME, "--cli-binary-format", "raw-in-base64-out",
            "--payload", json.dumps({"scamcatchCheck": True}), str(result_path))
    check = json.loads(result_path.read_text(encoding="utf-8"))
    result_path.unlink(missing_ok=True)
    if check.get("key") != "ok":
        raise Failed(f"The voice function can't read the OpenAI key: {check.get('key')}")
    log(f"The function reads the OpenAI key. Calls today: {check.get('callsToday')} of {check.get('dailyLimit')}.")

    log(f"4/5 Adding the /api route to {site_stack} (CloudFront updates can take several minutes)")
    check_site_template(aws, site_stack)
    current = aws.run("cloudformation", "describe-stacks", "--stack-name", site_stack, "--query", "Stacks[0].Parameters") or []
    keep = {item["ParameterKey"]: item["ParameterValue"] for item in current
            if item["ParameterKey"] in ("CertificateArn", "DomainName", "RedirectDomainName")}
    log("Keeping site settings: " + ", ".join(f"{key}={value}" for key, value in keep.items()))
    deploy_stack(aws, site_stack, SITE_TEMPLATE, {**keep, "VoiceOriginDomain": origin_domain, "VoiceOriginToken": token})
    log("Site stack updated.")

    log("5/5 Checking the live endpoint")
    if not smoke_test(domain):
        raise Failed("The voice endpoint did not answer through scamcatch.io yet. Run this again in a few minutes.")
    log("")
    log(f"DONE. Live voice is on at https://{domain} with up to {DAILY_LIMIT} calls a day.")


if __name__ == "__main__":
    try:
        main()
        code = 0
    except Failed as problem:
        log("")
        log(f"STOPPED: {problem}")
        code = 1
    except Exception as problem:  # report anything unexpected without a traceback that could echo parameters
        log("")
        log(f"STOPPED: unexpected {type(problem).__name__}: {str(problem)[:300]}")
        code = 1
    save_log()
    sys.exit(code)
