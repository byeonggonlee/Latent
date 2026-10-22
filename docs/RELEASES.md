# Builds and releases

How the app is built on GitHub, which builds there are, and how a phone gets them. Decided 2026-10-17.

## Channels

| Channel | When | What it does | Kept |
|---|---|---|---|
| **CI** (`build.yml`) | every push to `main`, every pull request, "Run workflow" | tests, lint, release APK as an artifact | artifact 14 days, reports 7 days |
| **Nightly** (`nightly.yml`) | 03:00 in Korea (18:00 UTC), when `main` has commits the last nightly lacks; "Run workflow" (with *force*) at any time | tests, lint, signed release APK, published as a **pre-release** `nightly-YYYYMMDD-<build>` with the commits since the last one as its notes | the last seven releases; older ones and their tags are deleted |
| **Stable** (`release.yml`) | a tag `vX.Y.Z` (or `vX.Y.Z-rc.1`, a pre-release) that the owner pushes on a commit of `main` | tests, lint, signed release APK with its checksum, published as a release with the version's section of `CHANGELOG.md` as its notes | for good |

A nightly is for the phones: open the release page in the phone's browser (no login needed), download the APK,
install it over the one before. The owner can put one out at any time with *Run workflow*; the schedule only
keeps it from piling up releases with every push.

## Versions

- `versionName`: `<base>-nightly.<date in Korea>` for a nightly, `<base>` itself for a stable release; the base
  is `latent.versionBase` in `gradle.properties`, the name of the next stable release.
- `versionCode`: the number of commits in `main` (`git rev-list --count HEAD`). It only goes up, and nightly and
  stable builds share the one line. A build on the owner's computer is version 1 of the base, as before.
- The workflow passes them as `-PlatentVersionCode` and `-PlatentVersionName`.

## File names

`latent-<versionName>-<versionCode>.apk`, only letters, digits, dots and dashes, so that the name survives a
download link and a file manager:

| Build | Name |
|---|---|
| CI, every push | `latent-0.1.0-ci.8c34842-4321-arm64-v8a.apk` (the commit) |
| Nightly | `latent-0.1.0-nightly.20261017-4321-arm64-v8a.apk`, with the same name and `.sha256` beside it |
| Stable | `latent-0.1.0-4321.apk`, with `latent-0.1.0-4321.apk.sha256` beside it (an `.aab` for Play comes with the store, not before 1.0.0) |
| A build on the owner's computer | Gradle's own `app-release.apk` / `app-debug.apk`, which scripts and docs refer to |

The number at the end is the commit count, so a name sorts in the order of the builds, and the commit it came
from is the one in the CI name, or in the release notes for a nightly. The version name is also what the About
dialog shows. Every build carries the native code of 64-bit ARM only (`arm64-v8a`, 20 MB instead of 39 MB; every
phone the app is for is that kind), the owner's choice of 2026-10-21; the CI and nightly builds say so at the end of
the name. So does the AAB for Play: phones of another kind (32-bit ARM, x86 Chromebooks) are not offered the app. An
emulator of another kind takes `-PlatentAbis=x86_64`. The checksum is for checking a download: `shasum -a 256 -c <file>.sha256`.

## Signing

A phone only updates an app to a build signed with the same key. The key comes from four repository secrets,
set by the owner under Settings, Secrets and variables, Actions:

| Secret | Contents |
|---|---|
| `SIGNING_KEYSTORE_BASE64` | the key store, `base64 -i <file>` |
| `SIGNING_STORE_PASSWORD` | its password |
| `SIGNING_KEY_ALIAS` | the key's alias |
| `SIGNING_KEY_PASSWORD` | the key's password |

The workflow writes them to `keystore.properties` and the key store file on the runner, which is thrown away
afterwards (`keystore.properties` is ignored by git, which `build.gradle.kts` already reads).

- **Since 2026-10-22** the secrets hold the release key: alias `latent`, `CN=Latent Image, C=KR`, RSA 4096, valid
  for 100 years, made on the owner's computer and kept outside the repository, in a password manager and two
  encrypted copies. Every nightly and stable build is signed with it. A build made on a computer without
  `keystore.properties` is signed with that computer's debug key instead, and neither installs over the other.
- **Without the secrets** the nightly builds, keeps the APK as an artifact (a login is needed to download it) and
  publishes nothing: an APK signed with a key no phone has would update nothing.
- **The release key** was made on 2026-10-22, the day the package name was settled as `com.latentimage.latent` (it
  was `com.byeonggon.latent`). Either makes a new app, so every phone moves once, before `v0.1.0`, taking the
  database along through a backup and its restore (below). Until then the secrets were empty and nightlies published
  nothing. The stable workflow refuses to run without the secrets. How it was made, for the day another is needed:

  ```bash
  keytool -genkeypair -v -keystore latent-release.jks -alias latent -keyalg RSA -keysize 4096 -validity 36500
  keytool -J-Duser.language=en -list -v -keystore latent-release.jks   # note the SHA-256 fingerprint somewhere safe
  base64 -i latent-release.jks | gh secret set SIGNING_KEYSTORE_BASE64 --repo byeonggonlee/Latent
  gh secret set SIGNING_STORE_PASSWORD --repo byeonggonlee/Latent   # pasted at the prompt, out of the shell's history
  ```

  The key store is PKCS12, so the key's password is the store's: `keytool` does not ask for it, and
  `SIGNING_KEY_PASSWORD` is the same. On Linux the third line takes `base64 -w0`. The English locale on the second line gets round the `keytool` of JDK 25,
  whose Korean messages fail while they print a certificate (`IllegalFormatConversionException`).

  Losing the key or its passwords means that no phone can update to any later build; keep the file and both
  passwords in a password manager and in one other place (for example an encrypted backup). `*.jks` and
  `keystore.properties` are ignored by git. If the app ever goes to Play, this is the *upload* key; Google keeps
  the key that signs what people download, which is a different one.

### Reinstalling a phone

Whenever a phone has to take an app it cannot install over the one it has (a new key, a new package name, an older
build), the database goes with the old app; the photos in `DCIM/Latent` and the phone's folders stay. The way across:

1. In the old app: Settings, **Backup and restore**, **Back up…**, and save the file outside the app (Downloads, a
   computer, a cloud folder of one's own). It holds places and the location history: keep it private.
2. Uninstall the old app, install the new one, open it and give it access to the photos and to `DCIM/Latent`, as on
   the first start; choose the phone folders again if they are wanted at once (the restore adds the backup's anyway).
3. Settings, **Backup and restore**, **Restore…**, pick the file, read the summary (photos found, photos not found by
   name, what replaces what), confirm.
4. Online maps stay off until they are turned on again; location recording is started again by hand.

Across the new package name the old app and the new one are two apps that can be installed side by side: install
the new one first, restore into it, and uninstall the old one once the new one shows everything (step 2 without the
uninstall). Meanwhile sync, edit and record places in one of them only. The originals the old app kept of the JPEGs
it edited (`DCIM/Latent/.originals`) are its own, which shared storage shuts to another app's paths; the new one
makes them its own as it lists the library, after which the old one can no longer edit those JPEGs again.

The new app reads the backup of an older one; an older app refuses the backup of a newer one (see
[`BACKUP_FORMAT.md`](BACKUP_FORMAT.md)).

## Making a stable release

1. Everything wanted is in `main` and the CI of its head is green. Run the release build on a phone through
   [`DEVICE_CHECKS.md`](DEVICE_CHECKS.md) (a nightly signed with the release key is the same build).
2. `CHANGELOG.md` has a `## [X.Y.Z] - date` section; write the date in. `latent.versionBase` in `gradle.properties` is `X.Y.Z`
   (the workflow refuses a tag that disagrees, as it refuses a commit that is not on `main`).
   The workflow also refuses a section still marked `unreleased`, a signing key whose alias is `androiddebugkey`, and
   any of the four secrets being empty, before it builds; a failed build leaves its test and lint reports as an artifact.
3. Tag and push: `git tag -a vX.Y.Z -m "Latent X.Y.Z" && git push origin vX.Y.Z`.
4. The workflow publishes the release in about seven minutes. Download the APK on a phone, check the checksum, and
   install it over the nightly before it.
5. Raise `latent.versionBase` to the next release (`X.Y.Z+1`) in a commit of its own, and start the next section of
   `CHANGELOG.md`, so that nightlies carry the new name.

A fix to a release goes into `main` like any change and comes out as the next number; there are no release
branches. A tag that has been pushed is not moved: if the release was wrong, delete the release and the tag, fix
it in `main`, and tag again only if no one has installed it, otherwise issue the next number.

## Things to know

- A build only goes forward: a lower `versionCode` is refused by Android, and the database moves to the newer
  schema, so going back to an older nightly means uninstalling, which loses the database unless it was backed up
  (above); the older app then reads the backup only if its format is not newer than the one it knows.
- Pre-releases are public downloads (the repository is public). That is a choice of the owner (2026-10-17).
- Public repositories use standard GitHub-hosted runners for free; do not use "larger runners", which are paid.
- A run takes about seven minutes. A failing nightly leaves no release behind and is shown red in the Actions
  tab; the day's change gets built the next night, or at once with *Run workflow*.
- Not yet: an AAB for Play, version numbers shown in the app's About dialog beyond the
  name, an update check inside the app.
