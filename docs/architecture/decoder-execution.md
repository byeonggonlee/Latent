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
