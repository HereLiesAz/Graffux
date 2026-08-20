# Privacy Policy

**Effective Date:** July 20, 2026

Graffux (the "App") is committed to protecting your privacy. This Privacy Policy describes how we handle information in our mobile application.

## 1. Information We Do Not Collect

Graffux is a "local-first" application. We do not require you to create an account, and we do not collect personal information such as your name, email address, phone number, or physical location.

## 2. Data Storage

All your creative work—including sketches, photos, and project layers—is stored locally on your device. We do not have access to your creative content. It is your responsibility to back up your data using your device's backup services (e.g., Google Drive, iCloud, or manual file transfers).

## 3. Crash Reporting

If the App crashes, it writes a technical report to a file in its own private cache directory
on your device.

- **What is captured:** the crash's stack trace, your device model and Android version, the
  App's version, and up to the last 1000 lines of the App's own log output at the time of the
  crash. Known-sensitive patterns (GPS coordinates, auth tokens/bearer headers, email
  addresses) are redacted from this report before it's saved.
- **Where it goes: nowhere, automatically.** The report stays in the App's local cache. The
  App does not transmit it anywhere, to us or anyone else, on its own.

## 4. Permissions

The App may request the following permissions to function correctly:

- **Storage/Media:** To save and load your images and projects.
- **Camera:** (If applicable) To capture photos for editing within the App.

We only use these permissions to provide the App's core functionality.

## 5. Third-Party Services

The App does not contain third-party advertising or analytics tracking. It does connect to a
small number of third-party services, but only when you explicitly choose to use the feature
that needs them:

- **Figma import.** If you connect a Figma personal access token to import frames from a Figma
  file, that token is stored on your device (encrypted) and sent to Figma's own API, along with
  the file link/key you provide, to load and render the frames you select. This only happens if
  you enter a token and use the Figma import feature — the App makes no contact with Figma
  otherwise. Figma's own privacy policy governs what happens to that request on their end.
- **Extensions (azphalt).** Installing a filter, LUT, or code extension — from a separate store
  app or an `azphalt://` link, always with an explicit confirmation before install — fetches
  that extension's package from wherever it's hosted. Code extensions then run in a sandboxed
  environment with no capability (canvas access, colour, timing) they weren't granted, and no
  network access of their own. See `spec/package-format.md` for the sandbox's exact guarantees.

## 6. Children's Privacy

Because we do not collect personal information, our App is safe for users of all ages. We do not knowingly collect any data from children under the age of 13.

## 7. Changes to This Policy

We may update our Privacy Policy from time to time. We will notify you of any changes by posting the new Privacy Policy on this page.

## 8. Contact Us

If you have any questions about this Privacy Policy, please contact the developer via the project's repository.
