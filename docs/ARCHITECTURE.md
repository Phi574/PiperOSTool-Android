# Architecture

## Android application

The application is a single Android `app` module using Kotlin, XML Views,
Firebase, AndroidX WebKit and AndroidX Media3.

Primary feature boundaries:

| Area | Main classes |
| --- | --- |
| Navigation | `HomeActivity`, `homeFragment`, `BetaFragment`, `SettingFragment` |
| Browser | `PiperBrowserActivity`, `BrowserSessionStore`, `BrowserExtensionStore`, `BrowserDownloadService` |
| Media | `PiperMediaActivity`, `PiperPlaybackService`, `PiperMediaAsset` |
| Connectivity | `NetworkAccess` |
