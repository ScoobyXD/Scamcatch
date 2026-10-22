# Voice warning rules

1. All privacy rules are in [docs/privacy.md](privacy.md), including microphone use, capture suspension, cloud transfer, records, and credentials.

## Edit questions and prompts

1. [app/src/main/assets/voice-scripts.md](../app/src/main/assets/voice-scripts.md) is the single source for phone voice scripts and website demo scripts.
2. [app/ai-instructions/AIInstructions.kt](../app/ai-instructions/AIInstructions.kt) defines Jev questions, both models, the voice name, greeting, evidence framing, and no-backend commentary.
3. The debug app compiles the Kotlin settings directly. Stop the run, rebuild, and install after editing those settings.
4. The website's section explanations remain in [website/scamcatch/public/voice-pages.json](../website/scamcatch/public/voice-pages.json). These are separate from the phone script library.
5. Ask the user to audit any new Jev question before adding it.

## Script library rules

1. Keep 1 to 100 scripts in the Markdown file. The library contains `baseline` (**Baseline**) and `uses_scam_language` (**Uses scam language**).
2. Each script starts with `## script_id | Display name`. Give each script a unique ID and nonempty instructions.
3. Set `ACTIVE_VOICE_SCRIPT` once before the script sections. It currently selects `baseline` when the file is copied to the phone.
4. Stop the current run before importing a library or changing the phone selection. Each run keeps its original script.
5. Edit the actual Markdown file in your editor. The desktop **Voice scripts** dialog provides a read-only preview.
6. **Reload file** refreshes the desktop preview. **Copy file to phone** saves the library and selects the file's active ID.
7. Desktop **Use on phone** selects the displayed script only when its ID and revision match the phone copy.
8. Phone **Choose script** and **Save selection** change the saved selection without editing the source file.
9. Wait for phone save confirmation before disconnecting. The library and selection persist across app restarts and phone reboots.
10. A saved phone library takes priority over the bundled file after an app update. Copy the edited file explicitly to update it.
11. Desktop `run.json` saves `voiceScript` with the exact instructions, ID, name, and revision used for that run.
12. Revisions are SHA-256 hashes of the script instructions. Selection is manual. There is no automatic rotation or A/B assignment.
13. Keep `baseline` and `uses_scam_language` in the file for the website. Website script selection is independent of the phone selection and `ACTIVE_VOICE_SCRIPT`.
14. Scripts contain configured instructions only. Apply [privacy rules](privacy.md) to all content and credentials.
15. Baseline contains the user's completed new text. Uses scam language preserves the previous text. Both are runnable script sections.

## Behavior and connection rules

1. A completed flagged Jev check from the active run opens one warning. Keep one warning and one voice session active.
2. Old Jev results must not reopen a dismissed warning.
3. Show a centered card above the current app with **We believe you are being scammed**.
4. Animate the entrance with a 420-millisecond fade and scale. Request two 65-millisecond vibration pulses with a 120-millisecond gap, even if the warning cannot open.
5. Use the website's navy colors, lighthouse logo, and blue voice orb with seven white bars. Animate the orb from remote WebRTC audio levels, sampled every 100 milliseconds. The orb is not tappable.
6. Enter the OpenAI key in Antiscam setup or the desktop console. Desktop delivery uses the local server and ADB bridge.
7. Key readiness confirms that a key is loaded. It does not confirm authentication, billing, or model access.
8. Start native voice automatically when an eligible warning opens. Require no tap. Permit one connection attempt per warning under [privacy rules](privacy.md#microphone-and-voice).
9. The selected script defines the opening and conversation. **Baseline** is the default. **Uses scam language** preserves the previous wording.
10. Never claim that the app blocked a payment. Evidence handling follows [privacy rules](privacy.md#content-cloud-transfer-and-records).
11. Read the pinned model and voice name from [AIInstructions.kt](../app/ai-instructions/AIInstructions.kt). Use client delegation without a paid backend model or tool calls.
12. Use native WebRTC media tracks for microphone input and generated speech. WebRTC handles audio buffering, decoding, and playback timing.
13. Pin the debug dependency to `io.github.webrtc-sdk:android:150.7871.01`. Do not restore the custom WebSocket audio transport or PCM queue.
14. Create the `oai-events` data channel before creating the SDP offer. Wait for ICE gathering to finish before submitting the offer.
15. Create the session with `POST https://api.openai.com/v1/live/sessions`, using the session configuration and WebRTC SDP offer. Apply the returned SDP answer.
16. Wait for `session.started` before sending application commands. Do not send `session.start`, microphone chunks, or generated audio through the data channel.
17. Wait up to 35 seconds for `session.started` and 10 seconds for ICE gathering. End voice if the peer remains disconnected for five seconds.
18. Do not automatically retry session creation. A retry can create another billable session.
19. The large red X and Android Back close the warning under the [voice shutdown rules](privacy.md#microphone-and-voice).
20. For a started session, send `session.close` and wait up to five seconds for `session.closed`. Record unconfirmed finalization if no confirmation arrives.
21. Missing setup and voice failures keep the warning open and capture paused. Keep a failure visible until the warning closes.
22. Start a five-second warning cooldown after dismissal finishes. Resume capture immediately when allowed. Suppress new warnings during the cooldown without queuing them.
23. Retain `voice_audio_summary` for the original run when cleanup finishes after Stop. Remote audio energy does not prove that the user heard sound.
24. Active voice costs $0.05 per minute, billed per second. WebRTC initialization bills 15 seconds, credited against the session duration. See [OpenAI's WebRTC guide](https://developers.openai.com/api/docs/guides/voice-webrtc#understand-the-connection-sequence).
25. Builds and lint do not verify live voice. Check speech, interruption, closing, backgrounding, and capture resumption with the laptop disconnected.
26. Update this document when voice behavior changes. Keep each rule consistent with `LiveVoice`, `ScamIntervention`, and `ScamWarningActivity`.
27. Start Run on the phone uses the same capture, OCR, Jev, warning, and voice flow. The phone needs Internet access.
28. Leaving Antiscam setup does not stop a phone run. The accessibility service continues screen checks while the user uses other apps.
29. If Android blocks the warning, keep capture paused. Open the notification or select Return to scam warning in Antiscam.
30. Backgrounding keeps the warning open. Returning must not start another voice session.
31. Start another run manually after an app restart or phone reboot.

## Website script selection

1. The default talk action uses `baseline`. A missing `context.scriptId` also selects `baseline`.
2. **Try the original voice**, below the eight scam circles, sends `context.scriptId = uses_scam_language` for that call.
3. Both actions use the selected scam's evidence. The original voice button does not change the default script for later calls.
4. Reject unknown or non-string script IDs before a provider request. Accept only `baseline` and `uses_scam_language` for demo calls.
5. Section explanations still use `context.page` and voice-pages.json. Demo script selection does not change that path.

## Website voice rules

1. `Start Website.cmd` runs [website/scamcatch/server.py](../website/scamcatch/server.py). It serves the site at http://localhost:5180 and creates voice sessions at `POST /api/voice/session`.
2. At startup, the server loads both demo scripts from voice-scripts.md into `VOICE_SCRIPTS`, plus shared settings from AIInstructions.kt. Restart it after edits.
3. Website secret configuration uses `voiceKeySecret`, `awsProfile`, and optional `jsonField` in [deployment.json](../website/scamcatch/infra/deployment.json). See [credential rules](privacy.md#credentials-and-local-access).
4. The browser sends its SDP offer and `ScamCatchDemo.context()`. The server frames the chat as screen evidence like the app, bounded to 6,000 characters.
5. The server returns the SDP answer, greeting, and no-backend commentary. Error and diagnostic contents follow [privacy rules](privacy.md).
6. The server accepts local same-origin requests up to 32 KB and starts at most one session every 10 seconds. It never retries session creation.
7. [live.js](../website/scamcatch/public/live.js) follows the LiveVoice connection sequence, events, timeouts, greeting, and delegation commentary.
8. Captions show transient `session.output_transcript.delta` text under [privacy rules](privacy.md#microphone-and-voice). A new caption starts after the user speaks or the voice pauses.
9. The voice calls itself ScamCatch on the website and in the app.
10. On scamcatch.io the scamcatch-voice Lambda (website/scamcatch/infra/voice/handler.py) is the backend: CloudFront sends /api/* to its function URL with a shared X-ScamCatch-Origin token. It reads the same secret, allows 50 calls per UTC day and one new call every 10 seconds, and is deployed by `Deploy Voice.cmd`.
11. Add `?voice=<name>` to the page address to try another built-in OpenAI voice. The server accepts only built-in voice names. `VOICE_NAME` stays the default.
12. The call screen shows whose turn it is. Once the voice has talked and its audio has been quiet for 1.2 seconds, it switches to the visitor's turn: the same light blue orb with a microphone that grows with the visitor's voice, and a pulsing light blue "Your turn. Talk back!" card in place of the caption. It switches back when the voice talks again. Without audio levels, a pause in the transcript decides.
13. Keep `VOICE_GREETING` short. The session reads the whole greeting before the voice starts, so every extra word delays the first words. The opening itself is in the selected demo script. Measured on scamcatch.io on October 11, 2026: about 1.9 seconds from `session.started` to the first words with the old 70-word greeting, about 1.45 seconds with a two-word one.
14. A blocked, missing, or busy microphone does not stop the call. live.js sends silence instead, and the visitor hears the voice's opening. The status says the mic is off, the visitor's turn never shows, and the call ends once the voice has been quiet for 3 seconds, showing why the microphone couldn't open.
15. live.js starts its audio element with silence during the tap, so Safari plays the voice even when no microphone is capturing.
16. "Click to hear explanation" sends `context` as `{"page": "<section id>"}`. The server and the Lambda then use `instructions` and that page's notes from voice-pages.json instead of the demo chat. An unknown page is refused before a session starts.
17. Page explanations reuse `VOICE_GREETING`, the voice name, the 3-minute cap, and the daily and 10-second limits. The voice skips any greeting or introduction, gives the page's opening from its page notes slowly and calmly, pausing after each point, then asks for questions.
18. Page notes go in as the session's first input, like the demo's evidence.
19. The Lambda reads voice-pages.json from the visitor's site (scamcatch.io/voice-pages.json) and reuses it for 60 seconds. If the site can't be read, it keeps its last good copy; with no copy, the page call fails before a session starts.
20. The local server reads public/voice-pages.json on every page call, so edits need no restart.
21. Editing voice-pages.json needs no Deploy Voice.cmd: a push to main publishes it with the site. Website script, AIInstructions.kt, or handler.py changes require Deploy Voice.cmd for the live voice backend.
22. System 1 & System 2 opening: no definitions up front; scammers keep you in System 1 (the red side) with love, hope, fear and urgency; ScamCatch (the lighthouse in the middle) steps in to slow the pace; then it brings in System 2 (the blue side).
23. Vision opening: starts with "Our vision is to", then the page's four steps, one by one: a scam comes in; ScamCatch intervenes; with approval or pre-approval, it contacts the bank, the authorities, and trusted contacts; if crypto was involved, a crypto trace. It doesn't say "This is our vision" or bring up zero dollars lost.
