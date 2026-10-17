# Builds and releases

How the app is built on GitHub, which builds there are, and how a phone gets them. Decided 2026-10-17.

## Channels

| Channel | When | What it does | Kept |
|---|---|---|---|
| **CI** (`build.yml`) | every push to `main`, every pull request, "Run workflow" | tests, lint, release APK as an artifact | artifact 14 days, reports 7 days |
| **Nightly** (`nightly.yml`) | 03:00 in Korea (18:00 UTC), when `main` has commits the last nightly lacks; "Run workflow" (with *force*) at any time | tests, lint, signed release APK, published as a **pre-release** `nightly-YYYYMMDD-<build>` with the commits since the last one as its notes | the last seven releases; older ones and their tags are deleted |
| **Stable** (not built yet) | a tag `v0.x.y` that the owner puts on a commit | signed APK and the AAB for Play, published as a release | for good |

A nightly is for the phones: open the release page in the phone's browser (no login needed), download the APK,
install it over the one before. The owner can put one out at any time with *Run workflow*; the schedule only
keeps it from piling up releases with every push.

## Versions

- `versionName`: `<base>-nightly.<date in Korea>` for a nightly, `<base>` itself for a stable release; the base
  is `latent.versionBase` in `gradle.properties`, the name of the next stable release.
- `versionCode`: the number of commits in `main` (`git rev-list --count HEAD`). It only goes up, and nightly and
  stable builds share the one line. A build on the owner's computer is version 1 of the base, as before.
- The workflow passes them as `-PlatentVersionCode` and `-PlatentVersionName`.

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

- **For now** the key is the one in `~/.android/debug.keystore` of the owner's computer (alias `androiddebugkey`,
  both passwords `android`): nightlies then update over the builds made on that computer, and nothing needs to
  be uninstalled. It is a debug key, of no value to anyone, and **not** the key of a Play release.
- **Without the secrets** the nightly builds, keeps the APK as an artifact (a login is needed to download it) and
  publishes nothing: an APK signed with a key no phone has would update nothing.
- **At the first real release** a release key is made with `keytool`, kept in two places outside the repository
  and put in the secrets in the same four; every phone then has to be reinstalled once, which takes the database
  along unless the backup and restore (not built yet) is there. This happens at the same time as the package
  name is settled (see the roadmap), as that makes a new app anyway. For Play the key is the *upload* key;
  Google keeps the key that signs what people download, which is a different one from any here.

## Things to know

- A build only goes forward: a lower `versionCode` is refused by Android, and the database moves to the newer
  schema, so going back to an older nightly means uninstalling, which loses the database.
- Pre-releases are public downloads (the repository is public). That is a choice of the owner (2026-10-17).
- Public repositories use standard GitHub-hosted runners for free; do not use "larger runners", which are paid.
- A run takes about seven minutes. A failing nightly leaves no release behind and is shown red in the Actions
  tab; the day's change gets built the next night, or at once with *Run workflow*.
- Not yet: the stable workflow (tag, AAB, release), version numbers shown in the app's About dialog beyond the
  name, an update check inside the app.
