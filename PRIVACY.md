# Privacy

PiperOS Tool is a beta Android utility. This document describes the behavior
of the source currently published in this repository; a distributor must
update it when changing backend services or data collection.

## Account data

Login and registration use Firebase Authentication. Account-related settings
may be synchronized through Firebase services configured by the application.
Firebase is a third-party service and processes requests according to the
Firebase project configuration and Google's applicable terms.

## Data stored on the device

- Browser tabs, history, preferences, cookies and normal browsing sessions.
- Media queue, selected folders and playback preferences.
- Terminal command history and active local shell session metadata.
- PiperOS settings, lock preferences and selected background.

Incognito browser data is intended to be deleted when its tab or application
session closes. Websites can still observe network addresses and browser
properties; incognito mode is not anonymity or a VPN.

## Optional device access

Depending on the feature the user opens, PiperOS may request:

- audio, video and file access for PiperOS Media and downloads;
- installed-app visibility for application management;
- notification access for notification-related tools;
- usage access for device activity information;
- foreground service and notification permissions for downloads, playback
  and user-started terminal sessions;
- elevated settings permissions for advanced device tools.

PiperOS must not silently grant itself restricted permissions. Android system
settings remain the source of truth and the user can revoke access there.

## Browser traffic

Web pages are loaded directly through Android System WebView and the selected
network or VPN. Visited websites may collect data under their own policies.
Imported browser extensions can read or modify pages matching their declared
rules; only import files from trusted sources.

## Contact

Privacy issues can be reported through the repository issue tracker. Do not
include passwords, tokens, private notifications or personal files in a
public issue.
