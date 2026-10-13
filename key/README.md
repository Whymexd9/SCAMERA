# Signing key

The APK is signed with the SCAMERA release key (`CN=SCAMERA`, alias `scamera`, RSA 4096, PKCS12). The repository is
public, so the key is **not** in it:

- local builds (`app/build.gradle`, `tools/package_scam_neural.py`) read `SCAMERA-PC/signing/signing.properties`
  next to the project (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`), or the environment variables
  `SCAMERA_KEYSTORE`, `SCAMERA_KEYSTORE_PASSWORD`, `SCAMERA_KEY_ALIAS`;
- GitHub Actions reads the secrets `SCAMERA_SIGNING_KEYSTORE` (the .jks, base64) and `SCAMERA_SIGNING_PASSWORD`.

Keep a backup of the key: an APK signed with another key does not install over the installed app.
Certificate SHA-256: `7B:12:87:5E:81:72:31:1F:EC:F2:17:E0:AA:4B:4D:86:D0:90:AE:01:C5:09:64:04:7D:2B:AE:10:4D:91:E9:D5`.

Until build 0.98 (October 2026) the APK was signed with the leaked PhotonCamera key (`CN=PhotonCamera developers`):
the first build with the SCAMERA key needs the old app uninstalled once.
