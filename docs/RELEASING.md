# Releasing

A release is a version tag. Pushing `v1.2.3` makes GitHub Actions run the tests, build the two APKs, sign them and publish them as a GitHub Release:

| File | For |
|---|---|
| `lookee-1.2.3-armeabi-v7a.apk` | The Mi Box (MIBOX3) and other 32-bit TV boxes |
| `lookee-1.2.3-arm64-v8a.apk` | 64-bit TV boxes |
| `SHA256SUMS` | Checksums of both |

Native libraries are stored uncompressed and read straight from the APK, so the download is larger (about 68 MB) but the box keeps no extracted copy on its internal storage.

## The signing key

Android installs an update only if it is signed with the same key as the installed app. lookee's release key is the key the Mi Box's install has carried from the start (it began as this Mac's debug key), kept as its own keystore **outside the repo**:

```
~/.lookee/lookee-release.jks     alias androiddebugkey
SHA-256 6F:F6:07:3A:DD:A6:B4:B1:07:78:2E:64:86:B0:13:96:15:87:A1:3F:57:9F:93:35:3C:57:17:8B:AE:85:65:11
```

**Back it up** (a password manager attachment is fine). Losing it means every device needs one uninstall, which wipes its library, settings and progress.

### One-time setup

1. Give the keystore its own password (it starts as `android`):

   ```bash
   keytool -storepasswd -keystore ~/.lookee/lookee-release.jks
   ```

2. Put the password in the Mac's Keychain, so `make release` finds it:

   ```bash
   security add-generic-password -a "$USER" -s lookee-release -w
   ```

3. Give GitHub the keystore and the password as repository secrets (both commands prompt or read from the file; nothing is printed):

   ```bash
   base64 -i ~/.lookee/lookee-release.jks | gh secret set LOOKEE_KEYSTORE_BASE64
   gh secret set LOOKEE_KEYSTORE_PASSWORD
   ```

## Cutting a release

```bash
git tag v0.6.0
git push origin v0.6.0
```

Watch it with `gh run watch`; the release appears under GitHub → Releases. Versions: `v1.2.3` becomes versionName `1.2.3` and versionCode `10203`, times ten plus 1 (armeabi-v7a) or 2 (arm64-v8a). Local builds take the latest tag's version, so they install over the release on the box.

## Building a signed release on the Mac

```bash
make release                     # password from LOOKEE_KEYSTORE_PASSWORD or the Keychain
make install-release MIBOX_IP=…  # installs the armeabi-v7a APK and precompiles it
```

Without the keystore or its password, `./gradlew assembleRelease` still works but signs with the debug key, for contributors who only want to try a release build.

## Installing a release without a computer

On the box, the free *Downloader* app (by AFTVnews) can open the release page and install `lookee-…-armeabi-v7a.apk` directly. Updates install over the previous version and keep everything.
