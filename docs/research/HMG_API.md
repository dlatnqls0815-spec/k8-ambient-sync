# HMG-API / NEMESIS 소스 조사

조사일: 2026-11-07 (한국). 공개 Git 저장소를 로컬 `.research/`에 수집해 실제 Java/C 소스와 최근 커밋을 읽었다. 설치 스크립트 실행, 차량 접속, 펌웨어 변경은 하지 않았다. 아래의 실차 검증 표시는 **상류 개발자의 US EV6 검증 기록**이며 이 프로젝트가 K8에서 검증했다는 뜻이 아니다.

## 고정한 소스와 변경 이력

| 소스 | 조사 커밋 | 커밋 날짜 | 라이선스 |
|---|---|---|---|
| [VysseCodes/HMG-API](https://github.com/VysseCodes/HMG-API) | `180185de2c57afb2cca4858d0be7828e8f1051d6` | 2026-11-04 | AGPL-3.0 |
| [xwtk/hmg_nemesis](https://github.com/xwtk/hmg_nemesis) | `345f171850c35742f84f0fbe511d0b7ab50364fc` | 2026-11-04 | AGPL-3.0 |

HMG-API `v1.0.2`는 annotated tag이고, tag object `765d2718b11325f030411184ffa941577ddd7bd1`을 역참조한 커밋은 위 HEAD와 같다. 사용자가 설치했다고 한 1.0.2와 조사 대상 버전은 일치하지만, 차량에서 설치 파일·Hook 활성 상태까지 확인한 것은 아니다.

최근 HMG-API 변경: `180185d` 1.0.2 병합, `8aa0656` 1.0.2 릴리스, `87dc51e` 부팅 수정 병합, `26332e5` 설치 시 부팅 완료 표시/NEMESIS 1.16.7 검토, `d519711` US EV6 부팅 테스트 수정, `0ea8e23` 분리된 부팅 실행. [CHANGELOG 1.0.2](https://github.com/VysseCodes/HMG-API/blob/180185de2c57afb2cca4858d0be7828e8f1051d6/CHANGELOG.md#L3-L20)는 NEMESIS 시작이 약 2분 지연되던 문제를 고친 기록이다. 엠비언트/BLE 기능 추가 릴리스라고 해석하지 않는다.

최근 NEMESIS 변경: `345f171` 1.16.7, `f2b34ca` 1.16.6 상세 화면 수정, `0f02c02` 1.16.6, `65c05f0` 1.16.5, `da77eef` 1.16.4 서비스 중지/지연 시작 등. [README 변경 기록](https://github.com/xwtk/hmg_nemesis/blob/345f171850c35742f84f0fbe511d0b7ab50364fc/README.md#L94-L133).

## 핵심 결론: 상태 이벤트는 있지만 공개 ambient 읽기 API는 없다

1. **상태 이벤트 경로가 실제 소스에 있다.** `hmgapi.hook.LightRelay.start(Context,Handler)`가 system_server 안에서 `com.mobis.intent.action.SML_STATUS_CHANGED`를 수신하고 `com.mobis.intent.extra.SML_STATUS`의 `byte[]`를 읽는다. `send(byte[])`는 이를 `light <hex>\n` 형태로 `hmgapi-vehicle` 로컬 소켓에 전달한다. [LightRelay.java:13–44](https://github.com/VysseCodes/HMG-API/blob/180185de2c57afb2cca4858d0be7828e8f1051d6/core/runtime/java/hmgapi/hook/LightRelay.java#L13-L44).
2. **차량 서비스 내부에는 최신 상태가 저장된다.** `CarCallbacks.lightStatus(String)`는 최소 9바이트를 받아 `sml`에 보관한다. `Hub.CarServices.light(byte[])`는 R/G/B를 각각 big-endian 2바이트로, 밝기를 7번째 바이트로 읽는다. [CarCallbacks.java:209–220](https://github.com/VysseCodes/HMG-API/blob/180185de2c57afb2cca4858d0be7828e8f1051d6/core/runtime/java/hmgapi/vehicle/CarCallbacks.java#L209-L220), [Hub.java:380–400](https://github.com/VysseCodes/HMG-API/blob/180185de2c57afb2cca4858d0be7828e8f1051d6/core/runtime/java/hmgapi/vehicle/Hub.java#L380-L400).
3. **일반 모드에서 `api.vehicle("ambient…")`라고 부를 인터페이스는 없다.** `Vehicle.READINGS` 목록에 ambient/RGB/밝기는 없고, `ambient_light`는 쓰기 명령으로만 선언되어 있다. [Vehicle.java:33–92](https://github.com/VysseCodes/HMG-API/blob/180185de2c57afb2cca4858d0be7828e8f1051d6/core/runtime/java/hmgapi/engine/Vehicle.java#L33-L92). 위 소켓은 uid 0/1000만 받고 읽기 요청은 uid 0만 받는다. 이벤트 구독용 공개 소켓으로 사용하면 안 된다. [Hub.java:97–118](https://github.com/VysseCodes/HMG-API/blob/180185de2c57afb2cca4858d0be7828e8f1051d6/core/runtime/java/hmgapi/vehicle/Hub.java#L97-L118).
4. 상류 실차 기록에 따르면 vehicle-service의 app_process에서 직접 등록한 receiver는 수신되지 않았고 system_server receiver는 수신되었다. 일반 APK 수신 여부도 별도로 확인해야 한다. [vehicle-commands.md:137–144](https://github.com/VysseCodes/HMG-API/blob/180185de2c57afb2cca4858d0be7828e8f1051d6/docs/vehicle-commands.md#L137-L144).

## 상태의 의미와 구현 제한

| 상태 | 소스에서 확인한 표현 | K8 구현 시 취급 |
|---|---|---|
| RGB | raw unsigned 16-bit × 3 | 우선 원시값 그대로 보관 |
| 실제 사용자 RGB | US EV6: 채널 0–255 | K8에서 실제 색상과 대조하기 전 미검증 |
| 테마 색상 | 채널 >255는 테마 코드 | 8비트 자르기/전체 범위 비례 축소 금지; RGB 변환 미확인으로 처리 |
| 밝기 | US EV6: raw 1 = OFF, 2–11 = 메뉴 1–10 | 해당 차종 전용 해석으로 격리 |
| 주행 중 감광 | byte[7]에 상태가 있다는 소스 주석 | 각 값의 의미·실제 LED 밝기 영향 미확인 |
| 주행 모드 | byte[8]에 상태가 있다는 소스 주석 | 값 대응·테마 실제 RGB 미확인 |
| 마지막 저장 색상 | HMG 내부 임대 제어의 복원값은 있음 | 영구 저장된 순정 색상을 독립적으로 읽는 API 미확인 |

raw 의미: [Commands.java:50–57](https://github.com/VysseCodes/HMG-API/blob/180185de2c57afb2cca4858d0be7828e8f1051d6/core/runtime/java/hmgapi/vehicle/Commands.java#L50-L57). 필드 위치와 길이: [CarCallbacks.java:209–220](https://github.com/VysseCodes/HMG-API/blob/180185de2c57afb2cca4858d0be7828e8f1051d6/core/runtime/java/hmgapi/vehicle/CarCallbacks.java#L209-L220). 테마가 보고하는 값은 상태이지 반드시 화면상 출력 RGB가 아니다. 메뉴의 마지막 저장값, 현재 출력, 자동 감광을 같은 것으로 합치지 않는다.

## RGB/밝기 쓰기와 상태 요청 — 현재 프로젝트는 호출하지 않는다

- `framework.system_service.sml_rgb` → `setSMLRGB(byte[],byte[],byte[])`.
- `framework.system_service.sml_configuration` → `setSMLConfiguration(int,int)`; 밝기는 설정 종류 5.
- `framework.system_service.sml_status` → `requestSMLStatus()`.

[후크 카탈로그](https://github.com/VysseCodes/HMG-API/blob/180185de2c57afb2cca4858d0be7828e8f1051d6/core/hookpoints/framework.json#L1549-L1586), [Hub의 실제 호출](https://github.com/VysseCodes/HMG-API/blob/180185de2c57afb2cca4858d0be7828e8f1051d6/core/runtime/java/hmgapi/vehicle/Hub.java#L380-L400).

`requestSMLStatus()`는 색상 쓰기와 다르지만 차량 서비스 요청을 발생시키므로 최초 수동 진단은 **이 호출 없이 수동 메뉴 조작의 이벤트만 관찰**한다. 상류 `CarCallbacks.ensureLight()`가 한 번 요청하는 구현이라는 사실은 별개다. [CarCallbacks.java:223–234](https://github.com/VysseCodes/HMG-API/blob/180185de2c57afb2cca4858d0be7828e8f1051d6/core/runtime/java/hmgapi/vehicle/CarCallbacks.java#L223-L234).

상류의 쓰기 임대/10Hz 제한/64색 근접값 매칭은 순정 조명을 제어하는 보호 로직이다. 사제 컨트롤러의 속도 한계나 K8의 컬러 팔레트 증거로 재사용하지 않는다. [vehicle-commands.md:128–153](https://github.com/VysseCodes/HMG-API/blob/180185de2c57afb2cca4858d0be7828e8f1051d6/docs/vehicle-commands.md#L128-L153).

## 최소 읽기 전용 진단 모드의 근거

- 런타임은 Android 4.4/API19, Java/Dalvik이다. Kotlin이나 최신 Android Automotive `CarPropertyManager`가 제공된다고 가정하지 않는다. [AndroidManifest.xml:1–7](https://github.com/VysseCodes/HMG-API/blob/180185de2c57afb2cca4858d0be7828e8f1051d6/core/app/AndroidManifest.xml#L1-L7), [장치 셸 설명](https://github.com/VysseCodes/HMG-API/blob/180185de2c57afb2cca4858d0be7828e8f1051d6/docs/mod-reference.md#L43-L50).
- 모드 계약은 `hmgapi.hook.ModEntry.zygoteInit(ModContext)`와 `appLoaded(ModContext,LoadedApp)`. [ModEntry.java:3–24](https://github.com/VysseCodes/HMG-API/blob/180185de2c57afb2cca4858d0be7828e8f1051d6/core/runtime/java/hmgapi/hook/ModEntry.java#L3-L24).
- `framework.hkmc_loader.automotive_service`를 `observe`로 선언한 후크에서 `Param.args[0]`의 `Context`를 얻는 패턴이 실제 `Input.zygote()`에 있다. 이 Context로 수신기를 등록하는 작은 독립 모드를 작성할 수 있다. 이는 **구현 경로의 근거**이며 K8에서 그 후크가 작동한다는 증명은 아니다. [Input.java:94–108](https://github.com/VysseCodes/HMG-API/blob/180185de2c57afb2cca4858d0be7828e8f1051d6/core/runtime/java/hmgapi/hook/Input.java#L94-L108).
- `mod.hook()`은 `mod.json hooks.points`의 observe/change/replace 선언을 검사한다. 순정 메서드의 인자·결과를 변경하지 않는 `observe`만 사용한다. [ModContext.java:37–48](https://github.com/VysseCodes/HMG-API/blob/180185de2c57afb2cca4858d0be7828e8f1051d6/core/runtime/java/hmgapi/hook/ModContext.java#L37-L48).
- 진단 수신기에는 Bluetooth 송신, `vehicle.commands`, CAN/LIN 송신, 상태 요청, 파일 패치가 필요 없다. 로그는 엠비언트 필드만 추출하고 원본 intent·장치 식별자를 기록하지 않는다. 설치와 실제 동작 검증은 차량 정보·복구 경로·사용자 확인을 확보한 뒤 진행한다.

## 부팅·백그라운드·복구

HMG 모드의 `services`는 jar/class 또는 script를 지정하며 `start=android|boot|manual`, `restart=true|false`를 지원한다. 별도 NEMESIS 서비스와 부팅 스크립트를 중복 작성할 필요가 없다. [mod-reference.md:26–57](https://github.com/VysseCodes/HMG-API/blob/180185de2c57afb2cca4858d0be7828e8f1051d6/docs/mod-reference.md#L26-L57). `hmgapid.c`는 서비스별 세션/로그/데이터 폴더와 1,2,4…300초 재시도, 짧은 반복 종료 10회 후 중단을 구현한다. [hmgapid.c:942–958](https://github.com/VysseCodes/HMG-API/blob/180185de2c57afb2cca4858d0be7828e8f1051d6/core/hmgapid/hmgapid.c#L942-L958).

NEMESIS 공식 소스에서는 `cmd_watchdog()`가 svcmgr를 감시하고, `cmd_svcmgr()`가 watchdog와 등록 서비스를 감시한다. 일반 재시작 서비스에는 실패 한도가 있다. [svc.c:276–342](https://github.com/xwtk/hmg_nemesis/blob/345f171850c35742f84f0fbe511d0b7ab50364fc/Sources/native/svc.c#L276-L342), [svc.c:401–442](https://github.com/xwtk/hmg_nemesis/blob/345f171850c35742f84f0fbe511d0b7ab50364fc/Sources/native/svc.c#L401-L442). Cold boot와 QuickBoot의 시작 경로는 [공식 실행 인터페이스 문서](https://github.com/xwtk/hmg_nemesis/blob/345f171850c35742f84f0fbe511d0b7ab50364fc/02-Debugging-Execution-Interface.md#L176-L182)에 구분되어 있다. 시동 ON을 언제나 프로세스 cold start로 간주하면 안 된다.

## K8 호환성과 BLE는 별도 검증 항목

현재 HMG 맵 트리의 Kia 항목은 EV6(미국/캐나다)이고 K8/GL3 한국 맵은 없다. 상류 README의 실차 검증 범위는 US EV6 260507이며 다른 차종은 미시험이다. [README:29–49](https://github.com/VysseCodes/HMG-API/blob/180185de2c57afb2cca4858d0be7828e8f1051d6/README.md#L29-L49), [검증된 EV6 맵:1–17](https://github.com/VysseCodes/HMG-API/blob/180185de2c57afb2cca4858d0be7828e8f1051d6/maps/kia/ev6/usa/260507/car.json#L1-L17). 미지원 맵에서 HMG 후크가 비활성화되는 안전 경로도 존재한다. [HmgApi.java:55–82](https://github.com/VysseCodes/HMG-API/blob/180185de2c57afb2cca4858d0be7828e8f1051d6/core/runtime/java/hmgapi/hook/HmgApi.java#L55-L82).

NEMESIS 공식 문서는 Android `S5W_M`와 비Android `S5W_L`를 구분한다. 사용자의 설치 사실만으로 K8 펌웨어별 모든 Hook을 지원한다고 판단하지 않는다. [NEMESIS README:24–39](https://github.com/xwtk/hmg_nemesis/blob/345f171850c35742f84f0fbe511d0b7ab50364fc/README.md#L24-L39).

이 HMG-API 소스에서 `BluetoothAdapter`, `BluetoothGatt`, BLE Central/GATT Client의 사용 구현은 발견하지 못했다. `com.broadcom.bt.jar`가 EV6 맵에 있다는 사실은 정확한 칩셋, K8 BLE 지원, Android API 정상 연결, 통화·미디어·AA 동시 동작의 증거가 아니다. [EV6 맵의 파일 목록](https://github.com/VysseCodes/HMG-API/blob/180185de2c57afb2cca4858d0be7828e8f1051d6/maps/kia/ev6/usa/260507/car.json#L18-L27). 따라서 A안은 우선 검증할 후보로 유지하며 BLE 불가능 판정이나 추가 장비 구매 권고를 하지 않는다.

CAN/LIN에 대해서 HMG-API는 차내 시스템/telematics/HEV/BCM 서비스와 MICOM 경로를 사용한다. 이것을 K8의 원시 CAN/LIN 프레임 형식이나 사제 조명 직접 제어 프로토콜로 일반화할 수 없다. [Vehicle.java:7–15](https://github.com/VysseCodes/HMG-API/blob/180185de2c57afb2cca4858d0be7828e8f1051d6/core/runtime/java/hmgapi/engine/Vehicle.java#L7-L15).

## 라이선스 처리

[HMG-API LICENSE](https://github.com/VysseCodes/HMG-API/blob/180185de2c57afb2cca4858d0be7828e8f1051d6/LICENSE), [NOTICE](https://github.com/VysseCodes/HMG-API/blob/180185de2c57afb2cca4858d0be7828e8f1051d6/NOTICE.md), [NEMESIS LICENSE](https://github.com/xwtk/hmg_nemesis/blob/345f171850c35742f84f0fbe511d0b7ab50364fc/LICENSE)를 확인했다. 이 조사 문서는 인터페이스·동작을 요약했고 상류 구현 소스나 차량 바이너리를 프로젝트에 복사하지 않았다. 이후 상류 코드를 포함하거나 파생 코드를 배포할 때는 저작권·라이선스 전문·해당 소스 제공 의무를 검토하고 기록한다. 프로젝트 비공개 여부가 라이선스 검토를 대신하지 않는다.
