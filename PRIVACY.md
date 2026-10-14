# Privacy policy

*Latent, effective October 15, 2026*

Latent keeps everything on your phone. It has no account, no ads and no analytics, and it does not
have the permission to use the internet: nothing it reads or makes can leave the phone through it.

## What the app works with

- **Photos and videos** that it copies from your camera over USB, into `DCIM/Latent` on the phone's
  storage, and the edits you make to them. They are ordinary files that gallery and backup apps on
  the phone can see.
- **Your location**, while location recording is on: the phone's position every few minutes, kept
  in the app's own database, to tell where each photo was taken from the time it was taken. Recording only
  runs while you have switched it on, and shows a notification for as long as it does.
- **A Google Timeline export** that you choose to import: the file is read on the phone, and the
  places in it are kept in the same database for the same purpose.
- **The locations it adds to photos**: written into JPEG files (EXIF) and into sidecar files next to
  raw files (XMP), so that other apps can show where a photo was taken. A shared photo carries its
  location along.

## Permissions

| Permission | Why |
|---|---|
| Photos and videos (read) | To show and edit the library, including photos put there by other apps. |
| Access to the `DCIM/Latent` folder, which you choose | To keep files next to raw photos, such as their location sidecars. |
| Location while the app is in use, and media location | To record where you are while recording is on, and to read the locations in photos. |
| Notifications | To show that location recording or a sync is running, or that recording stopped. |
| USB | To read the camera. |
| Foreground services, starting at boot | To keep recording and syncing running while the screen is off, and to tell you after a restart that recording stopped. |

## Sharing

Photos leave the app only when you share them, to the app you pick, or when another app on the phone
reads them from `DCIM/Latent`.

## Deleting

Photos you delete go to the app's trash for 30 days, then they are deleted. Uninstalling the app
deletes its database, with the recorded locations; the photos in `DCIM/Latent` stay until you delete
them.

## Contact

Questions and requests: [github.com/byeonggonlee/Latent/issues](https://github.com/byeonggonlee/Latent/issues).
