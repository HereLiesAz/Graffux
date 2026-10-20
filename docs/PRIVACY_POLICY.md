# Privacy Policy

**Effective Date:** October 20, 2026

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

## 4. Stroke-Model Training Data (Opt-In)

Graffux is building a model that predicts where a stroke is going. Contributing training data is
**off by default** and only starts when you switch on Settings → "Contribute strokes to model
training". Nothing is recorded or uploaded before then, and switching it off stops both.

- **What is recorded while it is on:** for each stroke, the touch path (positions, pressure,
  contact size, tilt, timing), the phone's motion-sensor readings around it (accelerometer,
  gyroscope, magnetometer, rotation), your device manufacturer and model, screen size and refresh
  rate, the App's version, the time the session started, the brush settings in use, and the name of
  the touch controller. The separate "Raw touch heatmap" setting (rooted devices only, also off by
  default) adds the touch controller's raw sensor image.
- **What is not recorded:** your name, an account, your location, or any image, layer, photo or
  project content. A stroke is a path of points, so if you write or sign something while this is
  on, that path is part of what is recorded. Leave the setting off when you do.
- **Where it goes:** finished session files are sent over the internet to a small relay run by
  the developer, which saves them to the **public** `stroke-data` branch of
  <https://github.com/HereLiesAz/Graffux>. Anyone can read and download that data, and a copy is
  kept in a private dataset used for training. The App keeps no credential for this; the relay
  holds the key.
- **Removal:** files already uploaded are not tied to you, so we cannot tell which are yours.
  Contact us (section 9) with the device model and approximate date and we will remove matching
  files from the branch, though copies that others have already downloaded cannot be recalled.

## 5. Permissions

The App may request the following permissions to function correctly:

- **Storage/Media:** To save and load your images and projects.
- **Camera:** (If applicable) To capture photos for editing within the App.

We only use these permissions to provide the App's core functionality.

## 6. Third-Party Services

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

## 7. Children's Privacy

Because we do not collect personal information, our App is safe for users of all ages. We do not knowingly collect any data from children under the age of 13.

## 8. Changes to This Policy

We may update our Privacy Policy from time to time. We will notify you of any changes by posting the new Privacy Policy on this page.

## 9. Contact Us

If you have any questions about this Privacy Policy, please contact the developer via the project's repository.
