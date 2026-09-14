# Validation — 2026-09-14 / v0.2.0

## Passed

- Six automated state/backend tests passed: `node --test tests/interface.test.cjs server/server.test.mjs`.
- Real Chromium browser checks passed: demo, stop, font size, dark mode, mosque mode, transcript, connection dialog, explicit live-audio limitation, no horizontal overflow at 320/412/768 CSS pixels, and no JavaScript page errors.
- Captured and visually inspected the mobile interface. The first inspection revealed missing Urdu glyphs in fallback fonts. Bundling Noto Naskh Arabic resolved them; the updated screenshot was inspected.
- XML resources parsed successfully. Source ZIP integrity checked.
- Gradle 8.9 executed successfully to generate the official Wrapper. A SHA-256 distribution verification value is included.

Browser test reproduction (optional): install Playwright with `npm install --no-save playwright`, install its Chromium using `npx playwright install chromium`, then run `node tests/browser.cjs`. Set CHROME_EXECUTABLE if using a separate Chromium. This is browser QA, not Android WebView/device validation.

## Changes reviewed but not verified on Android

- Stale listening/caption/failure events are gated by a session generation on the UI thread.
- A stale failed session cannot schedule cleanup against a newer session.
- Permission dialogs no longer trigger JavaScript/native cancellation of the pending permission flow.
- Orientation configuration preserves Activity during rotation.
- Reading preferences persist, while credentials and transcripts do not.
- Application icon and v0.2.0 version metadata added.

## Build attempt and remaining limits

`gradle assembleDebug` was attempted. It failed during resolution of `com.android.application` 8.7.3 from the configured repositories in this environment, before source compilation. The Android command-line-tools download also timed out after receiving only part of its archive. No APK was produced and Java/Android source has not been compiled.

The bundled GitHub Actions workflow is an alternate reproducible build route; it has not been executed. No matching Bayan repository was found in the connected repositories at the time of delivery.

No Azure resource or access credentials were supplied. Real speech translation, cloud token refresh, long sermons, microphone permissions, background behavior, rotation, and physical-device compatibility remain untested. Token broker tests mock Azure responses. No APK signing for distribution or Play Store release validation was performed. This remains a prototype pending device and Arabic/Urdu subject-matter validation.
