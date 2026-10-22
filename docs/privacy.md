# Privacy rules

## How to read this file

1. This file is the canonical source for privacy rules and known limits.
2. The [incident fields](#allowed-incident-fields--october-22-2026) replace the earlier permission to save diagnostic metadata.
3. The [data inventory](#personal-data-inventory--october-22-2026) records source behavior, recipients, and unresolved external retention.
4. The [HIPAA assessment](#hipaa-and-health-data--october-22-2026) remains conditional. This implementation does not establish compliance.
5. Source changes take effect after restarting the desktop server and installing the updated app. Hosted voice changes need deployment.

## Allowed incident fields — October 22, 2026

1. **Default:** Store no captured personal data unless its exact field appears below. Unknown or invalid fields reject the whole record.
2. **`involvedAccount`:** Only `platform` and `id`. Each is a nonblank, single-line string. Limits are 64 and 256 characters respectively.
3. **`flaggedAccount`:** The same exact account shape. A flagged account is not proof of the real person's identity or wrongdoing.
4. **`occurredAt`:** Optional ISO timestamp with date, time, and timezone. Omit it when unknown. Never substitute a run or export time.
5. **`scamMessage`:** The identified scam message only, up to 4,096 characters. This does not approve full OCR, a conversation, nearby text, audio, or transcripts.
6. **`scamType`:** A single-line scam category label, up to 80 characters. It is not an explanation, technical error, or general details field.
7. **`scamCount`:** Optional integer from 0 to 1,000,000, with `involvedAccount` present. Supply a confirmed count. Never count screenshots or detector flags as completed scams.
8. All fields are optional, but a record must contain at least one. Omit unknown values instead of storing nulls, placeholders, or guesses.
9. No names, user profiles, device serials, app activity, window IDs, run IDs, probabilities, instructions, or diagnostics belong in the retained record.
10. Do not truncate invalid values into compliance. Reject invalid types, extra account properties, control characters, oversized values, and unsupported record shapes.
11. Account provenance, scam confirmation, duplicate handling, and automatic classification remain unresolved. The schema validates structure, not truth or semantic privacy.
12. A permitted message can itself contain health or financial information. Field validation does not remove that content or resolve HIPAA duties.

### Implemented storage boundary

1. [incident_records.py](../dashboard/incident_records.py) is the single captured-data file boundary. Its policy is `incident_fields_only_v1`.
2. A structured `scam_incident` bridge event can supply only the six fields above. It must match an active desktop run with capture prerequisites still enabled.
3. The current Android app does not produce structured incidents. It does not identify the two accounts, isolate a scam message, classify scam types, or confirm scam counts.
4. Ordinary runs therefore save no new incident records. Never map full OCR, Jev results, or live snapshots into an incident as a fallback.
5. Valid records use `runs/incidents-v1.json`, or the configured runs directory. The file is an array of validated records with no diagnostic wrapper.
6. Validate before any file creation. Atomic temporary files contain only validated records. Remove the temporary file after success or a handled failure.
7. Validate the entire existing incident file before append or export. Invalid stored records block the operation; do not salvage or export their contents.
8. Exports contain only `incidents.json` in `scamcatch-incidents.zip`. Use no run folders, diagnostic files, summaries, or generated event timestamps.
9. Do not load legacy run directories. Do not create `run.json`, `events.jsonl`, `summary.txt`, or per-capture files. No error fallback may save diagnostics.
10. Keep live device status, capture summaries, model results, and activity in process/browser memory only. A new run resets history; server exit discards it.
11. Suppress dashboard HTTP access and exception logs. Keep HTTP responses uncached. Do not place rejected incident content in errors or status.
12. Website source suppresses custom per-request success/error logs and local HTTP exception logs. Existing cloud records and platform logging remain separate.
13. Preserve existing operational configuration: app choices, consent flags/version, monitoring state, selected/imported voice scripts, and explicitly saved credentials. These stores must not hold captured conversations.
14. Preserve the website's global quota fields: `day`, `calls`, `last_start`, and `expires_at`. They prevent excess service use and contain no user/account identifier.
15. Configuration, operator deployment logs, OS/SDK telemetry, and vendor storage are separate from this captured-data schema. This change does not prove that those systems retain only six fields.
16. Do not erase existing files or external copies without a separate cleanup decision. No automatic incident expiry is set; decide a retention schedule before enabling incident production.
17. The launcher rejects reuse of an older dashboard server. Stop the old run and restart that server before relying on the new boundary.
18. No new Jev questions, extraction feature, healthcare workflow, provider setting, or cloud deployment is included.

### Evidence for academics and care organizations

1. State a bounded claim for a named build and configuration, such as which fields the desktop persists. Do not claim that all personal information is excluded everywhere or that foreground checks isolate every captured pixel.
2. Maintain a source-to-destination register with each data category, transformation, recipient, writer, lifetime, deletion route, and unresolved dependency. Include SDK, platform, provider, support, and backup paths.
3. Supply the approved schema, source revision, build hash, configuration, dependency versions, and relevant deployed settings. A source-code rule alone does not prove the installed system follows it.
4. Propose reproducible end-to-end inspections using controlled synthetic data to check storage, exports, network traffic, errors, retries, stop/restart, upgrades, and deletion. Check backups and provider settings separately. Use integration checks, not unit tests. Record their scope and result below.
5. Give an independent reviewer the method, results, expected outcomes, and known limits for that exact build. Use synthetic or redacted evidence; do not create a real-conversation archive to prove privacy.
6. Keep detection-quality evidence separate from privacy evidence. A future evaluation needs defined ground truth, duplicate handling, false positives, missed scams, confirmation quality, and stated uncertainty. Production alert counts alone cannot establish these.
7. Research or care-provider reports need separate disclosure rules. A small group, a linked identifier, or a rare incident can identify a person even without a name. Permission to keep scam counts does not grant research access to residents' data.
8. Record who reviewed the claim, what they inspected, the date, and what remains unverified. An independent review is evidence within its scope, not a blanket guarantee or HIPAA certification.

### Verification — October 22, 2026

1. `python -B scripts/verify-privacy-flow.py` exercises real loopback HTTP, bridge sockets, files, and ZIP export. Only ADB and credential access use synthetic substitutes.
2. The check passed: live events, numeric Jev results, and late callbacks created no diagnostic files. Legacy files stayed unchanged and were not restored.
3. The check passed: only the six supplied incident fields persisted and exported. Unknown fields, extra nested fields, wrong types, invalid dates, and oversized messages were rejected.
4. The check passed: a stored record with an added diagnostic field blocked export. Rejected content did not appear in the response. Restart restored no diagnostic history.
5. The check creates synthetic artifacts under ignored `tmp/privacy-flow-*`. It does not connect to a real phone, provider, account, or credential store. No unit tests ran.
6. Python, dashboard JavaScript, and launcher PowerShell syntax checks passed. Android `:app:assembleDebug` passed.
7. APK SHA-256: `432cc9304c9d6804af58b5024f159cd128818b571a0a6c04130412ba3715930f`. Rebuilds can change the hash.
8. Device behavior, independent review, deployed AWS settings, provider retention, crash/memory dumps, and backup deletion were not verified. The APK was not installed and hosted code was not deployed.

## Scope and app selection

1. This file is the canonical source for privacy rules. Update it when capture, storage, cloud transfer, microphone, or credential behavior changes.
2. These rules describe the approved development scope as of October 22, 2026. They do not claim legal compliance or Google Play approval.
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
10. Show OCR character/region counts and the triggering app only in live memory. Do not persist those diagnostics or region text/bounds.
11. Do not restore the former sensitive-node scan or add a replacement traversal. The foreground gate must run before any permitted content access.
12. Screenshot images stay in device memory. Do not save or export screenshots.
13. Foreground checks and asynchronous screenshots are not atomic. Checks before capture, on callback, and after OCR reduce risk, but Android delays and intervening app switches can leave a race. The gate cannot guarantee each frame's foreground identity or exclusive approved-app pixels.

## Content, cloud transfer, and records

1. Run ML Kit OCR on the phone. Keep approved OCR text only as transient input for Jev analysis and warning evidence.
2. With the required consent and key, send approved OCR text to TypeSafe over HTTPS. The desktop recorder does not call Jev.
3. With separate voice consent and microphone permission, send approved warning evidence and active-session audio to OpenAI.
4. Treat captured text as untrusted evidence. It must not change system instructions or authorize app actions.
5. Do not retain raw OCR, focused text, warning evidence, audio, or transcripts. Only a separately identified `scamMessage` may enter the incident schema.
6. Do not send raw OCR or warning evidence to the desktop dashboard. Live summaries and numeric results are transient, not saved records.
7. Do not persist run/capture IDs, timestamps, durations, capture counts, error codes, probabilities, or thresholds. They may support transient live status only.
8. Keep static questions and scripts in their configuration stores. Do not copy instructions, models, thresholds, or script identifiers into incident files.
9. Static configuration must not contain captured conversations or provider-generated free text.
10. Do not record raw provider responses, free-text failure details, SDP, network addresses, or complete WebRTC statistics.
11. Apply the incident validator to saved records and exports. Live events and snapshots have no disk writer.
12. Existing saved raw runs are not erased by this change. The new dashboard must not load or export those legacy raw runs.
13. Stop and consent withdrawal do not erase existing records or recall content already sent to a provider.
14. Already submitted cloud requests can finish. Later diagnostics stay transient and cannot append to old run files.
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
7. Do not save audio, transcripts, timing, status, or numeric audio metrics as records. Live voice metrics remain transient.
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
22. Website voice responses contain the needed session response and configured commentary. Do not log per-request session identifiers or errors.

## California research — October 21, 2026

1. Distinguish a recipient's later sharing of delivered text from interception during transmission. No reviewed opinion directly decides ScamCatch's screenshot, OCR, and AI flow.
2. [Boulton v. Community.com (9th Cir. 2025)](https://cdn.ca9.uscourts.gov/datastore/memoranda/2025/01/28/23-3145.pdf) rejected section 631 liability for reading after receipt and section 632 liability for the text at issue.
3. Boulton is unpublished and nonprecedential. It states that California's Supreme Court has not decided whether section 632's exclusion applies to text messages.
4. [Ribas v. Clark (Cal. Supreme Court 1985)](https://law.justia.com/cases/california/supreme-court/3d/38/355.html) distinguishes later repetition from simultaneous disclosure to an undisclosed listener.
5. [In re Facebook Internet Tracking Litigation (9th Cir. 2020)](https://cdn.ca9.uscourts.gov/datastore/opinions/2020/04/09/17-17486.pdf) rejected automatic participant status for secret simultaneous duplication. It left other statutory elements unresolved.
6. Automation alone does not establish interception. Capture timing, draft synchronization, consent, and provider uses require analysis. A local unsent draft is not automatically in transit.
7. District courts differ on whether an analysis provider acts as the recipient's tool or an independent listener. [Tate v. VITAS Healthcare (2025)](https://www.govinfo.gov/content/pkg/USCOURTS-caed-2_24-cv-01327/pdf/USCOURTS-caed-2_24-cv-01327-3.pdf) allowed claims involving a provider's reserved independent uses.
8. These authorities support a post-delivery argument, not a blanket exemption for all text or screen capture. Obtain advice on the actual implementation before claiming compliance.

## Personal data inventory — October 22, 2026

1. Scope: Android, the desktop console, the public website, and website voice infrastructure. Baseline commit `9bae837`; source changes below reflect the incident-only implementation.
2. Evidence: source code, infrastructure templates, and the linked provider documentation. Live cloud settings, contracts, private records, and network traffic were not inspected.
3. Business scope: the owner answered “Both, or not decided yet” for independent consumer use and healthcare relationships. Neither model is excluded.
4. Current facts, documented vendor behavior, and unresolved items are separate. A written rule does not prove every runtime or vendor follows it.
5. People: users, message senders and recipients, people mentioned in messages, nearby speakers, website visitors, and people named in configuration or public assets.
6. Possible content: names, handles, addresses, email, phone numbers, messages, drafts, relationships, photographs, and other visible personal details.
7. Possible sensitive content: financial accounts, transactions, passwords, identity documents, health conditions, prescriptions, care payments, sexual information, and information about children.
8. App approval does not classify content. A selected messaging app can contain any of these categories, including another person's information.
9. Live metadata includes app use, timestamps, device identifiers, network information, and scam scores. Earlier files can retain it; removing raw text did not anonymize them.
10. No customer account, billing, contact-import, GPS, or health-record integration was found in the scoped product code. Visible or spoken equivalents can still enter capture.
11. No browser-extension package was found in this repository. Installed extensions, separate repositories, support systems, and company records outside this scope remain unaccounted for.

### Android collection and processing

1. **Installed apps:** The picker reads launchable package IDs and labels locally. It stores selected package IDs in `capture_settings` preferences.
2. **Consent and settings:** Capture consent, consent version, monitoring state, and voice consent persist locally. They are current settings, not a complete consent history.
3. **Foreground identity:** Accessibility events trigger package/window checks. Unapproved identity exists briefly in memory before rejection. Approved identity can reach live desktop status between runs.
4. **Interaction metadata:** Typing state, tap, scroll, focus, selection, and content-change events drive capture. Approved-app activity can reveal when someone uses an app.
5. **Focused input:** The service reads approved focused text to calculate node identity, length, and a hash in memory. It does not log field text or fingerprints.
6. **Fingerprints:** These are change detectors, not encrypted text or proof of de-identification. Invalidation clears the retained fingerprint.
7. **Screen pixels:** The full screenshot, including keyboard pixels, enters hardware/software memory first. Masking occurs before OCR, not before acquisition. Bitmap resources are then released.
8. **Other visible information:** Overlays, split-screen content, system UI, and visible sensitive fields can enter the screenshot. The existing foreground gate cannot isolate pixels.
9. **OCR and drafts:** ML Kit produces text in memory. Captured unsent drafts receive the same processing as delivered text. No health-content filter exists.
10. **Jev submission:** Eligible OCR text, static questions, and the model go to TypeSafe over HTTPS, with a 64-KiB text limit. Raw OCR bypasses the desktop.
11. **Pending requests:** OCR text remains in request/callback memory until processing releases it. Stop and key clearing do not recall an already submitted request.
12. **Results and inferences:** Numeric probabilities, threshold decisions, capture IDs, and status stay in phone state and transient desktop memory. They cannot enter incident files.
13. **Warning evidence:** A flagged result combines the captured text and relevant scores in memory. Voice can send that evidence to OpenAI.
14. **Warning display:** The warning UI and fallback notification reveal a scam concern. They do not intentionally display the raw conversation in the notification.
15. **Memory limits:** Object release and bitmap recycling do not establish secure erasure of every runtime, OS, native-library, or provider copy.
16. Sources: [ApprovedAppsCard](../app/src/main/java/com/antiscam/app/ApprovedAppsCard.kt), [CaptureStore](../app/src/main/java/com/antiscam/app/capture/CaptureStore.kt), [TypingCaptureService](../app/src/main/java/com/antiscam/app/capture/TypingCaptureService.kt), [JevAnalysis](../app/src/debug/java/com/antiscam/app/capture/JevAnalysis.kt), and [ScamIntervention](../app/src/main/java/com/antiscam/app/capture/ScamIntervention.kt).

### Voice, credentials, and configuration

1. **Android voice:** The active session sends microphone audio, instructions, connection data, and up to 6,000 characters of warning evidence to OpenAI. Nearby speech can enter.
2. **Voice output:** Generated speech and provider events return through WebRTC. The app processes them transiently without an intentional audio or transcript file writer.
3. **Connection data:** SDP, ICE/network candidates, and session identifiers pass through connection setup. The application record excludes SDP and complete WebRTC statistics.
4. **Voice diagnostics:** Timings, byte/packet counts, jitter, audio energy, usage, and status can enter transient desktop memory. The incident writer cannot retain them.
5. **Stopping voice:** Closing the session releases local audio resources. It does not request deletion of provider abuse-monitoring records or other provider data.
6. **API keys:** Active keys exist in memory. Optional phone copies use Android Keystore encryption. Optional PC copies use Windows Credential Manager.
7. **Key transport:** Desktop key entry passes through the loopback server and ADB bridge to the phone. Each provider receives its own key for authentication.
8. **Credential linkage:** Windows credential names use a hash of the phone serial, scoped to provider and Windows user. Browser status excludes key values.
9. **Scripts and questions:** Instructions, names/IDs/revisions, models, and questions persist as configuration in Git and phone storage, not new incident records. Phone scripts use plaintext `noBackupFilesDir/voice-scripts.json`.
10. **Configuration content:** The no-conversation rule applies to scripts too. Static-field selection does not detect personal information pasted into an instruction or name.
11. **Deletion:** Key Clear removes managed copies, but does not revoke the provider-issued key. Capture Reset leaves the script library, desktop files, and provider copies unchanged.
12. Sources: [LiveVoice](../app/src/debug/java/com/antiscam/app/capture/LiveVoice.kt), [PhoneCredentials](../app/src/main/java/com/antiscam/app/capture/PhoneCredentials.kt), [credential_store.py](../dashboard/credential_store.py), [VoiceScripts](../app/src/main/java/com/antiscam/app/capture/VoiceScripts.kt), and [voice-scripts.md](../app/src/main/assets/voice-scripts.md).

### Desktop records, exports, and local diagnostics

1. **Connection identity:** ADB supplies device serial and connection state for live use and credential lookup. The incident writer excludes these fields.
2. **New records:** Only the validated incident schema reaches the incident file or export. Normal Android events currently produce no such records.
3. **Live data:** App activity, precise times, scores, counts, and configuration remain transient. HTTP responses use `no-store`; no browser storage is added.
4. **Legacy records:** Existing metadata and raw-content folders remain untouched. The console no longer enumerates or parses old run files.
5. **Export:** Revalidate every record. Export no metadata, summaries, scripts, events, or per-capture files. A downloaded ZIP remains an independent copy.
6. **Retention:** No automatic incident expiry exists. No producer is enabled. Decide the required lifetime before enabling one. OS interruption can leave a validated temporary file.
7. **Access:** The console binds to loopback and checks command origin/token. These controls are not per-person healthcare access roles.
8. **Disk protection:** Incident JSON is not encrypted by the application. Windows permissions, disk encryption, backups, sync, and administrator access remain external controls.
9. **Errors:** Validation failures use fixed messages without input. Disk failure stops capture without writing diagnostic fallback files. HTTP request tracebacks are suppressed.
10. **Backups:** Android declares `allowBackup=false`. Phone key ciphertext uses `noBackupFilesDir`. PC backups and exported copies remain outside application deletion controls.
11. **Development tools:** Git ignores runtime data. This does not erase old commits, manual copies, support attachments, memory dumps, or AI-tool uploads.
12. Sources: [RunMonitor](../app/src/main/java/com/antiscam/app/capture/RunMonitor.kt), [RunBridge](../app/src/debug/java/com/antiscam/app/capture/RunBridge.kt), [incident records](../dashboard/incident_records.py), [desktop server](../dashboard/server.py), and [dashboard launcher](../scripts/dashboard.ps1).

### Website and hosted voice

1. **Visits:** CloudFront and the S3 origin serve public files. Network handling includes connection/request metadata even without an application analytics script.
2. **Public content:** The site intentionally publishes the founder's name/photo and company information. Public assets, Git history, CDN caches, and prior uploads can retain copies.
3. **Browser tracking:** No analytics, advertising SDK, cookies, localStorage, sessionStorage, or IndexedDB use was found in public source. Provider/platform telemetry is separate.
4. **Demo context:** The normal UI sends hard-coded scenario names/messages or a page section ID. It does not read visitors' other apps, tabs, contacts, or screens.
5. **Arbitrary submitted text:** The voice endpoint validates context shape/size but does not require exact demo text. A caller can submit private text.
6. **Real audio:** Website microphone input is real visitor speech, even when the opening conversation is a demo. It can contain financial or health information.
7. **Recipients:** The browser posts SDP and context to CloudFront/Lambda. Lambda sends them with instructions to OpenAI. WebRTC then carries browser/provider audio and events.
8. **Captions:** Assistant output transcripts remain in browser memory/DOM for captions. Input-transcript events help track turns. No transcript-file writer was found.
9. **Storage setting:** Android, hosted website voice, and local website voice request `store:false`. This does not establish zero provider retention.
10. **Call ending:** End, page hiding/exit, failure, and the three-minute timer stop local capture. Session closing is not provider-data deletion.
11. **Lambda logs:** Updated source removes custom session/error prints. Deployment is still required. Lambda platform/exception logs and existing records remain; the template sets 14-day retention.
12. **Rate-limit data:** DynamoDB stores aggregate UTC day, call count, last start, and expiration. The template enables TTL with a seven-day expiration target.
13. **TTL limit:** An expiration target is not proof of exact deletion time. Actual DynamoDB deletion and backup settings were not inspected.
14. **Cloud telemetry:** CloudFront/S3 access logs, CloudTrail, WAF, tracing, managed forwarding policies, platform errors, and actual retention remain unverified.
15. **URLs:** The client uses `scam` and `voice` query options. The alternate-domain redirect preserves paths/queries. URLs can remain in history or infrastructure records.
16. **Caches:** Public assets have short cache lifetimes. Voice responses use `no-store`. Public caching rules do not govern provider records or browser debugging captures.
17. **Old public objects:** Publishing omits S3 deletion, and the bucket has a Retain policy. Removing a local asset does not erase every hosted copy.
18. **Secrets:** Secrets Manager supplies the hosted OpenAI key. An origin token also exists in service configuration. Neither is customer conversation content.
19. **Local website mode:** The loopback server uses equivalent context/voice processing and suppresses request logs, tracebacks, and custom session/error prints. Startup configuration messages remain.
20. **Notices:** Public HTML has no privacy-policy link or separate cloud-voice disclosure. Browser microphone permission is not a complete data-handling notice.
21. **Reporting:** Animated bank, authority, and trusted-contact reports are demonstrations. No corresponding production reporting integration was found in this scope.
22. Sources: [website app](../website/scamcatch/public/app.js), [voice client](../website/scamcatch/public/live.js), [Lambda handler](../website/scamcatch/infra/voice/handler.py), [voice template](../website/scamcatch/infra/voice.json), [site template](../website/scamcatch/infra/site.json), [local server](../website/scamcatch/server.py), and [publish workflow](../.github/workflows/publish-website.yml).

### External processors and their documented limits

1. **TypeSafe:** Receives OCR state and questions. Its privacy policy prohibits training on inputs but allows service processing and retention based on stated purposes. [Policy](https://typesafe.ai/legal/privacy-policy).
2. **TypeSafe retention:** Enterprise zero retention requires account arrangements. The published DPA gives purpose-based retention, not a fixed deletion interval. Actual account terms remain unverified. [Legal docs](https://docs.typesafe.ai/legal), [DPA](https://typesafe.ai/legal/data-processing).
3. **TypeSafe health scope:** The published DPA marks its sensitive-data schedule as N/A. No executed BAA or approved health-data scope was inspected. A DPA alone does not establish either.
4. **OpenAI:** Receives voice evidence, audio, instructions, and connection information. Its data-controls table lists no training for `/v1/live/sessions`. [Data controls](https://developers.openai.com/api/docs/guides/your-data).
5. **OpenAI retention:** Default abuse monitoring can retain content for up to 30 days, with exceptions. `store:false` controls session storage, not every retention purpose.
6. **OpenAI account controls:** Live sessions are eligible for zero retention subject to conditions. Actual organization/project settings, BAA coverage, and endpoint eligibility were not inspected.
7. **Google ML Kit:** Google states that OCR input and output stay on the device. SDK performance/utilization metrics still go to Google. [ML Kit terms](https://developers.google.com/ml-kit/terms).
8. **ML Kit metadata:** Published SDK categories include device/app information, installation identifiers, latency, configuration, input/output sizes, event types, and errors. [Android disclosure](https://developers.google.com/ml-kit/android-data-disclosure).
9. **ML Kit uncertainty:** The disclosure describes current SDKs. Exact telemetry, retention, consent handling, and healthcare suitability for the pinned build remain unverified.
10. **AWS:** Hosts public content and website voice setup, logs, secrets, and aggregate rate limits. Templates do not prove a BAA, eligible-service scope, or deployed controls.
11. **Platform and subprocessors:** Android, the browser, WebRTC infrastructure, network operators, and each vendor's subprocessors add processing boundaries. Their detailed logs and access are not established here.
12. **Account ownership:** Phone/desktop keys can belong to different people or organizations. One vendor agreement or retention setting cannot be assumed to cover every supplied key.
13. **No zero-data claim:** No local raw log does not mean no personal-data processing, no metadata, no external disclosure, or no provider retention.

## HIPAA and health data — October 22, 2026

1. **Applicability remains open:** Both consumer and healthcare operating models are possible. The legal role must follow the actual service and customer relationship.
2. **Consumer path:** A consumer's independently selected app does not become a HIPAA business associate merely because it receives health data. [HHS app scenarios](https://www.hhs.gov/sites/default/files/ocr-health-app-developer-scenarios-2-2016.pdf).
3. **Healthcare path:** Handling identifiable health data on behalf of a covered entity or its business associate can create business-associate duties. The same code can serve different legal roles.
4. **Health data:** Screenshots, drafts, OCR, voice, and evidence can describe care, conditions, prescriptions, or care payments. Oral information can also be PHI. [HHS guidance](https://www.hhs.gov/hipaa/for-professionals/special-topics/de-identification/index.html).
5. **Processing without storage:** Temporary receipt, analysis, or transmission still belongs in the HIPAA assessment. “We do not save it” does not resolve applicability.
6. **Cloud chain:** Processing PHI for a regulated customer can require business-associate agreements with relevant providers and subcontractors. Encryption alone does not remove that role. [HHS cloud guidance](https://www.hhs.gov/hipaa/for-professionals/special-topics/health-information-technology/cloud-computing/index.html).
7. **Conduit limit:** Intentional OCR/AI analysis is not merely transmission because it is brief. The narrow transmission-only exception does not establish an exemption for this flow.
8. **Separate permissions:** Android permissions and app consent do not replace a required BAA or a valid HIPAA authorization when one is needed. [HHS consent guidance](https://www.hhs.gov/hipaa/for-professionals/faq/what-is-the-difference-between-consent-and-authorization/index.html).
9. **Metadata:** IDs, dates, app activity, and linked scores are not automatically de-identified. HIPAA de-identification uses Safe Harbor or Expert Determination. [HHS de-identification guidance](https://www.hhs.gov/hipaa/for-professionals/special-topics/de-identification/index.html).
10. **Incidental health text:** Unexpected medical content is not a blanket exception. Permitted incidental disclosures depend on an otherwise permitted use and safeguards. [HHS guidance](https://www.hhs.gov/hipaa/for-professionals/privacy/guidance/incidental-uses-and-disclosures/index.html).
11. **Security duties:** If HIPAA applies, administrative, physical, and technical safeguards require assessment beyond these capture controls. No organizational compliance assessment occurred here. [Current Security Rule summary](https://www.hhs.gov/hipaa/for-professionals/security/laws-regulations/index.html).
12. **FTC scope:** HIPAA exclusion does not resolve misleading privacy promises or unfair data practices. Actual handling must support user-facing claims. [FTC guidance](https://www.ftc.gov/business-guidance/resources/mobile-health-apps-interactive-tool).
13. **Health Breach Notification Rule:** Applicability depends on qualifying health-record functions and relationships. Incidental health text alone does not establish coverage. Unauthorized disclosures can count when covered. [FTC compliance guide](https://www.ftc.gov/business-guidance/resources/complying-ftcs-health-breach-notification-rule-0), [final-rule explanation](https://www.ftc.gov/system/files/ftc_gov/pdf/p205405healthbreachnotificationrule.pdf).
14. **State laws:** Consumer health privacy can extend beyond HIPAA. State applicability, including Washington's My Health My Data Act, remains unresolved. [Washington AG guidance](https://www.atg.wa.gov/protecting-washingtonians-personal-health-data-and-privacy).
15. **No clearance:** No signed BAA, healthcare deployment approval, provider-account eligibility, or legal compliance determination is established by this inventory.

## Unresolved accounting items

1. **Service role:** Which customers use each flow, whose behalf ScamCatch serves, and whether consumer and healthcare operations are separate.
2. **Permitted purposes:** The legal basis for collection, automated analysis, cloud disclosure, third-party message content, bystander audio, and any later use.
3. **Health-data scope:** Whether health content is allowed, excluded, or handled through a distinct future workflow. No new restriction was implemented.
4. **Vendor chain:** Contracts, BAAs where required, permitted endpoints, subprocessors, regions, human access, training/secondary uses, retention, and incident reporting for each account.
5. **SDK behavior:** Actual ML Kit and WebRTC telemetry for the pinned versions and builds, including disclosures and any provider configuration.
6. **Retention schedule:** Approved lifetimes and owners for desktop metadata, legacy raw runs, ZIP exports, runtime logs, scripts, provider records, and backups.
7. **Deletion coverage:** A process for locating and deleting relevant copies, handling provider requests, confirming completion, and honoring applicable retention duties.
8. **Access controls:** Authorized staff, support access, device/PC access, encryption at rest, least privilege, and access records for healthcare operation.
9. **Incidents:** Ownership, detection, investigation, notification duties, vendor escalation, and evidence handling. Existing product diagnostics do not establish an incident program.
10. **User notices:** A public privacy notice, complete SDK/provider disclosures, consent records, healthcare authorizations where required, and a rights/contact process.
11. **Deployment evidence:** Actual AWS logging, forwarding, backup, TTL, region, service eligibility, and account settings. Source templates alone do not settle them.
12. **Outside this repository:** Support email, CRM, billing, crash tooling, business documents, browser extensions, separate prototypes, and staff/AI tools that may receive personal information.
13. **Data subjects:** Treatment of minors, third-party senders, bystanders, healthcare staff, and patients whose information the user can see or speak.
14. **Claims:** Evidence for any future claim of local-only processing, zero retention, anonymization, HIPAA compliance, or guaranteed app isolation.
15. **Incident record:** The six fields are approved. Their reliable sources, confirmation criteria, scam taxonomy, counting period, and duplicate handling remain undecided.
16. **Operational exceptions:** Existing configuration and global quota fields remain as listed above. Access, lifetimes, security audit needs, and any legal retention duties need further decisions.
17. **Proof and evaluation:** The bounded privacy claim, independent inspection method, detection-quality protocol, report audience, and disclosure limits for small groups.
18. These are open decisions and evidence gaps. Further extraction, cleanup, provider configuration, research disclosure, and healthcare changes require separate scope.
