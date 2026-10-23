# ScamCatch permission requirements

1. This file summarizes setup requirements. [Privacy rules](privacy.md) remain canonical for capture, cloud transfer, microphone use, and credentials.
2. **Accessibility — Android:** The user must enable **ScamCatch interaction capture** in Android settings. Installation cannot grant this access automatically. [Android reference](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService)
3. **Microphone permission — Android:** The user must grant microphone access before voice can use it. Check permission again when needed. [Android permissions](https://developer.android.com/training/permissions/requesting)
4. **Microphone use — ScamCatch:** Use the microphone only while a voice session starts or runs. Do not use it for background screen checks.
5. **Approved apps — ScamCatch:** Start with no approved apps. Require the user to select which apps ScamCatch may check.
6. **Capture/cloud consent — ScamCatch:** Require explicit consent for screen capture, text extraction, and sending extracted text to TypeSafe for Jev analysis.
7. **Automatic voice consent — ScamCatch:** Require separate consent for automatic voice sessions and sending warning context and microphone audio to OpenAI. Android microphone permission remains separate.
8. **Notifications — Android 13+:** Request notification permission for monitoring status and fallback warning alerts. Notification denial does not block **Start Run**. Android shows the foreground-service notice in Task Manager instead of the notification drawer when permission is denied. [Android notification rules](https://developer.android.com/develop/ui/compose/notifications/notification-permission#exemptions)
9. **Current capture prerequisites:** Require capture/cloud consent, at least one approved app, enabled monitoring, connected accessibility, and a loaded voice script before starting a run.
10. **Current phone-run prerequisites:** Also require available Jev/voice features, both loaded API keys, voice consent, and microphone permission. Making voice optional requires a code change.
11. **API keys:** Saved or loaded keys do not prove provider access. Verify provider access through a live request when authorized.
12. **Planned guided setup:** Explain capture/cloud use and obtain consent, select apps, enable accessibility, then obtain voice consent and microphone permission. Finish with readiness checks and **Start Run**.
13. **Planned setup recovery:** Resume at the next incomplete step when users return from Android settings. Reuse existing settings and permission state. Keep each consent choice distinct.
14. **Google Play:** This accessibility use requires clear in-app disclosure and affirmative consent. A guided setup does not remove these requirements. [Google Play requirements](https://support.google.com/googleplay/android-developer/answer/10964491?hl=en)
15. **Current build limit:** Jev, voice, and phone-key setup are development features. Release builds disable provider access. Customer service access requires separate work.
16. **Run lifetime:** Select **Start Run** once after setup. The foreground service continues when the setup card is removed from Recents. Its notification shows monitoring status when notifications are enabled.
17. **Stop controls:** **Stop Run** or notification **Stop monitoring** ends monitoring. A reboot or Android **Force stop** requires another manual **Start Run**.
18. **Recovery limits:** An Android service restart can recover a requested phone run during the same boot after all prerequisites are ready. Follow the [phone-run lifetime rules](privacy.md#phone-run-lifetime). Do not promise uninterrupted monitoring.
19. **Foreground service — Android 14+:** The monitoring service declares `specialUse` and its purpose. Google Play reviews that use case. This declaration does not approve distribution or enable customer provider access. [Android foreground-service types](https://developer.android.com/develop/background-work/services/fgs/service-types#special-use)
20. See [voice rules](voice.md) for warning behavior and session shutdown. See [the README](../README.md) for current installation and setup steps.

## S10 Plus device check — October 22, 2026

1. Checked the development build from source revision `9d67d83` on a Samsung Galaxy S10 Plus with Android 12.
2. Started a phone run and swiped its setup card out of Recents. The card disappeared. The same process and run remained active with accessibility and the foreground service connected.
3. After removal, OCR completed on an unsent Messages draft. Jev returned a completed, flagged result for sample crypto and WhatsApp language.
4. The warning activity opened automatically. OpenAI reported `Voice connected. You can speak.` Android reported microphone use during that warning.
5. Session connection does not prove audible speech or a successful spoken reply. Human confirmation remains pending.
6. Closing the warning stopped voice. Notification **Stop monitoring** cleared the run, recovery request, and foreground service. Reopening ScamCatch did not restart monitoring.
7. Deleted the unsent sample and temporary screen files. Started a fresh phone run and left monitoring active. No message was sent.
8. This check does not verify reboot, Force stop, process-death recovery, extended battery restrictions, or other phone models. No unit tests ran.
