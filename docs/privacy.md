# Privacy rules

## How to read this file

1. The rules below are the existing approved privacy requirements.
2. The [data inventory](#personal-data-inventory--october-22-2026) records current handling, recipients, retention, and deletion limits.
3. The [HIPAA assessment](#hipaa-and-health-data--october-22-2026) records conditional legal requirements and unresolved facts. It does not establish compliance.
4. The October 22 inventory adds documentation only. It does not change capture, logging, permissions, providers, or retention settings.

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

## Personal data inventory — October 22, 2026

1. Scope: Android, the desktop console, the public website, and website voice infrastructure in this repository at commit `9bae837`.
2. Evidence: source code, infrastructure templates, and the linked provider documentation. Live cloud settings, contracts, private records, and network traffic were not inspected.
3. Business scope: the owner answered “Both, or not decided yet” for independent consumer use and healthcare relationships. Neither model is excluded.
4. Current facts, documented vendor behavior, and unresolved items are separate. A written rule does not prove every runtime or vendor follows it.
5. People: users, message senders and recipients, people mentioned in messages, nearby speakers, website visitors, and people named in configuration or public assets.
6. Possible content: names, handles, addresses, email, phone numbers, messages, drafts, relationships, photographs, and other visible personal details.
7. Possible sensitive content: financial accounts, transactions, passwords, identity documents, health conditions, prescriptions, care payments, sexual information, and information about children.
8. App approval does not classify content. A selected messaging app can contain any of these categories, including another person's information.
9. Metadata includes app use, timestamps, device identifiers, network information, and scam scores. Removing raw text does not make these records anonymous.
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
12. **Results and inferences:** Numeric probabilities, threshold decisions, capture IDs, and status stay in phone state and can enter desktop records.
13. **Warning evidence:** A flagged result combines the captured text and relevant scores in memory. Voice can send that evidence to OpenAI.
14. **Warning display:** The warning UI and fallback notification reveal a scam concern. They do not intentionally display the raw conversation in the notification.
15. **Memory limits:** Object release and bitmap recycling do not establish secure erasure of every runtime, OS, native-library, or provider copy.
16. Sources: [ApprovedAppsCard](../app/src/main/java/com/antiscam/app/ApprovedAppsCard.kt), [CaptureStore](../app/src/main/java/com/antiscam/app/capture/CaptureStore.kt), [TypingCaptureService](../app/src/main/java/com/antiscam/app/capture/TypingCaptureService.kt), [JevAnalysis](../app/src/debug/java/com/antiscam/app/capture/JevAnalysis.kt), and [ScamIntervention](../app/src/main/java/com/antiscam/app/capture/ScamIntervention.kt).

### Voice, credentials, and configuration

1. **Android voice:** The active session sends microphone audio, instructions, connection data, and up to 6,000 characters of warning evidence to OpenAI. Nearby speech can enter.
2. **Voice output:** Generated speech and provider events return through WebRTC. The app processes them transiently without an intentional audio or transcript file writer.
3. **Connection data:** SDP, ICE/network candidates, and session identifiers pass through connection setup. The application record excludes SDP and complete WebRTC statistics.
4. **Voice diagnostics:** Timings, byte/packet counts, jitter, audio energy, duration, usage, finalization status, and bounded error identifiers can enter desktop records.
5. **Stopping voice:** Closing the session releases local audio resources. It does not request deletion of provider abuse-monitoring records or other provider data.
6. **API keys:** Active keys exist in memory. Optional phone copies use Android Keystore encryption. Optional PC copies use Windows Credential Manager.
7. **Key transport:** Desktop key entry passes through the loopback server and ADB bridge to the phone. Each provider receives its own key for authentication.
8. **Credential linkage:** Windows credential names use a hash of the phone serial, scoped to provider and Windows user. Browser status excludes key values.
9. **Scripts and questions:** Exact instructions, names/IDs/revisions, models, and questions can persist in Git, records, and exports. Phone scripts use plaintext `noBackupFilesDir/voice-scripts.json`.
10. **Configuration content:** The no-conversation rule applies to scripts too. Static-field selection does not detect personal information pasted into an instruction or name.
11. **Deletion:** Key Clear removes managed copies, but does not revoke the provider-issued key. Capture Reset leaves the script library, desktop files, and provider copies unchanged.
12. Sources: [LiveVoice](../app/src/debug/java/com/antiscam/app/capture/LiveVoice.kt), [PhoneCredentials](../app/src/main/java/com/antiscam/app/capture/PhoneCredentials.kt), [credential_store.py](../dashboard/credential_store.py), [VoiceScripts](../app/src/main/java/com/antiscam/app/capture/VoiceScripts.kt), and [voice-scripts.md](../app/src/main/assets/voice-scripts.md).

### Desktop records, exports, and local diagnostics

1. **Connection identity:** ADB supplies device serial, model/status, and connection state. The selected serial enters a desktop run record and credential lookup.
2. **New run contents:** `run.json`, `events.jsonl`, `summary.txt`, and `ocr/*.json` contain permitted metadata, static configuration, and numeric Jev results.
3. **Linkable fields:** IDs, precise times, app activity, versions, and device serial can link records to someone. Absolute run-folder paths can reveal a computer username.
4. **Raw-content boundary:** New capture records exclude raw OCR, text regions, warning evidence, audio, and transcripts. The dashboard uses permitted fields before storage and UI delivery.
5. **Legacy records:** Earlier folders can contain raw conversations. Discovery parses `run.json` into memory before rejecting restoration/export. Old inbound events can also exist briefly before sanitization.
6. **ZIP files:** New exports contain permitted metadata and configuration. A downloaded or shared ZIP is an independent copy beyond the application's deletion controls.
7. **Retention:** No age-based run/ZIP deletion exists. Temporary write files can remain after interruption. Late results can append after Stop. Starting another run does not erase files.
8. **Access:** The console binds to loopback and uses existing browser-origin/token checks for commands. These are not per-person access roles or a healthcare audit system.
9. **Disk protection:** Run JSON/text files are not encrypted by the application. Windows permissions, disk encryption, backups, sync tools, and administrator access remain external controls.
10. **Other local state:** Launcher files contain connection/process information without documented expiration. Android retains run-session metadata for late callbacks until process exit, without map pruning.
11. **Error handling:** Product code uses controlled messages and bounded provider identifiers. SDK diagnostics, OS crash reports, debugging tools, and memory dumps remain separate exposure paths.
12. **Backups:** Android declares `allowBackup=false`, and phone key ciphertext uses `noBackupFilesDir`. PC backups, downloaded ZIPs, and third-party copies remain unverified.
13. **Development tools:** Git ignores run folders and common runtime outputs. This does not remove old commits, manual copies, screenshots, support attachments, or AI-tool uploads.
14. Sources: [RunMonitor](../app/src/main/java/com/antiscam/app/capture/RunMonitor.kt), [RunBridge](../app/src/debug/java/com/antiscam/app/capture/RunBridge.kt), [dashboard/server.py](../dashboard/server.py), [dashboard launcher](../scripts/dashboard.ps1), [manifest](../app/src/main/AndroidManifest.xml), and [.gitignore](../.gitignore).

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
11. **Lambda logs:** Explicit logs record session creation and selected errors. They do not intentionally print request bodies, SDP, conversations, or audio. The template sets 14-day retention.
12. **Rate-limit data:** DynamoDB stores aggregate UTC day, call count, last start, and expiration. The template enables TTL with a seven-day expiration target.
13. **TTL limit:** An expiration target is not proof of exact deletion time. Actual DynamoDB deletion and backup settings were not inspected.
14. **Cloud telemetry:** CloudFront/S3 access logs, CloudTrail, WAF, tracing, managed forwarding policies, platform errors, and actual retention remain unverified.
15. **URLs:** The client uses `scam` and `voice` query options. The alternate-domain redirect preserves paths/queries. URLs can remain in history or infrastructure records.
16. **Caches:** Public assets have short cache lifetimes. Voice responses use `no-store`. Public caching rules do not govern provider records or browser debugging captures.
17. **Old public objects:** Publishing omits S3 deletion, and the bucket has a Retain policy. Removing a local asset does not erase every hosted copy.
18. **Secrets:** Secrets Manager supplies the hosted OpenAI key. An origin token also exists in service configuration. Neither is customer conversation content.
19. **Local website mode:** The loopback server uses equivalent context/voice processing and suppresses ordinary HTTP request logs. Selected operational messages still reach the console.
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
15. These are open decisions and evidence gaps. This inventory does not authorize new collection, deletion, filtering, vendor configuration, or application changes.
