# Privacy Policy

**Effective Date:** October 23, 2026

Graffux (the "App") is committed to protecting your privacy. This Privacy Policy describes how we handle information in our mobile application.

## 1. Information We Do Not Collect

Graffux is a "local-first" application. We do not require you to create an account, and we do not collect personal information such as your name, email address, phone number, or physical location.

## 2. Data Storage

All your creative work—including sketches, photos, and project layers—is stored locally on your device. We do not have access to your creative content. It is your responsibility to back up your data using your device's backup services (e.g., Google Drive, iCloud, or manual file transfers).

## 3. Crash Reports (Opt-In)

If the App crashes or freezes, it writes a technical report to its own private cache on your device.

- **What is captured:** the error and stack trace (Java and native), your device model and Android
  version, the App's version, Android's own exit record for the crash or freeze (including the
  freeze trace), a short list of recent App actions, and up to the last 1000 lines of the App's
  own log output.
- **Where it goes:** nowhere unless you switch on Settings → Privacy → "Send crash reports" (off by
  default) **and** connect the reports token under Settings → Developer. Then reports of crashes that
  happen after you switched it on are posted as **public** issues on
  <https://github.com/HereLiesAz/Graffux>, where anyone can read them. Reports from before you
  switched it on are never sent.
- **Redaction:** before a report is saved or sent, the App removes GPS coordinates, auth tokens
  and bearer headers (including GitHub and Figma tokens), email addresses, file and content
  addresses and private storage paths. Redaction of free-form text cannot be perfect.
- **Stopping:** switch the setting off. To have a posted report removed, contact us (section 9).

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

The App requests Internet access only, for the features in sections 3, 4 and 6. It does not request
camera, location, contacts or broad storage permissions; images you add are opened through
Android's own file picker or share sheet, one file at a time.

## 6. Third-Party Services

The App contains no advertising. It connects to third-party services only as follows:

- **Usage analytics (opt-in).** Google Firebase Analytics is built in but **off by default**; it
  collects nothing until you switch on Settings → Privacy → "Usage analytics". When on, Firebase
  records anonymous usage events (screens opened, App version, device model, approximate country)
  under Google's privacy policy. No drawings, names or location are sent. The advertising ID is
  not used.
- **Updates.** The App checks for a newer version where it was installed from: Google Play's update
  service for a Play install, or the public GitHub releases page of HereLiesAz/Graffux otherwise.
  The request carries no personal data.
- **Figma import.** If you connect a Figma personal access token to import frames from a Figma
  file, that token is stored on your device (encrypted) and sent to Figma's own API, along with
  the file link/key you provide. This only happens if you enter a token and use Figma import.
- **Extensions (azphalt store).** Opening the store, searching it, or installing an extension
  sends the store (azphalt.store, or a store app you choose) the list of extensions you have
  installed and their versions, so it can show what you own and which updates exist. Installing
  fetches the extension's package from where it is hosted. Installs start only from your tap on
  Install in the store window, or after you confirm a prompt for a link or a store app's result.
  Code extensions run sandboxed, with no network or file access of their own, limited to the
  capabilities their package declares. See
  `spec/package-format.md` for the sandbox's exact guarantees.

## 7. Children's Privacy

The App does not ask for personal information; analytics, crash reports and training data are all off by default. We do not knowingly collect any data from children under the age of 13.

## 8. Changes to This Policy

We may update our Privacy Policy from time to time. We will notify you of any changes by posting the new Privacy Policy on this page.

## 9. Contact Us

If you have any questions about this Privacy Policy, please contact the developer via the project's repository.
