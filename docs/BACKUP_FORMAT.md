# Backup format

What a backup file holds, how it is laid out, and what a restore does with it. Decided 2026-10-20. The code is in
`app/src/main/java/com/byeonggon/latent/backup/`; a change there must be written down here.

Everything the person does in the app lives only in its private database and files: albums, picks and stars, edits and
versions, their own filters, LUTs, detected masks, the location history, which camera files were imported. A
reinstall loses all of it (a new phone, a new signing key, a new package name), and Android's cloud backup leaves the
database out on purpose. The photos themselves are files in `DCIM/Latent` and the phone's own folders, and survive.
Settings → **Backup and restore** writes that metadata into one file the person keeps where they like, and reads it
back, on the same phone or another.

## The file

One zip, `latent-backup-YYYYMMDD-HHMM.zip` (the phone's time when it was made), written and read through the system's
file dialogs (`CreateDocument`, `OpenDocument`): no permission, no network. It is the app's own format, not a copy of the
SQLite file, so that it outlives changes to the database's schema.

**In it:** the entries below. **Not in it:** photos and videos; thumbnails; the map's tiles; anything under the cache
folders; the trash (its files stay in `DCIM/Latent/.trash`, but its entries are not carried); what the library reads
from the files themselves whenever it takes them in (time, size, a place the file records, the Exif index for Find);
whether location recording is on; the access the person gave to photos and the library folder; the database file.

Text entries are UTF-8 JSON Lines: one JSON object per line, in no particular order. The zip deflates every entry.
Times are milliseconds since 1970 (UTC); places are degrees (WGS 84). The order of the entries does not matter;
`manifest.json` is written last, so a file that has one was written to the end.

| Entry | What |
|---|---|
| `manifest.json` | what the file is, its format version, the app that wrote it, counts |
| `settings.json` | a few preferences |
| `media.jsonl` | photos and videos with something to give back |
| `albums.jsonl`, `album_items.jsonl` | albums, and which photos are in them |
| `versions.jsonl` | kept versions of a photo's edit |
| `filters.jsonl` | the person's own filters (looks) |
| `luts.jsonl` | the list of imported LUTs |
| `luts/<id>.lut` | the table of a LUT that is listed or that an edit, version or filter names |
| `masks/<id>.mask` | the outline of a detected mask that an edit or version names |
| `sync_records.jsonl`, `synced_folders.jsonl` | which camera files were imported, and which camera folders were done |
| `locations.jsonl` | the location history: recorded by the app and imported from a Google Timeline |

## Versions and compatibility

- `formatVersion` is **1**. The app writes the newest version it knows and reads that one and every older one.
- A file with a **newer** `formatVersion` than the app knows is refused with a message ("made by a newer version of
  Latent (format 2; this version reads up to 1): update the app"), and nothing is changed.
- Within a version a reader ignores entries and fields it does not know, and takes a missing optional field as its
  default. A record with a value it does not know where it must know it (a photo's `kind` or `origin`, a place's
  `source` in the timeline) is left out of the restore, and not counted; a `geoSource` it does not know is taken as
  `CAMERA`. A line that is no JSON object, or lacks a field that is not optional, makes the file damaged.
- A change that an older reader can ignore without harm (a new optional field, a new entry) keeps the version. Any other
  change (a field renamed, removed, or given another meaning or unit; something an older reader must not skip) makes a
  new `formatVersion`, and the reader keeps reading every older one.
- `app/src/test/resources/backup/latent-backup-format1.zip` is a format 1 file **written by hand** from this document
  (by `make_format1.py` beside it, not by the app's writer). `BackupFileTest.readsTheFirstFormatAsItWasWritten`
  restores it; it must keep passing. Each new version adds a fixture of its own.
- The package name and the app's version in the manifest are only told, never checked: a backup of
  `com.byeonggon.latent`, the package until 2026-10-22, restores into `com.latentimage.latent`.

### manifest.json

```json
{"format": "latent-backup", "formatVersion": 1, "createdAt": "2026-10-20T06:30:00Z",
 "app": {"package": "com.byeonggon.latent", "versionName": "0.1.0", "versionCode": 1},
 "counts": {"media": 3, "albums": 1, "albumItems": 2, "versions": 1, "filters": 1, "luts": 1, "lutFiles": 1,
            "masks": 1, "syncRecords": 2, "syncedFolders": 1, "locations": 3}}
```

`format` must be `latent-backup`; a zip without `manifest.json`, or with one that says something else, is not a
backup. The counts are what the writer wrote; a reader counts again and only uses them for its progress bar.

### settings.json

One object. Every field is optional; one that is missing, or holds a value the reader cannot use, leaves the phone's
preference as it is.

| Field | Type | Restored as |
|---|---|---|
| `theme` | `SYSTEM`, `LIGHT`, `DARK` | as it is |
| `gridSize` | `SMALL`, `MEDIUM`, `LARGE` | as it is |
| `tabPosition` | `AUTO`, `LEFT`, `RIGHT`, `BOTTOM` | as it is |
| `trashKeptDays` | number | as it is (1 to 3650) |
| `cameraZoneId` | text, or `null` for the phone's own zone | as it is |
| `cameraClockOffsetMinutes` | number | as it is |
| `jpegQuality` | 1 to 100 | as it is |
| `removeLocationWhenSharing`, `allExif` | true or false | as it is |
| `phoneFolders` | list of folders as MediaStore names them (`DCIM/Camera/`) | **added** to the folders chosen on the phone |
| `syncPhotos`, `syncRaw`, `syncVideos` | true or false | as it is |
| `geotagMaxGapMinutes` | number | as it is |
| `onlineMaps` | true or false | **never applied** |

`onlineMaps` is only told. The app asks before it uses the internet, so a restore never turns online maps on: it
leaves the switch as it is on the phone, and when the backup had them on and the phone has them off, it says that they
stay off until they are turned on again under Internet. Location recording, its interval and its accuracy are not in the
file: recording is started by the person, with its permission and notification.

### media.jsonl

A photo or video is written when it has something a restore can give back: a rating, a flag, an edit, a place from
the location history (`TRACK`), a camera import key, an album, or a version. The rest the library finds again in the
files themselves. Photos in the trash are left out.

| Field | Type | |
|---|---|---|
| `id` | number | its id on the phone the backup was made on; `album_items` and `versions` refer to it |
| `origin` | `LIBRARY` or `PHONE` | in the library's folder, or another app's file listed where it is |
| `path` | text, optional | where it is, relative to its root (see below) |
| `name` | text | the file name |
| `kind` | `PHOTO`, `RAW`, `VIDEO` | |
| `size` | number | bytes |
| `capturedAt`, `importedAt` | number | |
| `rating` | 0 to 5, default 0 | stars |
| `flag` | 1 picked, -1 rejected, default 0 | |
| `edit` | text, optional | the edit as the app stores it (`PhotoEdit.toJson`, a JSON object in a string) |
| `latitude`, `longitude`, `altitude` | numbers, optional | its place |
| `geoSource` | `NONE`, `CAMERA`, `TRACK` | where the place came from: the file, or the location history |
| `sourceKey` | text, optional | the camera import it came from (see sync records) |

`path` is the same on any phone: for `LIBRARY` it is relative to the library folder (`2026/2026-10-08/DSC00001.JPG`
under `DCIM/Latent`), for `PHONE` it is MediaStore's relative path and name (`DCIM/Camera/20261008_120000.jpg`), without
the storage volume (`/storage/emulated/0/`, another user's `/storage/emulated/10/`, an SD card's `/storage/1234-ABCD/`).

An edit names the files it needs by id: `"lut": "<id>"` for a LUT, `"raster": "<id>"` in a detected mask. Those files
come along under the same ids, so the edit still finds them after a restore.

### albums.jsonl and album_items.jsonl

`{"id": 7, "name": "Christmas", "createdAt": ...}` and `{"album": 7, "media": 1, "addedAt": ...}`, `album` and
`media` being the backup's ids.

### versions.jsonl

`{"media": 2, "name": "Cool", "edit": "<edit>", "createdAt": ...}`.

### filters.jsonl

`{"name": "Faded", "look": "<edit>", "createdAt": ...}`: a look, as an edit without framing, masks or spots.

### luts.jsonl, luts/&lt;id&gt;.lut

`{"hash": "<id>", "name": "Film", "size": 33, "createdAt": ...}`. The id is 32 hexadecimal digits, a digest of the
`.cube` file it was imported from. `luts/<id>.lut` is the table as `LutStore` keeps it: `LUT1` (4 bytes), the points
along a side (2 to 65), the domain's minimum and maximum for red, green and blue, then red, green and blue of every
point, red changing fastest; all big-endian 32-bit integers and floats. A LUT taken off the list that a photo still
uses comes along as a file without a line.

### masks/&lt;id&gt;.mask

The outline of a detected (subject, background or sky) mask as `MaskStore` keeps it: `MSK1`, width and height (big-endian
32-bit integers, 1 to 8192), then one level from 0 to 255 per cell, row by row, packed with zlib (deflate). The id is
32 hexadecimal digits. Only outlines that an edit or a version names are written.

### sync_records.jsonl and synced_folders.jsonl

`{"sourceKey": "<camera>|<name>|<size>|<time>", "importedAt": ...}` for every camera file ever imported, also those
deleted from the phone since, and `{"cameraId": ..., "path": ..., "fingerprint": ..., "kinds": "PHOTO,RAW,VIDEO"}` for
camera folders whose files were all imported. The time in a key is the camera's file time as the phone read it in its
time zone then; a sync recognises the same file read in another zone (whole quarter hours, up to 26 hours apart:
`ImportedFiles`), so the keys hold on a phone in another time zone.

### locations.jsonl

`{"timestamp": ..., "latitude": ..., "longitude": ..., "altitude": ..., "accuracy": ..., "source": "APP"}`; `altitude`
and `accuracy` are optional, `source` is `APP` (recorded) or `TIMELINE` (imported). It is the largest entry: 150,000
places take 1.6 MB of the file. It is written and read a page at a time, never held whole.

## Finding the photos

A photo's absolute path differs between phones, so a restore finds each photo of the backup among those on the phone:

1. First the library takes in the files that are on the phone, as it does when it opens: those in its folder
   (`LibraryIndex.adoptUnknownFiles`) and those of the phone's chosen folders together with the backup's and the folders
   the backup's phone photos are in (`PhoneLibrary.refresh`). That needs access to the photos and the library folder;
   without it the restore is refused with a message, and nothing is changed.
2. A photo of the same origin and kind at the same relative path is the one. Its size may differ: an edit changes it.
3. Otherwise, a photo of the same origin and kind with the same name (in any case) and size, taken at the same moment
   (the same time, or one off by whole quarter hours up to 26 hours, as a clock in another time zone reads it); of
   several, the nearest of them. One alone is not taken on its name and size (since 2026-10-22): camera file names
   come round again, and every uncompressed raw file of a camera has the same size.
4. Each photo on the phone is taken by one record at most, and places are tried for every record before names are, so
   that a photo found by its name never takes the one another record is at the place of.

A photo not found is counted, and the first ten names are listed before and after the restore; it is never an error.

Where it fails:

- **A renamed file** is not found (its path and its name both changed).
- **A file whose size changed and that moved**, such as a library JPEG edited again elsewhere and copied into another
  folder, is not found.
- **Copies**: files of the same name and size in several folders that the backup's time does not single out are left
  unfound rather than guessed. Copies taken at the same moment are real duplicates: the first by path is taken.
- **Another origin**: a library photo copied into a folder of the phone (`DCIM/Camera`) is another app's photo there,
  and is not taken: its edit, kept as settings there, would be laid over a JPEG that already has it.
- **Camera file names come round again** (DSC00001 after DSC09999): told apart by size and time.
- **A moved photo whose time reads differently** on this phone, by more than a time zone explains: a file without a
  time of its own, dated by when it was copied, say, is not found once it has moved.
- **A phone photo on an SD card and one at the same relative path on the phone's own storage** cannot be told apart
  (the volume is not part of the path); the first by path is taken.

## What a restore changes

A restore **merges** the backup onto what is on the phone. Before anything changes it reads the whole file: every entry
against its checksum, every line, the LUT and mask files' formats; a file that is no backup, a newer one, or one damaged
anywhere is refused with a message and nothing changes. Then it shows what it will do (photos found of all, those not
found, how many photos found have a rating, flag, edit or place of their own on the phone that the backup's replace,
and the counts of albums, versions, filters, LUTs, masks, places and camera imports) and waits for the person to
confirm.

| What | A restore |
|---|---|
| Rating and flag of a photo found | replaces the phone's |
| Edit of a raw file, a HEIF file or another app's photo | replaces the phone's, also by none |
| Edit of a library JPEG | the file shows its edit already, with its original kept aside; the backup's tells how it was made only where the phone does not know (a file the library took in after a reinstall, whose original is there). An edit made on the phone stays. |
| Place | taken where the backup has one, with its source; otherwise the phone's (read from the file) stays |
| Camera import key, time of import | taken from the backup |
| Albums | an album of the same name on the phone is the one, and the photos are added to it; otherwise it is made, with its date. An album with none of its photos on the phone is not made. Photos are never taken out of an album. Two albums of one name in a backup become one. |
| Versions | added, unless the photo has one of the same name and edit |
| Own filters | added, unless there is one of the same name and look |
| LUTs | added to the list by id (a name on the phone stays); the table's file written unless it is there |
| Masks | the outline's file written unless it is there |
| Camera import records and done folders | added; the phone's stay. The next sync skips every file in them, also those deleted from the phone since (as before; "Let them be imported again" works as usual). |
| Timeline | places added; one with the same time and source is there already and is skipped |
| Settings | as in the table above; phone folders added; online maps never turned on |
| What is on the phone and not in the backup | stays |

The LUT and mask files are written first, the database is changed in **one transaction**, and the settings last. A
restore that fails or is cancelled leaves the database as it was and takes the files it wrote away again; what stays is
only what the library does whenever it opens: it has taken in the files of its folder and the phone folders. Restoring
the same file again changes nothing more.

## Memory, time and size

Backup and restore run off the main thread, can be cancelled, and go through the database a page at a time: on the
JVM, 150,000 places are backed up in under a second and restored in about three, with well under a megabyte of them in
memory at any time (`BackupSizeTest`). A restore first copies the chosen file into the app's cache, so that it can be
read twice in any order; the copy is deleted afterwards. How long a real library takes on a phone, and how large its
file is, is to be measured (`DEVICE_CHECKS.md`).
