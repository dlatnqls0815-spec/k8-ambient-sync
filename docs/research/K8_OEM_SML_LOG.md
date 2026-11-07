# 순정 SystemService의 SML 원시 로그 경로

사용자는 이전에 RGB와 밝기를 직접 변경했다. 확보한 HMG 로그에는 전달 시도와 오류만 있었고,
숫자 보존 경로를 확인하기 위해 기존 차량 시스템 사본을 로컬에서 조사했다.

## 입력과 분석 범위

기존 K8 수집본의 automotive-service.odex와 automotive.odex를 사용했다.
각 파일의 MD5가 이번 차량에서 수집한 car.json의 동일 파일 항목과 일치했다.
원본·추출 DEX·전체 SDK 출력은 private/oem-sml-analysis에만 보관한다.

REA inspect_android_package는 caller-supplied JADX provider가 설정되지 않아 실행되지 않았다.
기존 Android SDK35.0.1의 dexdump와 로컬 DEX 메타데이터 읽기를 사용했다.
ODEX 내부 DEX의 최적화된 class flags로 일반 검증이 실패해 분석용 파생본에 dexdump -j를 사용했다.
일부 Dalvik quick opcode는 현대 도구에서 unknown으로 출력됐다.
아래 내용은 선택한 문자열·태그·정적 호출 위치에 근거하며 전체 제어 흐름의 복원을 주장하지 않는다.

## 확인한 출력 후보

| 경로 | 태그와 문구 | 의미 |
|---|---|---|
| MicomEventHandler의 SML 응답 | SystemService / `[MicomEventHandler.handleMessage] SYS_SML_STATUS_M_RESP:` | 응답 byte[]를 Utils.byteToHexString에 전달하고 Slog.d에 출력하는 코드 |
| SystemService의 상태 처리 | SystemService / `SYS_SML_STATUS_M_RESP:` | DBG 조건 아래 출력되는 별도 응답 코드 |
| setSMLRGB | SystemService / `[SystemService.setSMLRGB] red:` | 전달된 red/green/blue byte[]를 출력하는 설정 요청 기록 |
| setSMLConfiguration | SystemService / `[SystemService.setSMLConfiguration] mode:` | 전달된 mode/data를 출력하는 설정 요청 기록 |

automotive-service DEX에서 응답 문구 const-string은0x19e034,
Utils.byteToHexString 호출은0x19e040, Slog.d 호출은0x19e058에서 확인했다.
RGB 설정 method code_item은0x1b51ac, configuration 설정 code_item은0x1b507c다.
이는 추출 DEX 파일 시작 기준 byte offset이다.
automotive의 Utils.byteToHexString code_item0x19db7c에서 `0x`, byte2hex 호출, 공백을 사용하는
출력 후보를 확인했다. 실제 캡처 형식은 수집 후 대조한다.

설정 요청 로그는 해당 시점의 요청 인자다. 상태 응답·실제 발광·영구 저장값은 각각 대조해야 한다.
원시 RGB나 밝기 범위를 K8 정규화 값으로 바로 확정하지 않는다.

## USB 수집 동작

Slog 출력에 맞춰 system 버퍼와 SystemService 태그를 명시한다.

```text
logcat -b system -d -v brief -s SystemService:D '*:S'
```

셸 builtin의 read/case로 위3종 문구만 골라 USB의 logs/oem-sml.log에 쓴다.
읽기 종료 코드는 logs/oem-reader-exit.txt, 줄 수는 logs/oem-sml-count.txt와 collection.txt에 남긴다.
원본은 USB/private에 보관하며 태그의 다른 메시지는 최종 로그에 보존하지 않는다.
필터와 버퍼 옵션의 의미는 [Android 공식 Logcat 문서](https://developer.android.com/tools/logcat)에 있다.

사용자가 이전에 수행한 색상·밝기 변경은 완료 상태로 유지한다.
다음 차량 실행에서는 기존 캐시를 먼저 수집한다. cache0건이면 실제 값 확보는 미완료다.
그때는 출력 조건과 로그 수명부터 확인하고 후속 관측을 결정한다.
이번 스크립트는 읽기·USB 저장 범위이며 설치·Hook Map 변경·로그 삭제·디버그 속성 변경·
상태 요청·BLE/차량 조명 명령을 포함하지 않는다.
