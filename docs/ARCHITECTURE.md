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
| Terminal | `PiperTerminalActivity`, `PiperTerminalService`, `TerminalSessionManager` |
| Connectivity | `NetworkAccess` |

## Terminal runtime boundary

The APK currently exposes a local Android shell. The complete `$PREFIX`
environment is built in the companion
[Piperos_termux](https://github.com/Phi574/Piperos_termux) repository.

The separation keeps generated bootstrap archives and package build
infrastructure out of the Android source tree. Runtime releases must match:

```text
application id: com.piper.os.tool
rootfs:         /data/data/com.piper.os.tool/files
home:           /data/data/com.piper.os.tool/files/home
prefix:         /data/data/com.piper.os.tool/files/usr
```

The Android installer will verify a signed manifest, ABI, byte size and
SHA-256 before extracting a runtime into private app storage.

After runtime verification, the installer provisions the pinned PiperOS APT
public key and this repository source:

```text
deb [signed-by=/data/data/com.piper.os.tool/files/usr/etc/apt/keyrings/piperos-archive-keyring.gpg] https://phi574.github.io/Piperos_termux stable main
```

The package repository is built by `Phi574/Piperos_termux` for `aarch64`,
`arm` and `x86_64`. The app must never enable an unsigned source or mix
official Termux binaries into the PiperOS `$PREFIX`.
