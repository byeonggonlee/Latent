# Decoder 실행 계획과 진행 기록

2026-10-21 · Codex · 기준 `f399bf3` · 사용자가 평가 계획의 순차 오토파일럿 실행을 승인했다.

## 범위와 판단

기존 ARW/DNG와 DNG 내부 JPEG XL, 14-bit 정규화와 카메라 룩을 유지한다. Core는 `app`에 두고 모듈 추출, standalone JXL/JPEG/HEIF, native backend, F32/HDR는 추가하지 않는다. 원래 평가의 M2-A → M1-A → M2-B 순서로 진행한다. 기존 jxlatte hang 수정과 RawCache 잠금 수정은 재작성하지 않는다.

자원 예산 수치는 아직 실측·승인 전이다. 이번 할당 검증은 컨테이너가 허용한 범위, 파일 길이, 기존 크기 제한과 checked arithmetic에 근거한다. 이를 폰 OOM 방지 보장으로 표현하지 않는다. 경계/동시성 변경은 Claude Code 리뷰와 CI가 필요한 PR로 내며, 직접 병합하지 않는다.

## 작업 순서

1. **M2-A — 손상 입력과 회복.** `DngTest`/synthetic fixture로 잘린 multi-group JXL strip을 DNG에 넣고 `Dng.read` 오류 및 다음 정상 read를 검사한다. TIFF entry 범위, ARW compressed strip, DNG strip/tile 범위와 산술, JXL header 치수를 할당 전에 확인한다. 오류는 `IOException`, 정상 sample은 동일해야 한다. 변경별 RED→GREEN 및 관련 회귀 후 커밋한다. 이미 동작하는 hang fix의 negative control은 별도 프로세스/격리 스냅샷에서만 수행한다.
2. **M1-A — 최소 계약.** 첫 단계의 결과에 맞춰 기존 `ArwImage`를 복사 없이 감싸는 명시적 frame/request 계약을 도입한다. sensor mosaic와 이미 보간된 camera RGB를 구분하고, 정규화/보정·저장 방향·채널/stride·공유 read-only 수명을 명시한다. 새 CMS나 픽셀 변환은 하지 않는다. shape/encoding/alias 수명과 sample 동일성을 검증한다.
3. **M2-B — dispatch와 취소.** header 기반 ARW/DNG 선택, 기존 지원 범위와 오류 분류, 협력적 취소 검사 및 codec 중단 불가 구간을 명시한다. 기존 `RawRenderer` adapter로 통합하고 캐시 monitor/gate를 suspend 경계로 넘기지 않는다. malformed→정상, 취소→정상, 확장자 불일치와 unsupported를 검증한다.
4. **완료 gate.** 전체 JVM 테스트, debug/release lint, androidTest 컴파일, schema diff 및 release 빌드; 독립 branch 리뷰 후 발견 사항 보완. 실기기 색/메모리 미검증을 명시하고 PR을 제출한다.

## 진행 기록

- 초기 baseline 시도는 새 worktree의 SDK 경로 누락으로 실행 전 실패했다. ignored `local.properties`에 기존 설치 SDK 경로만 지정하고 재실행한다. 소스 실패가 아니다.
- M0 잔여 사항(실기기/예산/샘플 권리)은 기존 제한을 바꾸지 않는 작업과 분리한다. 새 자원 상한이나 포맷 확대가 필요하면 근거와 제안을 남기고 그 변경의 구현을 보류한다.

- **M2-A 검증:** 새 입력 검증 테스트 7개는 기존 코드에서 실패했고 수정 후 통과했다. JXL 과대 tile 테스트도 기존 어댑터가 거부하지 않아 실패한 것을 확인했다. RAW 60개(58 통과, 실사진 2 skip), 전체 JVM 504개(500 통과, 4 skip). 잘린 multi-group strip은 실제 `StripByteCounts`만 줄이고 남은 파일 bytes는 유지했다. 원래 `Frame` 수정 전 클래스를 우선 로드한 별도 Java negative control은 같은 DNG 테스트가 20초 내 종료하지 않아 프로세스를 종료했다. vendor source/patch는 변경하지 않았다.
- **자원 제한:** JXL header는 DNG sensor 할당 전과 codec 진입 시 검사한다. 기존 API에 header-only 진입점이 없어 public header parser로 preflight를 수행한다. 이중 header parsing 비용과 codec 내부/ICC allocation 상한은 실측 전이며 전체 메모리 예산 보장을 주장하지 않는다.

- **M1-A:** `raw/api/DecodedFrame.kt`를 도입하고 ARW/DNG의 기존 `read`는 동일 legacy image로 반환한다. 계약 API 부재 RED 후 RAW 계약/회귀 GREEN, 전체 JVM 511개(507 통과, 4 skip). unsigned bit pattern, 기존 sample 동일성, Core의 read-only 사용, crop view 수명, shape/range와 opaque TIFF ICC 보존을 확인했다. `image-contract.md`에 전환 중 mutable legacy 배열 및 미지원 profile/색 관리/장치 예산을 명시했다.

- **M2-B 구현:** 정적 TIFF header dispatch, `DecodeFailureReason`(unsupported/corrupt/resource/I/O), 입력·최종 sample 바이트 상한과 row/tile/codec 앞뒤 취소 callback을 도입했다. 취소 예외는 감싸지 않으며 decoder 상태를 다음 요청에 남기지 않는다. `RawRenderer.read`만 legacy adapter로 연결했고 `RawCache` 잠금과 `ArwImage` 필드는 바꾸지 않았다. UI Job→callback 전달은 M3-A 후속 범위다.
- **독립 검토:** tool-less Claude Code CLI가 전체 diff와 호출부를 검토하여 확정 P1/P2 없음, 호환성/실측 위험을 제시했다. unused private TIFF tag로 정상 태그까지 실패하는 사례를 RED로 재현하고, payload를 실제 조회할 때만 checked range를 적용하도록 보완했다. jxlatte `JXLImage`가 `getOrientedSize()`를 쓰는 것을 확인했고, orientation 6의 수작업 header 테스트가 RED인 후 preflight도 같은 치수를 쓰도록 수정했다. padded edge tile의 literal sample 테스트는 통과했다.
- **출하 범위 판단:** 새 모듈/포맷/native/색 룩/Gradle/CI 변경 없이 기존 decoder와 내부 adapter만 변경한다. 이번 PR은 release가 아니므로 프로젝트의 기존 `latent.versionBase`와 release notes는 임의로 바꾸지 않는다. `ship` 절차의 검증·리뷰·문서·PR 단계를 적용한다.

- **최종 로컬 gate:** `./gradlew testDebugUnitTest lintDebug lintRelease assembleDebugAndroidTest assembleRelease -PdebugSignedRelease --offline` 성공(1m14s), JVM 526개 중 522 통과/4 skip. `git diff --check`와 Room schema diff 통과. 코드/metadata 이동 외 sample 변환은 기존 결과를 유지한다. 실제 폰 heap/RSS/OOM·색·실사진 회귀는 미검증이며 CI 결과와 구분한다.
- **다음 승인 지점:** 이 PR의 shared `RawRenderer` adapter와 v0 계약을 유지보수자가 검토한 뒤 통합한다. M3-A(UI Job→요청 취소 전달), 폰 예산 확정, 색/왜곡 기능과 모듈 추출은 이 PR에서 완료했다고 표시하지 않는다.

- **제출:** [PR #7](https://github.com/byeonggonlee/Latent/pull/7), `codex/decoder-stability`. 단계 커밋: `b8be490`(선점/계획), `0f7809e`(M2-A), `3350d8c`(M1-A), `a4a23e3`(M2-B 및 검토 보완). 통합 승인과 원격 CI는 로컬 성공과 구분한다.

## PR #7 리뷰 후속 — 2026-10-21

[Claude Code 리뷰](https://github.com/byeonggonlee/Latent/pull/7#issuecomment-6310871184)를 현재 코드와 대조했다. 새 테스트 5개가 `c1dc651`의 `Unsupported RAW metadata`, `Invalid RAW calibration`, `Damaged TIFF value range`, strip 길이 검사에서 실패했고, `2a4f81d` 수정 후 RAW 회귀가 통과했다.

- ARW/DNG의 방향값 0·9·65535는 upright(1)로 정규화한다. 기존에 허용하던 유한한 ARW WB gain(0·음수 포함)은 유지한다. crop/shape, finite calibration과 sample range 검사는 유지한다.
- 범위가 잘못된 선택 TIFF ICC payload만 생략한다. 정상 ICC 방어 복사와 픽셀 오류 처리는 유지한다.
- lossless JPEG는 기존 decoder처럼 overstated byte count를 실제 EOF에서 자른다(`Int.MAX_VALUE` 회귀 포함). 비압축·JPEG XL은 선언 범위를 계속 검사하며, offset·양수 길이·메모리 제한은 완화하지 않았다.
- 기본 취소 검사는 현재 스레드의 interrupt만 관찰한다. M3-A에서 요청 Job의 `ensureActive()`를 캡처하고 worker 전파·UI 로딩 정리를 검사한다. 이번 PR에 UI 취소 완료를 표시하지 않는다.
- 추가 sample scan은 측정 전까지 validation으로 유지하고, public `Dng.isDng`도 호환 helper로 유지한다. 성능 개선이나 API 삭제를 이번 호환성 수정에 섞지 않는다.
- HANDOFF의 assessment-only 문장을 과거 문서 커밋에 한정했고, 미병합 PR #7의 Done 행을 제거했다. In progress 선점은 병합 때까지 유지한다.

후속 로컬 gate: 전체 JVM **531개(527 통과, 선택 4 skip)**, debug/release lint, androidTest 컴파일, R8 release 빌드 성공(1m11s). `BackupSizeTest`도 통과했다. Room schema와 whitespace diff 검사를 통과했다. 폰 메모리·OOM·실사진은 측정하지 않았다. 원격 CI는 이 후속 커밋을 push한 뒤 별도로 확인한다.


## 소유자 지정 후속 순서 — 2026-10-21

1. 보드 정리 `4be60fc`: PR #7(`938b5e0`)을 Done으로 옮기고 JPEG XL tile 크기 항목 완료 표시. 필수 main gate 통과 후 fast-forward push.
2. [PR #8 리뷰](https://github.com/byeonggonlee/Latent/pull/8#issuecomment-6313647973), head `3e1db45`: 차단 사항 없음. 실제 DNG ForkJoin worker-only Job 취소와 결정적 queued-reader 취소 probe 각각 통과. 관련 테스트 실행 및 원래 전체 결과 535개(531 통과·4 skip, 전체 실행은 cache 재사용), 원격 CI 성공 확인. PR 소스는 수정하지 않았다.
3. 비압축 DNG 길이 호환성: WORDS/PACKED_12의 count 1과 Int.MAX_VALUE 두 테스트 RED 후, 실제 행 바이트를 EOF와 비교하도록 수정. metadata를 보존한 채 마지막 row byte만 제거하면 여전히 실패하고 JXL 선언 범위 검사는 유지한다. 필수 main gate 성공(59s), 전체 534개(530 통과·4 skip). 원격 CI 확인 후 보드를 완료 처리한다.
4. 이후 색 행렬 보간은 별도 리뷰 PR, WarpRectilinear는 PR #8 병합 뒤 seam 선점 후 진행한다. 모듈 추출·backend 비교·폰 우선 항목은 시작하지 않는다.


## 두 광원 색 보간 설계와 검증 계획

소유자가 지정한 기존 `Dng.Colors`의 범위 안에서 구현한다. 새 모듈·CMS·ForwardMatrix·색 룩·샘플 정규화·BaselineExposure/게인맵 순서는 바꾸지 않는다. 선행 비압축 DNG 수정 `113ca0b`의 [CI](https://github.com/byeonggonlee/Latent/actions/runs/39584546602)는 전체 성공했다.

- 표준 EXIF 광원을 Kelvin으로 대응하고 Robertson CIE 1960 uv 등온선으로 source white의 역색온도를 얻는다. `AsShotNeutral`은 D50 xy에서 시작해 현재 XYZ→camera 역행렬로 white를 찾는 과정을 최대 30회 반복한다. 유효한 `AsShotWhiteXY`가 있으면 직접 weight를 구한다.
- 두 광원 경로에 SDK의 D50 기준 ColorMatrix scale 정규화와 소수 4자리 반올림을 적용한다. SDK 실제 순서에 맞춰 각 광원의 `AnalogBalance × CameraCalibration × ColorMatrix`를 먼저 합성하고, 두 합성 행렬을 clamped inverse-temperature weight로 보간한다. 각 행렬을 별도로 보간한 후 곱하는 방식은 사용하지 않는다. 기존 sRGB primaries 곱·행 정규화·역행렬과 Core WB/룩은 유지한다.
- 단일 행렬, 광원 누락/알 수 없음/같은 온도, scene white 추정 실패는 기존 daylight 우선 선택을 유지한다. 두 광원 경로의 WhiteXY-only 입력은 그 white로 WB를 구한다. 단일 행렬 경로는 기존 반환값을 유지한다.
- TDD: tungsten/daylight endpoint, 중간 white와 보간 weight 방향, Neutral↔WhiteXY, 광원 순서 반전, calibration/analog 적용, 단일·알 수 없는 광원 fallback을 실제 synthetic DNG로 검증한다. 고정 수치는 앱과 별도의 Gauss–Jordan 계산으로 구했다.
- 필수 전체 JVM/lint/androidTest 컴파일·schema 검사, release 빌드와 독립 리뷰 후 별도 PR을 연다. `DEVICE_CHECKS.md`에 tungsten RAW/JPEG 비교를 추가하고 미검증으로 둔다.

근거: Adobe [DNG SDK](https://www.adobe.com/support/downloads/dng/dng_sdk.html), AOSP에 수록된 SDK 1.7.1 [`dng_color_spec.cpp`](https://android.googlesource.com/platform/external/dng_sdk/+/de700ad461e35af50b28b861943a0b0753b10929/source/dng_color_spec.cpp) (`FindXYZtoCamera_SingleOrDual`, `NeutralToXY`), [`dng_temperature.cpp`](https://android.googlesource.com/platform/external/dng_sdk/+/de700ad461e35af50b28b861943a0b0753b10929/source/dng_temperature.cpp), [`dng_camera_profile.cpp`](https://android.googlesource.com/platform/external/dng_sdk/+/de700ad461e35af50b28b861943a0b0753b10929/source/dng_camera_profile.cpp). SDK 자체를 빌드하거나 앱 의존성으로 추가하지 않는다.


### 구현과 독립 리뷰

- `b889a14`: 실제 synthetic DNG의 두 광원 보간, `DngIlluminants`의 표준 EXIF/Robertson 수치, Neutral/WhiteXY·calibration·analog·광원 순서·단일 경로·scale·진동 회귀를 구현했다. 초기 색 선택 실패 5개, 단일 행렬의 unused illuminant payload, CM 전체 scale 불변성이 각각 RED 후 GREEN이다. 샘플 배열과 Core 룩 단계는 바꾸지 않았다.
- **수치 판단:** 최초의 강한 합성 프로필은 독립 Gauss–Jordan/Robertson 계산에서도 두 white 사이를 진동했다. 그 입력을 4000 K 수렴 사례로 보지 않고 SDK의 30회 평균 사례로 분리했다. 수렴하는 별도 프로필의 4000 K 결과와 직접 WhiteXY 결과는 고정 수치로 검사한다. 비용/한계는 비수렴 white가 SDK의 근사 평균이라는 것이다.
- **Claude Code CLI 독립 리뷰:** 전체 diff·호출부·SDK 1.7.1 primary source 발췌를 tool-less로 검토했고 필수 P1/P2는 없었다. 선택 지적 중 WhiteXY의 음수 camera response에서 WB만 legacy가 되는 항목은 색 모델 불일치로 판단해 회귀 RED 후 transform/WB 동시 fallback으로 수정했다. 재리뷰 루프는 만들지 않았으며 이 CLI 검토를 GitHub 승인으로 표시하지 않는다.
- **확인한 검토 가정:** 광원 Kelvin 표는 `IlluminantToTemperature` 실제 소스와 대조했다(13=5050, 2/14=4150, 15=3525 등). SDK를 빌드하거나 완전한 렌더 결과를 비교한 것은 아니다. calibration signature 비교 미지원은 기존 동작이며 이번 보간의 새 정책으로 만들지 않는다.
- **비차단 후속:** 반복의 Float 정밀도에서 1e-7 조기 수렴 여부는 보장하지 않는다(30회 상한 유지). Neutral 경로의 광원 역순, 서로 다른 tag/동일 Kelvin, 모든 fallback WB 불변, Neutral/WhiteXY 동시 제공의 우선순위, 0/음수 Neutral의 추가 경계 테스트는 후속으로 남겼다. 기존 API의 단일 profile 선택과 WB 경로는 계속 회귀한다.
- 텅스텐/백열등의 neutral·skin을 폰 JPEG와 비교하는 미확인 항목 한 줄을 `DEVICE_CHECKS.md`의 Phone raw files에 추가했다. 폰 색·메모리·OOM은 측정하지 않았다. M3-B/M4-A와 폰 우선 exposure/gain-map 변경은 하지 않았다.

후속 최종 gate: `testDebugUnitTest lintDebug lintRelease assembleDebugAndroidTest assembleRelease -PdebugSignedRelease --offline` 성공(1m15s), JVM **546개(542 통과·4 선택 skip)**. DNG 33개와 `BackupSizeTest` 포함 모두 통과했다. Room schema 및 whitespace diff 검사도 통과했다. 구현 `b889a14`, 독립 리뷰 후속 수정 `9c5dbd4`; 별도 리뷰 PR로 제출하고 직접 병합하지 않는다.
