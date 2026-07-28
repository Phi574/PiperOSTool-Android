# Changelog

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
