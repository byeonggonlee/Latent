# Play Store listing and declarations (draft)

Everything to paste into the Play Console, written from what the app does today. Check it against the
app again before submitting. Pictures are in [`store/`](../store): `icon-512.png`,
`feature-graphic-1024x500.png` (regenerate with `LATENT_STORE_ASSETS=1 ./gradlew testDebugUnitTest --tests '*StoreAssetsTest'`).
Phone screenshots still have to be taken on a phone, with photos the owner is happy to show.

- App name: **Latent**
- Package: `com.byeonggon.latent`
- Category: Photography
- Privacy policy URL: https://github.com/byeonggonlee/Latent/blob/main/PRIVACY.md
- Source code: https://github.com/byeonggonlee/Latent (Apache License 2.0)
- Contact: https://github.com/byeonggonlee/Latent/issues

## Short description (80 characters at most)

English: `Bring in camera photos over USB, edit raw and JPEG, map them. No account.`
한국어: `카메라 사진을 USB로 가져와 RAW·JPEG를 보정하고 지도에서 봅니다. 계정 없음.`

## Full description

**English**

Latent brings the photos of your camera onto your phone, places them where they were taken, and develops them.

- Import over USB (MTP), only what is new or only the days you pick. Photos and videos, raw and JPEG together.
- Location from your phone's own record or a Google Timeline export, written into the photos. Fix a camera clock that is off.
- Develop Sony ARW raw files with the camera's lens corrections, and edit JPEGs: exposure, tone curves, colour mixer and grading, texture, clarity, dehaze, sharpening and noise reduction, filters, cropping, levelling and perspective, linear, radial and painted masks, and spot healing. Edits stay editable; a JPEG's original is kept.
- Swipe on a photo to edit it, type exact values, see clipped highlights and shadows.
- A library by day, with ratings, a trash, sharing, and a map of where you took your photos.
- List your phone's own photos too, from the folders you choose. They are never changed; edits are kept as settings.
- Phones, foldables, tablets and laptop windows, with keyboard and mouse.

Private by design: no account, no ads, no analytics. Your photos stay on your phone as ordinary files in DCIM/Latent. The internet is used only for the optional map, which is off until you turn it on.

Sony and ZV-E10 are trademarks of Sony Group Corporation. Latent is not affiliated with or endorsed by Sony or Google.

**한국어**

Latent는 카메라의 사진을 휴대전화로 가져와 찍은 곳에 놓고, 현상합니다.

- USB(MTP)로 가져오기: 새 사진만, 또는 고른 날짜만. 사진·동영상, RAW와 JPEG를 함께.
- 휴대전화가 기록한 위치나 Google 타임라인 내보내기로 사진에 위치를 넣습니다. 카메라 시계가 틀려도 맞출 수 있습니다.
- Sony ARW RAW를 카메라의 렌즈 보정과 함께 현상하고 JPEG를 보정합니다: 노출, 톤 곡선, 색상 혼합·등급, 텍스처, 명료도, 안개 제거, 선명도, 노이즈 감소, 필터, 자르기·수평·원근, 선형·원형·브러시 마스크, 잡티 제거. 보정은 언제든 다시 열 수 있고, JPEG 원본은 보관됩니다.
- 사진 위에서 스와이프로 보정하고, 값을 직접 입력하고, 날아간 밝은 곳·어두운 곳을 확인합니다.
- 날짜별 보관함, 별점, 휴지통, 공유, 사진을 찍은 곳의 지도.
- 휴대전화 자체의 사진도 고른 폴더에서 함께 볼 수 있습니다. 원본은 절대 바뀌지 않고 보정은 설정으로 저장됩니다.
- 휴대전화, 폴더블, 태블릿, 노트북 창에서 키보드와 마우스로.

설계부터 사생활 중심: 계정, 광고, 분석이 없습니다. 사진은 DCIM/Latent의 일반 파일로 휴대전화 안에 머뭅니다. 인터넷은 선택 기능인 지도에만 쓰고, 켜기 전에는 쓰지 않습니다.

Sony와 ZV-E10은 Sony Group Corporation의 상표입니다. Latent는 Sony, Google과 관련이 없으며 보증을 받지 않았습니다.

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

For the foreground service declarations Play asks for a short video: show turning on recording (notification
visible) and starting a sync (notification with progress).

## Data safety form (suggested answers)

- Does the app collect or share user data? **Collected: none by the developer. No data is sent to the developer or to any analytics or ad service.**
- With online maps on, map tile requests go from the phone straight to OpenStreetMap's servers. That reveals an IP address and the map area looked at, not photos or a list of places. If the console asks about it, answer for *Approximate location* (the area of the map shown), **shared with a third party (OpenStreetMap), optional, not linked to the user, for app functionality**, and name it in the policy (it is: see PRIVACY.md, "Online maps").
- Photos and videos, location history, and edits stay on the device. Not collected, not shared.
- Data encrypted in transit: yes, for the map tiles (HTTPS); nothing else is transmitted.
- Can users ask to delete their data? Everything is local; uninstalling deletes the app's database. Photos are ordinary files the user owns.
- Cloud backup: the database (it holds recorded locations) is excluded from Android's cloud backup.

## Other forms

- Content rating questionnaire: no user-generated content shared, no violence, no purchases, no ads.
- Target audience: 13+ / general. Not designed for children.
- Ads: none. In-app purchases: none.
- App access: no login. Reviewers can use the app without a camera: the library lists the phone's own photos once a folder is chosen ("Photos on this phone…"), and sync can be tried only with a camera over USB.
- Government apps / news / health / finance: no.

## Before the first upload

- [ ] Create an upload keystore; put `keystore.properties` (storeFile, storePassword, keyAlias, keyPassword) next to `settings.gradle.kts`. It is ignored by git.
- [ ] `./gradlew bundleRelease` and upload `app/build/outputs/bundle/release/app-release.aab`. Do not use `-PdebugSignedRelease` for Play: Play refuses debug-signed packages.
- [ ] Raise `versionCode` for every upload.
- [ ] Take phone screenshots (library, viewer, editor, map, sync) on real photos.
- [ ] Run [`docs/DEVICE_CHECKS.md`](DEVICE_CHECKS.md) on the release build.
