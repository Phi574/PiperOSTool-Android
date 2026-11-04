# PiperOS Browser start-page configuration

The Browser reads browser-start.json from this GitHub Pages site and keeps the last valid response on-device. The app also packages this file as its offline fallback. Update the JSON, increase configVersion, and push to master; the Pages workflow publishes it without rebuilding the APK.

The first pilot supports a column screen with:

- title, subtitle, searchHint, and privacyNote text.
- shortcutGrid children with up to six title, badge, and url items.
- announcementBanner children with visible, title, message, buttonText, and url.

The app validates the schema, limits the response size, and only accepts HTTPS destinations without embedded credentials. URL fields are browser destinations and open inside PiperOS Browser. Remote JSON cannot execute code or name arbitrary Android activities. Unknown components are ignored; an invalid document falls back to the last valid configuration, then to the bundled JSON.

Config endpoint: https://phi574.github.io/PiperOSTool-Android/config/browser-start.json
