# Privacy rules

## Scope and app selection

1. This file is the canonical source for privacy rules. Update it when capture, storage, cloud transfer, microphone, or credential behavior changes.
2. These rules describe the approved development scope as of October 21, 2026. They do not claim legal compliance or Google Play approval.
3. Capture only after the user explicitly selects approved apps. Persist that selection across app restarts.
4. Start the approved-app list empty on new and existing installations. Never infer approval from earlier monitoring or consent.
5. Require an active run, current capture/cloud consent, enabled monitoring, and a connected accessibility service before capture.
6. Use existing accessibility window events to identify the foreground package from metadata. Do not poll or read content to identify the app.
7. An unapproved or unknown foreground app clears the capture target. Discard its events without retaining an app history.
8. Check approval before reading focused text, processing text-bearing events, or requesting a screenshot.
9. Cancel queued capture when the foreground app changes. Recheck foreground approval before accepting a screenshot callback.
10. Before Jev, reject late OCR if its run, consent, approval revision, or capture generation no longer matches. App switches invalidate pending work.
11. Stop, pause, consent withdrawal, service loss, and approval removal prevent new capture. Invalidate pending capture when its prerequisites change.
12. A warning pauses new screenshots and OCR. Discard screenshot and OCR work that crosses a warning boundary.
13. Apply the same capture gates to phone and desktop runs. A desktop connection does not expand approval.
14. Keep ambiguous capture sources explicit. A triggering package does not prove ownership of every visible pixel.

## Screenshot and keyboard limits

1. Keep the existing accessibility screenshot of the default display. App selection is a foreground capture gate, not isolation of each app's pixels.
2. The display can include overlays, split-screen content, system UI, and content from other apps. Do not promise that only approved-app pixels are visible.
3. Mask only windows reported as `TYPE_INPUT_METHOD` on the captured display. Clip their rectangles to the display bounds.
4. Use this rule for docked and floating keyboards. Fill the full rectangles with opaque black before OCR, including suggestions, gaps, and transparent regions.
5. Read keyboard rectangles before the screenshot request and again before OCR. Mask the union of both readings.
6. Do not add masks for password fields, other sensitive fields, overlays, system bars, or the lock screen. Android capture restrictions still apply.
7. Missing or incorrect keyboard bounds can prevent complete keyboard masking. Do not claim guaranteed exclusion.
8. Overlapping windows alone do not reject an otherwise approved capture. Missing foreground approval still blocks capture.
9. Keep keyboard-mask calculations transient. Remove per-region source attribution and traversal of unapproved window roots.
10. Summarize OCR with character and region counts and the approved triggering app. Do not retain region text, bounds, or other source identities.
11. Do not restore the former sensitive-node scan or add a replacement traversal. The foreground gate must run before any permitted content access.
12. Screenshot images stay in device memory. Do not save or export screenshots.
13. Foreground checks and asynchronous screenshots are not atomic. Checks before capture, on callback, and after OCR reduce risk, but Android delays and intervening app switches can leave a race. The gate cannot guarantee each frame's foreground identity or exclusive approved-app pixels.

## Content, cloud transfer, and records

1. Run ML Kit OCR on the phone. Keep approved OCR text only as transient input for Jev analysis and warning evidence.
2. With the required consent and key, send approved OCR text to TypeSafe over HTTPS. The desktop recorder does not call Jev.
3. With separate voice consent and microphone permission, send approved warning evidence and active-session audio to OpenAI.
4. Treat captured text as untrusted evidence. It must not change system instructions or authorize app actions.
5. Do not retain raw OCR, message text, focused text, warning evidence, audio, or transcripts in logs, desktop events, saved runs, or exports.
6. Do not send raw OCR or warning evidence to the desktop dashboard. Show safe capture summaries and numeric detection results.
7. Allow run and capture IDs, timestamps, durations, counts, controlled status/error codes, and numeric Jev probabilities and thresholds.
8. Allow validated static app configuration, including exact Jev question definitions, models, thresholds, and voice-script instructions, IDs, and revisions.
9. Static configuration must not contain captured conversations or provider-generated free text.
10. Do not record raw provider responses, free-text failure details, SDP, network addresses, or complete WebRTC statistics.
11. Apply the same safe record format to live events, snapshots, saved files, and new ZIP exports.
12. Existing saved raw runs are not erased by this change. The new dashboard must not load or export those legacy raw runs.
13. Stop and consent withdrawal do not erase existing records or recall content already sent to a provider.
14. Already submitted cloud requests can finish. Keep any later diagnostics content-free and associate them with the original run and capture.
15. Phone runs keep transient analysis in app memory. A connected dashboard receives phone-run status only.
16. Keep run folders and dashboard runtime files out of Git. Never commit or publish private run content.
17. Cloud transfer is not local-only processing. Provider retention and use depend on the applicable service terms and account settings.

## Microphone and voice

1. Require separate explicit consent for automatic voice, Android microphone permission, capture/cloud consent, and an OpenAI key.
2. Preserve automatic voice when an eligible warning opens. App approval does not itself grant microphone consent.
3. Capture microphone audio only for the active voice session, including its connection setup. Never use the microphone for background screen monitoring.
4. The red X, Android Back, warning backgrounding, and session shutdown stop microphone capture and playback.
5. Stop, capture-consent withdrawal, and service loss close the warning and its voice session.
6. Release local microphone and playback resources before warning dismissal permits capture to resume.
7. Do not save audio or transcripts. Keep only safe timing, status, and numeric audio metrics.
8. These rules do not authorize recording third-party calls. Microphone audio can include nearby people or speakerphone sound.
9. Website microphone use remains limited to an active call. Ending a call, leaving the page, hiding the tab, or reaching the call limit stops input and playback.
10. Website captions can show transient output text. Do not retain transcripts in logs or exports.
11. Each website call ends after three minutes. The browser can open an allowed microphone during connection setup.
12. Website input sends silence until one second after the first output transcript arrives, or six seconds after session.started if speech has not begun.
13. Android voice does not use that initial microphone hold.
14. The website can connect with silent input when microphone access fails. It does not capture microphone audio without permission.

## Credentials and local access

1. Keep API keys out of source, APK resources, plaintext preferences, run files, exports, browser storage, and diagnostics.
2. Enter keys through phone setup or the desktop's masked dialog. Never request keys in chat or embed them in scripts.
3. Clear key inputs after submission or dismissal. Key readiness means loaded, not authenticated.
4. Use each phone key only with its provider. Keep active keys in phone process memory.
5. Optional phone persistence uses AES-GCM with Android Keystore. Store ciphertext under `noBackupFilesDir`, outside Android backups.
6. Phone **Remember on this phone** starts cleared. Desktop **Remember on this phone** starts selected. Saving requires the chosen action.
7. On Windows, **Remember on this PC** starts selected and saves only in Windows Credential Manager.
8. Separate Windows credentials by provider, phone serial, and Windows user. Use `CRED_PERSIST_LOCAL_MACHINE` for this computer.
9. PC and phone persistence are independent. Sending with the PC checkbox cleared deletes its Windows copy. A cleared phone checkbox leaves its saved copy unchanged.
10. Phone **Clear** deletes the active and saved phone key for that provider. Desktop **Clear key** also deletes its Windows copy and requests phone deletion.
11. Wait for phone save or erase confirmation before disconnecting. Older apps can support only memory delivery.
12. Restore phone keys at process startup. A newer entry or clear action takes priority. App removal or data removal deletes phone persistence.
13. Automatic Windows restore supplies phone memory only when the selected phone reports a missing key. Manual **Use saved key** follows the phone checkbox.
14. Return saved-key availability to the browser without values. Hold desktop key values only briefly during delivery.
15. Other operating systems support manual delivery and optional encrypted phone storage. Do not add cloud vaults or bundled credentials.
16. Protect desktop key commands with the existing Origin and command-token checks. Accept at most 2,048 ASCII characters without whitespace.
17. Bind the desktop server to this computer. Use the ADB-forwarded debug socket. Release builds expose no dashboard socket.
18. Keep direct Jev, voice, phone credentials, and the dashboard bridge within existing development-build limits. Keep `isAccessibilityTool` false.
19. Website voice reads its OpenAI secret through AWS Secrets Manager and keeps it in server memory. Never return it to the browser or errors.
20. A secret can contain a raw key or JSON. JSON uses the configured field, or its first sk- value when no field is configured.
21. A provider key rejection causes the next website call to read the secret again.
22. Website voice responses contain the needed session response and configured commentary. Diagnostics contain safe identifiers only.

## California research — October 21, 2026

1. Distinguish a recipient's later sharing of delivered text from interception during transmission. No reviewed opinion directly decides ScamCatch's screenshot, OCR, and AI flow.
2. [Boulton v. Community.com (9th Cir. 2025)](https://cdn.ca9.uscourts.gov/datastore/memoranda/2025/01/28/23-3145.pdf) rejected section 631 liability for reading after receipt and section 632 liability for the text at issue.
3. Boulton is unpublished and nonprecedential. It states that California's Supreme Court has not decided whether section 632's exclusion applies to text messages.
4. [Ribas v. Clark (Cal. Supreme Court 1985)](https://law.justia.com/cases/california/supreme-court/3d/38/355.html) distinguishes later repetition from simultaneous disclosure to an undisclosed listener.
5. [In re Facebook Internet Tracking Litigation (9th Cir. 2020)](https://cdn.ca9.uscourts.gov/datastore/opinions/2020/04/09/17-17486.pdf) rejected automatic participant status for secret simultaneous duplication. It left other statutory elements unresolved.
6. Automation alone does not establish interception. Capture timing, draft synchronization, consent, and provider uses require analysis. A local unsent draft is not automatically in transit.
7. District courts differ on whether an analysis provider acts as the recipient's tool or an independent listener. [Tate v. VITAS Healthcare (2025)](https://www.govinfo.gov/content/pkg/USCOURTS-caed-2_24-cv-01327/pdf/USCOURTS-caed-2_24-cv-01327-3.pdf) allowed claims involving a provider's reserved independent uses.
8. These authorities support a post-delivery argument, not a blanket exemption for all text or screen capture. Obtain advice on the actual implementation before claiming compliance.
