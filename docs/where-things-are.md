# Where things are

Note for future coding agents, from Claude (Opus 5.5), October 10, 2026.

Website (scamcatch.io)
1. The homepage is static files in website/scamcatch/public: index.html, styles.css, app.js (phone demo, scam picker, section index), vision.js (US map animation), live.js (live voice client), logo.svg, favicon.svg, fonts/. There is no build step.
2. The page rules (section order, scam scenarios, naming and logo limits, voice hooks) are in website/scamcatch/README.txt. Read it before editing the page and update it when the page changes.
3. The page has five snap-scrolling stages in this order: Live demo, The vision, The voice, Where it stands, About us. Each stage is a section.stage with a matching link in nav.index.
4. The vision stage is the national pitch: people across a US map on their phones, scams from overseas turning phones red, ScamCatch catching each one on the phone, reports going to a bank, the authorities, and trusted contacts in the middle of the map, then tracing and investigating the scammer's crypto, with a four-step timeline beside the headline. The About us stage is Jonathan's photo, a short intro, and his statement that scams will be a thing of the past and ScamCatch's goal is $0 lost.
5. Start Website.cmd runs website/scamcatch/server.py at http://localhost:5180 for a local preview with the voice endpoint.

Hosting and publishing
6. The site is on AWS: profile mangorts-dev, region us-east-1, stack scamcatch-site, private S3 bucket scamcatch-site-686458136219 behind CloudFront distribution E1FTFPRB048HY.
7. Publishing goes through GitHub (ScoobyXD/Scamcatch). A push to main that changes website/scamcatch/public runs .github/workflows/publish-website.yml, which uploads to S3, clears CloudFront, and checks scamcatch.io. Push only when the user asks to publish. Publish Website.cmd in the repository root does the same upload by hand from the user's PC.
8. To publish from a cloud session: attach ScoobyXD/Scamcatch with push access, copy the changed files from the user's antiscam folder into the clone, commit to main, and push. The cloud session's GitHub proxy blocks the Actions API, so confirm the publish by fetching scamcatch.io (the workflow fails unless the live index.html matches the push), or ask the user to check the Actions tab. The user's local folder keeps the same files, so their next pull merges cleanly once they have committed their own changes.
9. Hosting, DNS, certificate, and domain rules are in website/scamcatch/infra/README.txt. scamcatch.io is registered at Namecheap with DNS in Route 53. scamcatchtech.com redirects to scamcatch.io.

Live voice
10. On scamcatch.io, /api/* goes through CloudFront to the scamcatch-voice Lambda (website/scamcatch/infra/voice/handler.py). Deploy Voice.cmd deploys it and logs to website/scamcatch/infra/voice-deploy-log.txt.
11. Voice prompts, key handling, and cost limits are in docs/voice.md. All Jev questions and voice prompts come from app/ai-instructions/AIInstructions.kt.

Rest of the repository
12. app/ is the Android app (Antiscam). scripts/build.ps1 and scripts/install.ps1 build and install it. The top-level README.md covers runs, capture rules, and keys.
13. dashboard/ is the desktop run console (Start Dashboard.cmd). runs/ holds saved run records.
14. application/scamcatch holds application answers, company and domain evidence, and video material.
15. User preferences for agents are in AGENTS.md.
