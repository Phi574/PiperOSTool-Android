# Contributing

## Before opening a pull request

1. Keep changes focused and explain user-visible behavior.
2. Preserve copyright and third-party license notices.
3. Do not commit IDE files, build outputs, APKs, credentials or keystores.
4. Run:

```powershell
.\gradlew.bat assembleDebug lintDebug testDebugUnitTest
```

5. Test affected screens on at least one supported Android device or emulator.
6. Document new permissions, background services and data collection.

Contributions are accepted under GPL-3.0-only unless a file carries a
compatible upstream license that must be preserved.
