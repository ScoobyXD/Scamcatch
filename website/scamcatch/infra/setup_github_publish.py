"""One-time setup so GitHub Actions can publish scamcatch.io. Python 3.11+, standard library only, plus the AWS CLI.

Run it with "Set Up GitHub Publishing.cmd" in the repository root. It deploys the scamcatch-github-publish stack
(infra/github-publish.json): GitHub's OIDC sign-in, if the account doesn't have it yet, and a role that only
ScoobyXD/Scamcatch main can use, limited to uploading to the site bucket and clearing the CloudFront cache.
No AWS keys are created or stored. Results go to infra/github-publish-setup-log.txt.
"""
from __future__ import annotations

import json
import sys
from datetime import datetime
from pathlib import Path

from deploy_voice import Aws, Failed, deploy_stack, stack_outputs  # same AWS CLI helpers as the voice deploy

HERE = Path(__file__).resolve().parent              # website/scamcatch/infra
DEPLOYMENT = HERE / "deployment.json"
TEMPLATE = HERE / "github-publish.json"
LOG = HERE / "github-publish-setup-log.txt"
STACK = "scamcatch-github-publish"
OIDC_HOST = "token.actions.githubusercontent.com"

lines = []


def log(message=""):
    print(message, flush=True)
    lines.append(f"{datetime.now():%Y-%m-%d %H:%M:%S}  {message}" if message else "")


def main():
    deployment = json.loads(DEPLOYMENT.read_text(encoding="utf-8"))
    aws = Aws(deployment["awsProfile"], deployment.get("awsRegion") or "us-east-1")

    log("1/3 Checking for GitHub sign-in in this AWS account")
    providers = aws.run("iam", "list-open-id-connect-providers") or {}
    existing = next((p["Arn"] for p in providers.get("OpenIDConnectProviderList", []) if p["Arn"].endswith(OIDC_HOST)), "")
    log(f"Found {existing}" if existing else "None yet; the stack will create it.")

    log("2/3 Deploying the scamcatch-github-publish stack")
    deploy_stack(aws, STACK, TEMPLATE, {"ExistingOidcProviderArn": existing}, capabilities=("CAPABILITY_NAMED_IAM",))
    role = stack_outputs(aws, STACK).get("RoleArn", "")
    log(f"Role: {role}")

    log("3/3 Checking the role")
    policy = aws.run("iam", "get-role", "--role-name", "scamcatch-github-publish", "--query", "Role.AssumeRolePolicyDocument")
    subject = json.dumps(policy)
    if "repo:ScoobyXD/Scamcatch:ref:refs/heads/main" not in subject:
        raise Failed("The role exists but doesn't name ScoobyXD/Scamcatch main. Open IAM > Roles > scamcatch-github-publish.")
    log("")
    log("DONE. GitHub can now publish scamcatch.io whenever website/scamcatch/public changes on main.")


if __name__ == "__main__":
    try:
        main()
        code = 0
    except Failed as problem:
        log("")
        log(f"STOPPED: {problem}")
        code = 1
    except Exception as problem:
        log("")
        log(f"STOPPED: unexpected {type(problem).__name__}: {str(problem)[:300]}")
        code = 1
    LOG.write_text("\n".join(lines) + "\n", encoding="utf-8")
    sys.exit(code)
