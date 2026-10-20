# Writes the format-1 backup fixture by hand, from docs/BACKUP_FORMAT.md, not with the app's writer.
import json, struct, sys, zipfile, zlib

LUT = "a1b2c3d4e5f60718293a4b5c6d7e8f90"
MASK = "0f1e2d3c4b5a69788796a5b4c3d2e1f0"

def lines(records):
    return "".join(json.dumps(r, separators=(",", ":"), ensure_ascii=False) + "\n" for r in records)

raw_edit = json.dumps({
    "exposure": -0.5,
    "locals": [{"shape": "DETECTED", "mask": [0.0, 0.0, 1.0, 1.0, 0.5],
                "detected": {"kind": "SUBJECT", "raster": MASK, "model": "u2netp", "modelVersion": 1},
                "exposure": 0.8}],
    "lut": LUT, "lutIntensity": 0.6,
}, separators=(",", ":"))

entries = {
    "settings.json": json.dumps({
        "theme": "LIGHT", "gridSize": "SMALL", "tabPosition": "BOTTOM", "trashKeptDays": 90,
        "cameraZoneId": "Europe/Paris", "cameraClockOffsetMinutes": -2, "jpegQuality": 80,
        "removeLocationWhenSharing": True, "allExif": False,
        "phoneFolders": ["DCIM/Camera/", "Pictures/Snapseed/"],
        "syncPhotos": True, "syncRaw": False, "syncVideos": True, "geotagMaxGapMinutes": 30,
        "onlineMaps": True, "aSettingOfALaterVersion": 1,
    }),
    "media.jsonl": lines([
        {"id": 1, "origin": "LIBRARY", "path": "2025/2025-12-24/DSC09001.JPG", "name": "DSC09001.JPG", "kind": "PHOTO",
         "size": 4100000, "capturedAt": 1766566500000, "importedAt": 1766600000000, "rating": 5, "flag": 1,
         "edit": "{\"exposure\":0.3}", "latitude": 48.8584, "longitude": 2.2945, "altitude": 35.0, "geoSource": "TRACK",
         "sourceKey": "4321|DSC09001.JPG|4000000|1766566500000"},
        {"id": 2, "origin": "LIBRARY", "path": "2025/2025-12-24/DSC09001.ARW", "name": "DSC09001.ARW", "kind": "RAW",
         "size": 24500000, "capturedAt": 1766566500000, "importedAt": 1766600000000, "edit": raw_edit,
         "sourceKey": "4321|DSC09001.ARW|24500000|1766566500000"},
        {"id": 3, "origin": "PHONE", "path": "DCIM/Camera/20251224_101500.jpg", "name": "20251224_101500.jpg", "kind": "PHOTO",
         "size": 3500000, "capturedAt": 1766567700000, "importedAt": 1766600000000, "rating": 3, "flag": -1,
         "latitude": 48.86, "longitude": 2.34, "geoSource": "CAMERA", "aFieldOfALaterVersion": "ignored"},
    ]),
    "albums.jsonl": lines([{"id": 7, "name": "Christmas", "createdAt": 1766700000000}]),
    "album_items.jsonl": lines([
        {"album": 7, "media": 1, "addedAt": 1766700001000},
        {"album": 7, "media": 3, "addedAt": 1766700002000},
    ]),
    "versions.jsonl": lines([{"media": 2, "name": "Cool", "edit": "{\"temperature\":-0.4}", "createdAt": 1766700003000}]),
    "filters.jsonl": lines([{"name": "Faded", "look": "{\"saturation\":-0.4,\"lut\":\"%s\"}" % LUT, "createdAt": 1766700004000}]),
    "luts.jsonl": lines([{"hash": LUT, "name": "Film", "size": 2, "createdAt": 1766700005000}]),
    "sync_records.jsonl": lines([
        {"sourceKey": "4321|DSC09001.ARW|24500000|1766566500000", "importedAt": 1766600000000},
        {"sourceKey": "4321|DSC09001.JPG|4000000|1766566500000", "importedAt": 1766600000000},
    ]),
    "synced_folders.jsonl": lines([{"cameraId": "4321", "path": "/DCIM/100MSDCF", "fingerprint": "12:4100", "kinds": "PHOTO,RAW,VIDEO"}]),
    "locations.jsonl": lines([
        {"timestamp": 1766566000000, "latitude": 48.8583, "longitude": 2.2944, "altitude": 34.0, "accuracy": 8.5, "source": "APP"},
        {"timestamp": 1766566600000, "latitude": 48.8585, "longitude": 2.2946, "source": "APP"},
        {"timestamp": 1766566600000, "latitude": 48.859, "longitude": 2.295, "accuracy": 50.0, "source": "TIMELINE"},
    ]),
}

# LutStore's file: "LUT1", the size, the domain, then red, green and blue for every point, as big-endian floats.
table = [0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 1.0, 1.0, 0.0, 0.0, 0.0, 1.0, 1.0, 0.0, 1.0, 0.0, 1.0, 1.0, 0.9, 0.9, 0.9]
lut = struct.pack(">ii6f", 0x4C555431, 2, 0, 0, 0, 1, 1, 1) + struct.pack(">24f", *table)
# MaskStore's file: "MSK1", width, height, then the levels row by row, packed with zlib.
mask = struct.pack(">iii", 0x4D534B31, 3, 2) + zlib.compress(bytes([0, 128, 255, 255, 128, 0]))

manifest = {
    "format": "latent-backup", "formatVersion": 1, "createdAt": "2026-10-20T06:30:00Z",
    "app": {"package": "com.byeonggon.latent", "versionName": "0.1.0", "versionCode": 1},
    "counts": {"media": 3, "albums": 1, "albumItems": 2, "versions": 1, "filters": 1, "luts": 1, "lutFiles": 1,
               "masks": 1, "syncRecords": 2, "syncedFolders": 1, "locations": 3},
}

with zipfile.ZipFile(sys.argv[1], "w") as out:
    def put(name, data):
        info = zipfile.ZipInfo(name, date_time=(2026, 10, 20, 15, 30, 0))
        info.compress_type = zipfile.ZIP_DEFLATED
        out.writestr(info, data)
    for name, text in entries.items():
        put(name, text.encode("utf-8"))
    put("luts/%s.lut" % LUT, lut)
    put("masks/%s.mask" % MASK, mask)
    put("manifest.json", json.dumps(manifest).encode("utf-8"))
