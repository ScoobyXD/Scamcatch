# Run rules

1. The emulator and desktop dashboard are separate windows.
2. Start run begins a new recording. Stop run ends it and preserves the results.
3. Capture requires local consent, enabled monitoring, a connected accessibility service, and an active run.
4. Capture follows the current foreground app, including Home. There is no app selector or app allowlist.
5. The first eligible typing event requests one screenshot for that burst.
6. Each later typing event resets a one-second idle timer. It does not request another initial screenshot.
7. One second without text changes requests the final typing screenshot. A longer pause ends the burst even within a sentence.
8. Taps and scrolling request one screenshot after the interaction settles. Repeated events reset the settle timer.
9. Pending requests are coalesced. Only one screenshot/OCR operation runs at a time.
10. Capture attempts remain at least 1.5 seconds apart. The dashboard reports waiting rather than polling for completion.
11. App or window changes, stopping, pausing, consent withdrawal, and disconnects invalidate pending work. A settled new foreground window requests a capture during a run.
12. Late callbacks cannot write into a later run.
13. Secure windows and visible password or Android-marked sensitive fields prevent capture. Unmarked private content can still appear in OCR.
14. Screenshot requests, successful screenshots, failed screenshots, skips, and completed OCR results have separate counters.
15. Every capture has an ID that links its timestamps, status, and full extracted text.
16. Each run saves events and extracted text on the connected computer. Images remain in device memory.
17. Saved runs stay outside Git. OCR text never enters Logcat or console diagnostics.
18. Partial runs remain available after a connection failure. Their summary identifies the interruption.
19. Android sends interaction events. The app does not repeatedly inspect the screen on a polling interval.
20. The browser receives pushed status changes. A display clock or connection keepalive does not trigger capture.
21. Stopping or withdrawing consent does not delete records that were already saved on the computer.
22. Device checks use real apps. The demo app, sample conversations, and test-screen module are removed.
23. This prototype does not classify scams, send cloud analysis, or provide voice features.
24. The Android screen contains only accessibility setup and capture consent. The desktop dashboard contains run controls, status, extracted text, and activity.
25. Extracted text and activity occupy the two main dashboard columns. Each column has one scroll area.
26. When no run is active, interactions can update live status but do not create capture requests or skipped-capture messages.
