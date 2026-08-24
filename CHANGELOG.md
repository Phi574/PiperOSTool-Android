# Changelog

## 3.2.3.beta

- Added PiperOS View Remote for Android screen sharing and remote control on a
  local network, with nearby discovery, QR pairing and six-digit pairing code.
- Added sharing consent, selectable resolution/FPS, orientation-aware viewing,
  full-screen playback and a clean disconnect flow.
- Added Apple Screen Mirroring receiver for iPhone, iPad and macOS through
  AirPlay/RAOP discovery. The receiver advertises through mDNS as
  `PiperOS View Remote` and runs as an Android foreground service.
- Added View Remote information to the Info section.

## 2.5.8.beta

- Added PiperOS Fake Map GPS to the Beta page.
- Added fixed mock locations and simulated routes with walking, motorbike,
  car and plane presets.
- Added adjustable speed, natural stops, looping, pause/resume and a
  foreground notification.
- Added OpenStreetMap rendering and OSRM road geometry with a direct-route
  fallback when routing is unavailable.
- Added Developer Options guidance and mock-location provider validation.

## 2.5.1.beta

- Added a visible Android Shell/Linux Runtime status panel to PiperOS Terminal.
- Added automatic runtime detection for `$PREFIX/bin/bash` and `$PREFIX/bin/sh`.
- Fixed terminal tab numbering after closing tabs or closing the final tab.
- Added the active terminal mode and app version to the foreground notification.

## 2.5.0.beta

- Added PiperOS Browser with persistent tabs, incognito tabs, downloads,
  history, User-Agent profiles and extension import.
- Added PiperOS Media with audio/video scanning, folder filters, search,
  sorting, playback queue, background playback and Picture-in-Picture.
- Added the first PiperOS Terminal activity with local shell sessions and a
  foreground session service.
- Updated authentication, Settings, Device Info and offline behavior.
- Added Android 16 progress notification support with a standard notification
  fallback for older Android versions.
- Moved the full Linux runtime build to
  [Piperos_termux](https://github.com/Phi574/Piperos_termux).
