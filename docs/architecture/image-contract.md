# Image Decoder 내부 계약 v0

2026-10-21 · Codex · 순차 구현 승인에 따른 PR 제안. 외부 SDK ABI나 새 모듈의 선언이 아니다.

## 지원 범위와 픽셀

기존 Sony compressed ARW 2.3와 mosaic/linear DNG만 다룬다. DNG 내부 JPEG XL은 기존 jxlatte를 유지한다. standalone JXL, Android JPEG/HEIF, alpha, U8/F16/F32 및 HDR 출력은 이 API의 지원 범위가 아니다.

`DecodedFrame`은 기존 `ArwImage`를 복사 없이 감싼다. `U16Samples`는 unsigned bit pattern을 0..65535로 읽지만, **v0 frame의 실제 정규화 sample은 0..32767**이다. 원본 bit depth와 출력 white level은 별개다. Sony stored 11-bit 값은 tone expansion을 거치고 DNG의 white는 기존 16383이다. Core는 기존 14-bit 처리와 clipping을 유지한다.

mosaic는 crop 원점에서 RGGB를 반복하며, linear DNG는 camera RGB interleaved이다. row stride 단위는 sample이고 padding/alpha는 없다. sensor 전체 크기와 crop, EXIF 1..8은 저장 방향 그대로다. 범위 밖 방향값(0·9 포함)은 기존 Core fallback과 같이 1(upright)로 정규화한다. 방향 적용·demosaic·WB·calibration·룩·출력 변환은 기존 Core에 남는다. 이 값들을 sRGB, 색도학적으로 보장된 scene radiance, 또는 HDR라고 부르지 않는다.

## 보정과 메타데이터

적용된 Sony tone expansion, DNG levels/linearization, 실제 양의 baseline exposure 조정과 gain map 단계를 기록한다. Core가 같은 처리를 중복 적용하지 않는다. matrix/WB·렌즈·crop 등 기존 calibration은 legacy image에 유지된다. ARW 리더가 허용하던 유한한 0·음수 WB gain도 보존하며 새 양수 조건을 추가하지 않는다.

TIFF `InterColorProfile`은 제한된 opaque source bytes로 보존하고 조회 때 방어 복사한다. 선택 profile의 payload 범위가 손상되면 해당 profile만 생략하며 픽셀 디코딩 오류는 감추지 않는다. CMS 변환이나 출력 JPEG profile 적용은 하지 않는다. DNG의 별도 calibration ICC tag 및 JXL 내부 profile 전체 보존은 아직 이 adapter가 보장하지 않는다. 촬영/노출/EXIF/XMP의 전체 보존은 원본 파일과 기존 앱 metadata/export 경로의 책임이다.

유효한 두 광원 DNG는 D50 기준 profile scale 정규화 후 `AB × CC × CM`을 역색온도로 보간한다. Neutral의 white 추정은 최대 30회이며 진동 시 마지막 두 xy의 평균을 쓴다. WhiteXY-only 두 광원 경로는 같은 보간 행렬에서 WB도 구하며, 그 white가 카메라 모델 밖이면 행렬과 WB를 함께 legacy로 되돌린다. 단일 행렬, 알 수 없거나 같은 온도의 광원, 해석 불가능한 보조 metadata는 기존 선택을 유지한다. 이 색 변화는 리뷰 PR과 텅스텐 RAW/JPEG 실사진 판단 대상이며 완전한 SDK renderer/CMS를 뜻하지 않는다.

## 수명과 검증

새 sample API는 배열을 노출하지 않는다. decoder는 publish 후 sample을 변경하지 않고 Core는 별도 작업 배열을 만든다. 전환 중 legacy adapter의 mutable 배열은 trusted Core에 남아 있으므로 read-only 규칙이 필요하다. view가 parent 배열을 붙잡고 있어 cache clear 후에도 유효하다. heap-only frame에 `close()`나 reference counting을 추가하지 않는다.

생성 시 채널/stride/sample count, crop, orientation, calibration과 legacy range를 검사한다. sample range 검사에는 전체 배열 1회 순회가 필요하며 대형 배열 복사는 추가하지 않는다. shape 오류는 성공 결과로 publish하지 않는다. 이 PR에서는 range 검사를 유지하며 제거 여부는 대형 사진 비용을 측정한 뒤 판단한다.

## 요청·오류·취소의 경계

후속 dispatch는 `DecodeRequest`의 원본 파일, 명시적 input/sample-byte 상한과 취소 callback을 받는다. default는 기존 제한을 유지하며 폰의 안전 heap 예산을 임의로 선언하지 않는다. sample-byte 상한은 codec scratch/GPU/전체 live heap 상한과 다르다.

비압축 strip/tile은 선언한 byte count 대신 실제 행에 필요한 바이트가 파일에 있는지 검사한다(PR #7 이전 호환성). lossless JPEG는 기존처럼 overstated byte count를 EOF에서 자르고, JPEG XL은 선언한 전체 범위를 검사한다.

취소 callback은 worker에서도 호출될 수 있어 thread-safe해야 하며 취소 상태를 유지해야 한다. 표준 `CancellationException`은 I/O 오류로 감싸지 않는다. 행/tile 및 codec 앞뒤에서 협력적으로 검사하며 jxlatte 실행 중 worker 강제 종료를 약속하지 않는다. timeout을 취소 구현으로 사용하지 않는다. 앱의 cache gate와 off-main scheduling은 유지한다. 기본 thread interrupt 검사는 호출된 스레드만 보므로 ForkJoin worker에서 원래 호출자의 취소를 감지하지 못한다. M3-A에서는 요청 소유 Job의 `ensureActive()`를 캡처하고 로딩 상태 정리를 검증해야 한다. 취소되지 않은 UI Job에 임의의 interrupt 취소 예외를 전달하는 연결은 하지 않는다. 기존 UI 요청까지 취소 callback을 전달하는 수명 통합은 M3-A의 별도 검증 대상이다.

## 검증과 남은 gate

최종 로컬 검사: JVM 546개(542 통과, 4 선택 검사 skip), debug/release Lint, androidTest APK 컴파일, R8 release APK와 Room schema diff 통과. 실제 사진·기기 heap/RSS·OOM·JXL header preflight 비용은 측정하지 않았다. 기본 요청은 새 폰 자원 예산이 아니며, codec 내부/ICC/GPU allocation의 전체 상한은 후속 측정·승인 대상이다.
