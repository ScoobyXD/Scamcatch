# Masking rules

1. Capture the visible default display through Android Accessibility.
2. Mask only windows that Android reports as `TYPE_INPUT_METHOD` on that display.
3. Use each keyboard window's reported screen rectangle, clipped to the display bounds.
4. Apply the same rule to docked and floating keyboards across phone manufacturers.
5. Mask the entire rectangle, including keyboard toolbars, suggestions, gaps, and any content behind transparent parts.
6. Fill each mask with opaque black pixels before ML Kit OCR starts. Do not remove keyboard words after OCR.
7. Do not add masks for password fields, sensitive fields, other overlays, system bars, or the lock screen.
8. Do not reject capture because a field is sensitive, a field is unavailable, or the screen is locked.
9. Do not reject capture merely because window rectangles overlap.
10. Missing privacy information must not create a mask. Keyboard exclusion is not guaranteed when Android omits or misreports keyboard bounds.
11. Record keyboard masks before requesting a screenshot. Recheck them before OCR starts and before accepting OCR results.
12. Discard the capture if the keyboard mask list changes during those checks. Do not publish its OCR text.
13. Other changes in window metadata make text sources uncertain. They do not add masks or discard otherwise valid OCR results.
14. Keep the triggering app separate from text ownership. Label ambiguous sources uncertain instead of assigning them to the underlying app.
15. Record each mask's display coordinates and the reason `Keyboard excluded.` with capture and OCR metadata.
16. Preserve mask metadata in saved runs and ZIP exports, including masks recorded by earlier app versions.
17. Keep screenshot images in device memory. Save and export OCR text and event records without screenshot image files.
18. Do not traverse fields to find sensitive content. The former 1,000-node privacy scan is removed without a replacement scan.
19. Keep consent, run controls, current-request checks, display availability checks, and capture timing. Android capture restrictions still apply.
20. Update this file whenever masking behavior changes. Keep each rule explicit, numbered, and consistent with the capture code.
