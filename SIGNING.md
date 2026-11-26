# EinthusanTV app signing and safe updates

Android only accepts an APK as an in-place update when it has the same application ID and is signed with the same signing certificate as the installed app. Keep the release keystore and its passwords backed up securely. Never commit the keystore or passwords to this repository.

## Create the release keystore on Windows

Run this from a terminal with JDK 17 installed:

```powershell
keytool -genkeypair -v -keystore einthusan-tv-release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias einthusan-tv
```

Choose and safely store the keystore password and key password. Keep the `einthusan-tv-release.jks` file in a secure backup location.

Convert the keystore to Base64 for the GitHub secret:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes(".\einthusan-tv-release.jks")) | Set-Clipboard
```

## Add GitHub Actions secrets

In the repository, open **Settings → Secrets and variables → Actions → New repository secret** and add:

- `ANDROID_KEYSTORE_BASE64` — paste the Base64 value copied above
- `ANDROID_KEYSTORE_PASSWORD` — keystore password
- `ANDROID_KEY_ALIAS` — `einthusan-tv`
- `ANDROID_KEY_PASSWORD` — key password

After all four secrets are present, each GitHub Actions build publishes a release APK signed with this stable key. Future versions can then install over that signed app while retaining app-private data.

## One-time migration from earlier previews

Earlier preview APKs were built with GitHub runner-generated debug keys. Those keys are not stable between builds, and cannot be used to verify updates. The first stable-key release therefore cannot update an earlier preview in place. Uninstall the old preview and install the first stable-key release once. This removes that app installation's local data. After this one-time migration, keep the keystore unchanged for every future release.

The current sign-in screen is only a placeholder; no Einthusan credentials are stored by this app yet.

## Update behaviour

The app checks the public GitHub Releases list on launch, offers Update or Cancel when it finds a newer preview, downloads the APK into its private cache, and hands installation to Android/Fire TV's package installer. The platform still requires the user to approve installation. The app removes the staged APK after Android reports that the package has been replaced.
