# PiperOS Browser start-page configuration

The Browser reads browser-start.json from this GitHub Pages site and keeps the last valid response on-device. The app also packages this file as its offline fallback. Update the JSON, increase configVersion, and push to master; the Pages workflow publishes it without rebuilding the APK.

The first pilot supports a column start screen and a remote-configured Browser options menu:

- Start-page title, subtitle, searchHint, and privacyNote text.
- shortcutGrid children with up to six title, badge, and url items.
- announcementBanner children with visible, title, message, buttonText, and url.
- `browserOptions.title` and an ordered `items` list. Each item can set its title, subtitle, icon key, visibility, enabled state, and a supported dynamic summary.
- Browser option action IDs are allowlisted (`privacy_report`, `developer_tools`, `browser_theme`, `search_engine`, `site_data`, `desktop_mode`, `history`, `downloads`, `extensions`, `user_agent`, `notifications`, `about`). Actions still call the corresponding native app implementation; JSON cannot inject code or arbitrary intents.
- Icon keys and dynamic summary keys are allowlisted by the app. Unknown menu items are ignored.

The app validates schema version 2, limits the response size, and only accepts HTTPS destinations without embedded credentials. URL fields are browser destinations and open inside PiperOS Browser. Remote JSON cannot execute code or name arbitrary Android activities. Unknown screen components are ignored; invalid documents fall back to the last valid configuration, then to the bundled JSON.

Increase `configVersion` when publishing a change. The app rejects older config versions, caches the last valid document, and attempts a refresh no more than once every five minutes.

Config endpoint: https://phi574.github.io/PiperOSTool-Android/config/browser-start.json
