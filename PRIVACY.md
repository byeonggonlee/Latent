# Privacy policy

*Latent, effective October 15, 2026*

Latent keeps everything on your phone. It has no account, no ads and no analytics, and it does not use
the internet unless you turn on online maps, the one feature that needs it. With online maps off, which is
how the app starts out, it makes no network request at all.

## What the app works with

- **Photos and videos of other apps** in the folders of the phone that you choose, such as the phone's
  camera: they are listed in the library where they are, never changed or copied. What you do to them is
  kept as settings in the app's database; saving a copy as a JPEG makes a new file in `DCIM/Latent`.
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
- **Automatic masks** (subject and background) are made on the phone by a model inside the app; the photo
  is not sent anywhere for it.

## Permissions

| Permission | Why |
|---|---|
| Photos and videos (read) | To show and edit the library, including photos put there by other apps. |
| Access to the `DCIM/Latent` folder, which you choose | To keep files next to raw photos, such as their location sidecars. |
| Location while the app is in use, and media location | To record where you are while recording is on, and to read the locations in photos. |
| Notifications | To show that location recording or a sync is running, or that recording stopped. |
| USB | To read the camera. |
| Internet | Only for online maps, and only after you turn them on: to download map pictures from OpenStreetMap. |
| Foreground services, starting at boot | To keep recording and syncing running while the screen is off, and to tell you after a restart that recording stopped. |

## Online maps

Online maps are off until you turn them on, which the app asks you to confirm. While the map is open,
the app downloads map pictures (tiles) from OpenStreetMap's servers. Like any web request, that tells the
server your IP address and which map areas you look at. Nothing else is sent: not your photos, their names,
or a list of where they were taken. Map pictures are kept on the phone for a week to save requests. You can
turn online maps off again from the map's menu. Map data © OpenStreetMap contributors.

## Backups

The app's database, with the recorded locations, is kept out of Android's cloud backup. When you move to a
new phone with the system's own transfer, started by you, it comes along.

**Backup and restore** in the settings writes, when you ask for it, one file with what lives only in the app: your
albums, ratings and flags, edits and their versions, your own filters, imported LUTs, the outlines of detected masks,
which camera files were imported, a few settings, the places of your photos and the location history (recorded by the
app and imported from Google Timeline). Your photos and videos themselves are not in it. The file goes only where you
choose in the system's file dialog, and is read only when you pick it to restore. The app never uploads it or sends it
anywhere; you are the only one who handles it. Whoever has the file can see where your photos were taken and where you
have been, so keep it private. If you choose a folder of a cloud service in the file dialog, that service keeps the
file under its own terms. Restoring never turns online maps on.

## Sharing

Photos leave the app only when you share them, to the app you pick, or when another app on the phone
reads them from `DCIM/Latent`.

## Deleting

Photos you delete go to the app's trash for 30 days, then they are deleted. Uninstalling the app
deletes its database, with the recorded locations; the photos in `DCIM/Latent` stay until you delete
them, and so does a backup file you made, wherever you saved it.

## Contact

Questions and requests: [github.com/byeonggonlee/Latent/issues](https://github.com/byeonggonlee/Latent/issues).
