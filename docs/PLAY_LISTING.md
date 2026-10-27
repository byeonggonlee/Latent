# Play Store listing and declarations (draft)

Everything to paste into the Play Console, written from what the app does today. Check it against the
app again before submitting. Pictures are in [`store/`](../store): `icon-512.png`,
`feature-graphic-1024x500.png`, and `developer-header-4096x2304.png` for the developer page (regenerate with `LATENT_STORE_ASSETS=1 ./gradlew testDebugUnitTest --tests '*StoreAssetsTest'`).
Phone screenshots still have to be taken on a phone, with photos the owner is happy to show.

- App name: **Latent**
- Package: `com.latentimage.latent` (settled 2026-10-22; it can never change once on Play)
- Developer name: **Latent Image**
- Category: Photography
- Privacy policy URL: https://github.com/byeonggonlee/Latent/blob/main/PRIVACY.md
- Source code: https://github.com/byeonggonlee/Latent (Apache License 2.0)
- Contact: https://github.com/byeonggonlee/Latent/issues

## Short description (80 characters at most)

English: `From camera (USB) or memory card: develop RAW and JPEG, map them. No account.`
한국어: `카메라를 USB로 연결하거나 메모리 카드에서 사진을 가져와 RAW·JPEG를 보정하고 지도에서 봅니다. 계정 없음.`
日本語: `カメラをUSBでつなぐかメモリーカードから写真を取り込み、RAWとJPEGを現像して地図で見る。アカウント不要。`

## Full description (4000 characters at most)

**English**

Latent brings the photos from your camera onto your phone, places them where they were taken, and develops them.

• Import over USB (MTP) or from the camera's card: only what is new, or only the days you pick. The raw file and JPEG of a shot show as one.
• Add the place to each photo from your phone's own location record or a Google Timeline export. Fix a camera clock that is off.
• Develop raw files: Sony ARW with the camera's lens corrections, and phone DNGs such as a Galaxy's Pro mode and Expert RAW. JPEGs are edited the same way, and their original is kept.
• Edit with exposure, tone curves, colour mixer and grading, texture, clarity, dehaze, sharpening and noise reduction, cropping, levelling and perspective, spot healing, and masks: linear, radial, brush, colour and tone ranges, and the subject, background and sky, found on the phone.
• Looks: built-in filters, your own, Lightroom presets (.xmp) and 3D LUTs (.cube).
• Sort through your shots with stars, pick and reject flags, albums, search by camera, date and place, and a side-by-side compare.
• See HDR photos in HDR on a phone with an HDR screen.
• Back up albums, ratings, edits and your location record to one file you keep, for a new phone or a reinstall.
• See on a map where you took your photos (OpenStreetMap).
• List your phone's own photos too, from the folders you choose. They are never changed.
• Phones, foldables, tablets and laptop windows, with keyboard and mouse.

Private by design: no account, no ads, no analytics. Your photos stay on your phone as ordinary files in DCIM/Latent, and your edits and location record never leave it. The internet is used only for the optional map, which stays off until you turn it on.

Needs Android 15 or later on a 64-bit ARM phone. Tried with a Sony ZV-E10 and Galaxy phones; other cameras that offer MTP may work.

Sony and ZV-E10 are trademarks of Sony Group Corporation, Galaxy of Samsung Electronics, and Lightroom of Adobe. Latent is not affiliated with or endorsed by Sony, Samsung, Adobe or Google.

**한국어**

Latent는 카메라의 사진을 휴대전화로 가져와, 찍은 곳에 놓고, 현상합니다.

• USB(MTP)나 카메라의 카드에서 가져오기: 새 사진만, 또는 고른 날짜만. 한 장면의 RAW와 JPEG는 하나로 보입니다.
• 휴대전화가 기록한 위치나 Google 타임라인 내보내기로 사진마다 찍은 곳을 넣습니다. 카메라 시계가 틀려도 맞출 수 있습니다.
• RAW 현상: Sony ARW는 카메라의 렌즈 보정과 함께, 휴대전화 DNG(갤럭시의 프로 모드, Expert RAW 등)도 현상합니다. JPEG도 같은 방식으로 보정하고, 원본은 보관합니다.
• 보정: 노출, 톤 곡선, 색상 혼합·그레이딩, 텍스처, 명료도, 안개 제거, 선명도, 노이즈 감소, 자르기·수평·원근, 잡티 제거, 그리고 마스크(선형, 원형, 브러시, 색상·톤 범위, 휴대전화 안에서 찾는 피사체·배경·하늘).
• 룩: 기본 필터, 직접 만든 필터, Lightroom 프리셋(.xmp), 3D LUT(.cube).
• 사진 고르기: 별점, 채택·제외 플래그, 앨범, 카메라·날짜·장소로 찾기, 나란히 비교.
• HDR 화면이 있는 휴대전화에서는 HDR 사진을 HDR로 봅니다.
• 앨범, 별점, 보정, 위치 기록을 파일 하나로 백업해 새 휴대전화나 재설치 때 되살립니다.
• 사진을 찍은 곳을 지도(OpenStreetMap)에서 봅니다.
• 고른 폴더의 휴대전화 사진도 함께 볼 수 있습니다. 원본은 절대 바뀌지 않습니다.
• 휴대전화, 폴더블, 태블릿, 노트북 창에서 키보드와 마우스로.

설계부터 사생활 중심: 계정, 광고, 분석이 없습니다. 사진은 DCIM/Latent의 일반 파일로 휴대전화 안에 머물고, 보정과 위치 기록도 밖으로 나가지 않습니다. 인터넷은 선택 기능인 지도에만 쓰며, 켜기 전에는 쓰지 않습니다.

Android 15 이상, 64비트 ARM 휴대전화가 필요합니다. Sony ZV-E10과 갤럭시 휴대전화로 시험했으며, MTP를 지원하는 다른 카메라도 동작할 수 있습니다.

Sony와 ZV-E10은 Sony Group Corporation, 갤럭시는 삼성전자, Lightroom은 Adobe의 상표입니다. Latent는 Sony, 삼성, Adobe, Google과 관련이 없으며 보증을 받지 않았습니다.

**日本語**

Latent は、カメラの写真をスマートフォンに取り込み、撮った場所に置き、現像します。

• USB（MTP）やカメラのカードから取り込み：新しい写真だけ、または選んだ日だけ。同じショットの RAW と JPEG はひとつに表示されます。
• スマートフォンの位置情報の記録や Google タイムラインのエクスポートから、写真ごとに撮影場所を付けます。カメラの時計のずれも直せます。
• RAW 現像：Sony ARW はカメラのレンズ補正つきで、スマートフォンの DNG（Galaxy のプロモードや Expert RAW など）も現像します。JPEG も同じように編集でき、元の写真は保管されます。
• 編集：露出、トーンカーブ、カラーミキサーとグレーディング、テクスチャ、明瞭度、かすみの除去、シャープネス、ノイズ低減、切り抜き・水平・遠近、スポット修正、そしてマスク（線形、円形、ブラシ、色域・トーン範囲、スマートフォン上で見つける被写体・背景・空）。
• ルック：内蔵フィルター、自作フィルター、Lightroom プリセット（.xmp）、3D LUT（.cube）。
• 写真の選別：星の評価、採用・除外フラグ、アルバム、カメラ・日付・場所での検索、並べて比較。
• HDR 画面のあるスマートフォンでは、HDR 写真を HDR で表示します。
• アルバム、評価、編集、位置情報の記録をひとつのファイルにバックアップし、新しいスマートフォンや再インストールで戻せます。
• 写真を撮った場所を地図（OpenStreetMap）で見られます。
• 選んだフォルダのスマートフォンの写真も一覧できます。元の写真は決して変更されません。
• スマートフォン、折りたたみ、タブレット、ノートパソコンのウィンドウで、キーボードとマウスにも対応。

プライバシーを重視した設計：アカウント、広告、分析はありません。写真は DCIM/Latent の普通のファイルとしてスマートフォンに残り、編集や位置情報の記録も外に出ません。インターネットは任意の地図にだけ使い、オンにするまで使いません。

Android 15 以降の 64 ビット ARM スマートフォンが必要です。Sony ZV-E10 と Galaxy のスマートフォンで試しています。MTP に対応した他のカメラでも動く場合があります。

Sony と ZV-E10 は Sony Group Corporation、Galaxy は Samsung Electronics、Lightroom は Adobe の商標です。Latent は Sony、Samsung、Adobe、Google とは関係がなく、承認も受けていません。

## Release notes for the first release (500 characters at most)

English: `The first release of Latent: bring in your camera's photos over USB or from its card, place them on the map, and develop them on your phone.`
한국어: `Latent의 첫 출시: 카메라 사진을 USB나 카드로 가져와 지도에 놓고 휴대전화에서 현상합니다.`
日本語: `Latent の最初のリリース：カメラの写真を USB やカードから取り込み、地図に置き、スマートフォンで現像します。`

## Screenshots to take

Play asks for at least two phone screenshots (up to eight), 16:9 or 9:16, and shows the first three most. On the
release build, in the light theme unless noted, with photos the owner is happy to show:

1. Library: a day of real photos, with stars and a pick or two.
2. Viewer: one strong photo, its details sheet open.
3. Editor: a raw photo mid-edit, Light panel, histogram showing.
4. Editor with a mask: the sky or subject mask over a landscape.
5. Map: places with photo pins (online maps on; the attribution shows).
6. Import: a camera connected, or the result of an import.
7. Optional: a foldable or tablet in a wide window (tablet screenshots are separate in the console).

## Permissions, and what to say about each

| Permission | Why (for the declaration forms) |
|---|---|
| `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO` | **Photo and video permissions declaration.** Core purpose: a photo library and editor that lists, shows and develops the photos and videos in its own folder `DCIM/Latent` and, when the user chooses folders, those of other apps. A one-off picker cannot do it: the library is browsed and edited continuously. Nothing is uploaded. |
| `ACCESS_MEDIA_LOCATION` | Reads the places stored in photos, to show and map them. |
| `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` | While the user has location recording on: the phone's position every few minutes, kept on the phone, to tell where each photo was taken from the time it was taken. **No background location permission is used.** |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION` | The recording runs as a foreground service with a notification the whole time. Declare type *location*: "continuous location recording started by the user, with a visible notification and a stop button". |
| `FOREGROUND_SERVICE_DATA_SYNC` | Importing from a camera over USB can take minutes to an hour for thousands of files. Declare type *dataSync*: "user-started transfer of photos from a camera, with progress and cancel in the notification". |
| `POST_NOTIFICATIONS` | To show that recording or a transfer is running, or that recording stopped. |
| `RECEIVE_BOOT_COMPLETED` | After a restart, to tell the user that location recording has stopped (it cannot start itself without background access). Nothing runs at boot. |
| `INTERNET` | Only for the optional map: OpenStreetMap tiles. Off until the user turns on online maps and confirms. With it off, the app makes no request. |
| USB host (`uses-feature`, optional in effect) | To read the camera. |
| `ACCESS_NETWORK_STATE`, `WAKE_LOCK` | Normal permissions, no declaration form: asked for by the video and map libraries; the app uses them to know whether it is online and to finish a sync with the screen off. |

For the foreground service declarations Play asks for a short video: show turning on recording (notification
visible) and starting a sync (notification with progress).

## Data safety form (suggested answers)

- Does the app collect or share user data? **Collected: none by the developer. No data is sent to the developer or to any analytics or ad service.**
- With online maps on, map tile requests go from the phone straight to OpenStreetMap's servers. That reveals an IP address and the map area looked at, not photos or a list of places. If the console asks about it, answer for *Approximate location* (the area of the map shown), **shared with a third party (OpenStreetMap), optional, not linked to the user, for app functionality**, and name it in the policy (it is: see PRIVACY.md, "Online maps").
- Photos and videos, location history, and edits stay on the device. Not collected, not shared.
- Subject, background and sky masks are worked out on the device by models shipped inside the app (LiteRT, no Google Play services, no ML Kit): processing on the device is not collection, so nothing changes in the form.
- Data encrypted in transit: yes, for the map tiles (HTTPS); nothing else is transmitted.
- Can users ask to delete their data? Everything is local; uninstalling deletes the app's database. Photos are ordinary files the user owns.
- Cloud backup: the database (it holds recorded locations) is excluded from Android's cloud backup.
- Backup and restore (Settings): a file the user writes, through the system's file dialog, to a place the user picks, and
  reads back the same way. The app never transmits it; data the user moves themselves is not collection or sharing, so
  nothing changes in the form (PRIVACY.md, "Backups", says what the file holds).

## Other forms

- Content rating questionnaire: no user-generated content shared, no violence, no purchases, no ads.
- Target audience: 13+ / general. Not designed for children.
- Ads: none. In-app purchases: none.
- App access: no login. Reviewers can use the app without a camera: the library lists the phone's own photos once a folder is chosen ("Photos on this phone…"), and sync can be tried only with a camera over USB.
- Government apps / news / health / finance: no.

## Before the first upload

- [x] Create an upload keystore (2026-10-22: the release key, alias `latent`, which is Play's upload key; RELEASES.md, "Signing"). To build on a computer, put `keystore.properties` (storeFile, storePassword, keyAlias, keyPassword) next to `settings.gradle.kts`. It is ignored by git.
- [ ] Upload a signed AAB: from the release workflow once it publishes one beside the APK (being added), or built
      with `./gradlew bundleRelease` and a `keystore.properties`. Do not use `-PdebugSignedRelease` for
      Play: Play refuses debug-signed packages. Let Play App Signing keep the app signing key; the release key is the
      upload key.
- [ ] Every upload needs a higher `versionCode`: the build number in the file name, which only grows.
- [ ] Check the native libraries keep 16 KB page alignment (Play requires it for apps targeting Android 15 and
      later). Checked on `v0.1.0-rc.1` on 2026-10-27: all five `.so` files have 16 KB LOAD segments and the APK passes
      `zipalign -c -P 16`.
- [ ] Take the phone screenshots above on real photos.
- [ ] Record the foreground-service video: turning on location recording (notification visible) and an import with
      its progress notification.
- [ ] A personal developer account created after 13 November 2023 must run a closed test with at least 12 testers
      for 14 days in a row before it can apply for production. Set up the closed test track first if that applies.
- [ ] Run [`docs/DEVICE_CHECKS.md`](DEVICE_CHECKS.md) on the release build.
