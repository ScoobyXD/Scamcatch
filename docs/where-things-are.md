# Where things are

Note for future coding agents, from Claude (Opus 5.5), October 10, 2026.

Website (scamcatch.io)
1. The homepage is static files in website/scamcatch/public: index.html, styles.css, app.js (phone demo, scam picker, section index, the System 1 & System 2 story), vision.js (US map animation), live.js (live voice client), logo.svg, favicon.svg, fonts/. There is no build step.
2. The page rules (section order, scam scenarios, naming and logo limits, voice hooks) are in website/scamcatch/README.txt. Read it before editing the page and update it when the page changes.
3. The page has five snap-scrolling stages in this order: Live demo, System 1 & System 2, The vision, The voice, About us. Each stage is a section.stage with a matching link in nav.index. System 1 & System 2 has an emblem beside its title (a red line into the lighthouse logo, a blue wave out of it), then a six-step timeline in the vision's style: three emotional-thinking (System 1) steps where a scam keeps you, the line where ScamCatch intervenes, and three logical-thinking (System 2) steps.
4. The vision stage is the national pitch: people across a US map on their phones, scams from overseas turning phones red, ScamCatch catching each one on the phone, reports going to a bank, the authorities, and trusted contacts in the middle of the map, then tracing and investigating the scammer's crypto, with a four-step timeline beside the headline. The About us stage is Jonathan's photo, the goal ($0 lost to scams), his belief that scams will be a thing of the past, and a short intro.
5. Start Website.cmd runs website/scamcatch/server.py at http://localhost:5180 for a local preview with the voice endpoint.

Hosting and publishing
6. The site is on AWS: profile mangorts-dev, region us-east-1, stack scamcatch-site, private S3 bucket scamcatch-site-686458136219 behind CloudFront distribution E1FTFPRB048HY.
7. Publishing goes through GitHub (ScoobyXD/Scamcatch). A push to main that changes website/scamcatch/public runs .github/workflows/publish-website.yml, which uploads to S3, clears CloudFront, and checks scamcatch.io. Push only when the user asks to publish. To upload by hand instead, use the aws s3 sync command in website/scamcatch/infra/README.txt.
8. To publish from a cloud session: attach ScoobyXD/Scamcatch with push access, copy the changed files from the user's antiscam folder into the clone, commit to main, and push. The cloud session's GitHub proxy blocks the Actions API, so confirm the publish by fetching scamcatch.io (the workflow fails unless the live index.html matches the push), or ask the user to check the Actions tab. The user's local folder keeps the same files, so their next pull merges cleanly once they have committed their own changes.
9. Hosting, DNS, certificate, and domain rules are in website/scamcatch/infra/README.txt. scamcatch.io is registered at Namecheap with DNS in Route 53. scamcatchtech.com redirects to scamcatch.io.

Live voice
10. On scamcatch.io, /api/* goes through CloudFront to the scamcatch-voice Lambda (website/scamcatch/infra/voice/handler.py). Deploy Voice.cmd deploys it and logs to website/scamcatch/infra/voice-deploy-log.txt.
11. Voice behavior, script selection, keys, and cost limits are in [docs/voice.md](voice.md). Website section explanations remain in website/scamcatch/public/voice-pages.json.

Voice script library
12. [app/src/main/assets/voice-scripts.md](../app/src/main/assets/voice-scripts.md) holds 1 to 100 named scripts. `baseline` contains the user's new text. `uses_scam_language` preserves the previous text.
13. `ACTIVE_VOICE_SCRIPT = baseline` selects Baseline when the desktop copies the file to the phone. There is no automatic A/B assignment.
14. Edit the actual Markdown file. In the desktop **Voice scripts** dialog, select **Reload file**, then **Copy file to phone**.
15. Phone script code is in app/src/main/java/com/antiscam/app/capture/VoiceScripts.kt. The phone saves the library and selected ID across restarts.
16. Stop the run before changing phone scripts. Each run keeps its original script. Desktop `run.json` records its exact instructions, ID, name, and revision.
17. Website demo voice defaults to `baseline`. **Try the original voice**, below the eight scam circles, selects `uses_scam_language` for that call with the same scam evidence.
18. Shared models, greeting, voice settings, and Jev questions remain in [app/ai-instructions/AIInstructions.kt](../app/ai-instructions/AIInstructions.kt).
19. Website startup and voice deployment load both demo scripts from Markdown into `VOICE_SCRIPTS`. Demo requests select through `context.scriptId`. Section explanations still use `context.page`.

Rest of the repository
20. app/ is the Android app (Antiscam). scripts/build.ps1 and scripts/install.ps1 build and install it. The top-level README.md covers setup and runs.
21. dashboard/ is the desktop run console (Start Dashboard.cmd). runs/ holds saved diagnostic metadata and legacy runs. scripts/voice_scripts.py reads the shared script file.
22. application/scamcatch holds application answers, company and domain evidence, and video material.
23. User preferences for agents are in AGENTS.md.


24. [docs/privacy.md](privacy.md) is the canonical source for approved apps, capture gates, keyboard masks, raw-data limits, microphone use, credentials, and California research.
