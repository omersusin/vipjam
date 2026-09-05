# Release signing

The `release` CI job builds a signed release APK on every `v*` tag push
(and on manual dispatch). Signing is driven by environment variables —
no keys live in the repo.

## Production key (one-time setup)

```sh
keytool -genkeypair -keystore vipjam-release.keystore \
  -alias vipjam -keyalg RSA -keysize 4096 -validity 10950 \
  -storepass <STOREPASS> -keypass <KEYPASS> \
  -dname "CN=VipJam, OU=VipJam, O=VipJam"
base64 -w0 vipjam-release.keystore  # Linux; macOS: base64 -i
```

Add four repository secrets (`Settings → Secrets → Actions`):

| Secret             | Value                                     |
|--------------------|-------------------------------------------|
| `KEYSTORE_BASE64`  | base64 of the keystore file               |
| `KEYSTORE_PASSWORD`| store password                            |
| `KEY_ALIAS`        | key alias (`vipjam`)                      |
| `KEY_PASSWORD`     | key password                              |

Then push a tag: `git tag v0.1.0 && git push origin v0.1.0`.
The APK is named `app-release.apk` with `versionName` taken from the tag.

## Without secrets

The job falls back to an ephemeral test key generated per run and
uploads a `TEST-SIGNED.txt` marker next to the APK. Test-signed builds
install and run fine but must never be published (upgrade path breaks
when the key changes).
