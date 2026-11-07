# 수동 관측용 HMG 진단 소스

이 디렉터리는 **미설치·미패키징 소스**다. `.disabled` 표시와 `diagnostics=false` 기본값을 유지한다. 차량에 복사하거나 활성화하는 명령은 제공하지 않는다. K8 펌웨어, 백업/복구 방법, 실제 Hook 상태, 정차 테스트와 롤백 절차를 확인하고 사용자 설치 승인을 받은 다음 단계에서만 실차에 적용한다.

`PassiveDiagnostics`는 HMG-API 1.0.2의 Java 모드 계약을 사용한다. `framework.hkmc_loader.automotive_service`에 `observe` 후크를 선언하고, 후크가 제공하는 Context에서 순정 SML 상태 브로드캐스트를 받는다. 색상/밝기를 요청하거나 변경하는 메서드, CAN/LIN/Binder 명령, Bluetooth 연결·송신, 파일·펌웨어 변경은 없다. 상류 구현을 복사하지 않고 공개 API 호출부를 독자 작성했다. 근거는 [HMG_API.md](../docs/research/HMG_API.md)에 고정 SHA와 코드 위치로 기록했다.

수신 시 HMG 모드 활성 상태와 boolean `diagnostics`를 확인한다. action/extra/길이를 검사하고, 확인된 필드가 담긴 상태만 코어 `SmlStatus`로 파싱한다. 원본 Intent, 장치 이름·주소·VIN·인증정보는 로그에 넣지 않는다. 알려지지 않은 추가 필드를 식별자로 로그하지 않는다. 9~64바이트만 받아 원시 채널을 유지한다. 알 수 없는 테마를 RGB로 변환하지 않는다.

수신기는 별도 HandlerThread 하나에서 작동하며, 250ms 동안 최신 상태 하나만 남기고 이전 로그와 완전히 같은 값은 생략한다. **진단 샘플 로그는 최대 4회/초이고 중간 변경은 생략**된다. 이 간격은 조명 동기화 지연이나 BLE 제어 주기가 아니다. 잘못된 데이터와 초기화 실패 로그는 각각 한 번만 남긴다. 예외 원문/스택을 기록하지 않으며 등록 실패가 순정 메서드에 전파되지 않도록 처리한다. OFF/설정 콜백을 해당 프로세스에서 받으면 수신기를 해제하고 작업 스레드를 종료한다.

## 실행 수명 제한

- 차량별 맵에 후크가 있어야 하며, 현재 상류에 K8/GL3 한국 맵은 없다. 모드 설치 성공이 후크/이벤트 성공을 뜻하지 않는다.
- 진단 설정은 시작 전에 반영하고 재시작이 필요하다. 후크 대상은 Android 초기화 중 한 번 실행될 수 있다. 이미 실행된 뒤 새로 모드를 켜거나 live-load하면 Context를 얻지 못할 수 있다.
- HMG 1.0.2의 Live 갱신은 앱 프로세스 중심이다. system_server에서 설정/OFF 콜백이 즉시 전파된다는 증거가 없어 **화면 스위치만으로 즉시 중단된다고 보장하지 않는다**. 현재 소스는 실차에 활성화하지 않는다. 향후 승인된 테스트에서 중단 확인과 재시작 절차를 먼저 검증한다.
- QuickBoot/화면 복귀는 cold boot와 다르다. 시동마다 수신기가 재등록되거나 최신 상태가 자동으로 도착한다고 가정하지 않는다. 초기 상태 요청을 일부러 보내지 않으므로 순정 메뉴 변경이 없으면 아무 로그도 나오지 않을 수 있다.
- 브로드캐스트의 송신자 인증을 검증하는 경로는 없다. 수신값은 진단 관측값이며 신뢰된 차량 이벤트로 송신에 연결하지 않는다.

## 컴파일 계약

언어는 Java 7, Android API 19를 기준으로 한다. 필요한 입력은 다음과 같다.

1. `android.jar` (API19의 `android.content`, `android.os` 및 `org.json` 서명).
2. HMG-API 1.0.2 실제 runtime을 컴파일한 클래스 디렉터리: `hmgapi.hook.ModEntry`, `ModContext`, `MethodHook`, `MethodHook.Param`.
3. 프로젝트의 `io.github.k8ambient.core.SmlStatus` 클래스.

상류 `build.sh`의 `runtime()`은 `core/runtime/stubs`를 compile-only로 만든 후 Android jar와 함께 실제 runtime을 컴파일한다. `mods()`는 그 runtime 클래스와 Android jar로 모드를 컴파일하고 D8 `--min-api 19`로 변환한다. [고정 버전 build.sh](https://github.com/VysseCodes/HMG-API/blob/180185de2c57afb2cca4858d0be7828e8f1051d6/build.sh#L32-L70). JVM class 검사는 DEX 패키징·서명·차량 설치 성공을 증명하지 않는다. 여기에는 DEX/JAR/ZIP/서명키/설치 스크립트를 넣지 않는다.
