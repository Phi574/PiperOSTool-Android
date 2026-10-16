# Android signing and v3 key rotation

Release signing uses a new PiperOS certificate with a non-default serial. The private PKCS#12 keystore and its local password file live under `%USERPROFILE%\.android\PiperOS\`; they are intentionally excluded from Git. Back up both securely. Anyone who loses the keystore cannot sign future updates with this certificate.

`app/signing/piperos-signing-lineage.bin` is the public proof-of-rotation from the previous Android debug signing certificate to the new PiperOS release certificate. It is required for Android to accept updates from installations signed with the previous certificate. Keep this lineage file with the project for every future release.

When the local signing properties exist, both local debug builds and release builds use the new PiperOS certificate and APK Signature Scheme v3. CI without those local secrets continues to use the Android debug key. To produce a distributable APK carrying the rotation lineage:

```powershell
.\gradlew.bat assembleRelease
.\scripts\sign-apk-with-lineage.ps1 -ApkPath .\app\build\outputs\apk\release\app-release.apk
```

The script writes `app-release-rotated.apk` beside the Gradle output. Verify a release with Android SDK Build Tools before publishing:

```powershell
apksigner verify --verbose --print-certs .\app\build\outputs\apk\release\app-release-rotated.apk
```

Firebase Authentication and any Google API configuration that checks signing fingerprints must include the new certificate's SHA-1 and SHA-256 fingerprints. The debug build and CI debug artifact continue using the standard Android debug key; use the rotated release APK for distribution.
