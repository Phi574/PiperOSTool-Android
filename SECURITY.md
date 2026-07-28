# Security policy

## Supported version

Only the latest commit on `master` and the newest published PiperOS beta are
supported.

## Reporting a vulnerability

Do not publish credentials, private notifications, browsing data or a working
exploit in a public issue. Contact the repository owner privately through the
GitHub profile associated with this project and include:

- affected version and Android version;
- reproducible steps;
- security impact;
- a minimal proof of concept without personal data.

## Sensitive configuration

- Never commit release keystores or signing passwords.
- Treat Firebase rules and backend authorization as security boundaries.
- A Firebase API key in `google-services.json` is an identifier, not a
  substitute for proper Firebase Security Rules.
- PiperOS Termux runtime manifests must be signed and archive hashes verified
  before installation.
