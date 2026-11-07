# 공개 엠비언트 프로젝트 소스 조사

확인일: 2026-11-07 (Asia/Seoul). 공개 저장소를 `.research/`에 복제한 뒤 소스, 패키지 정의, 최근 커밋을 읽었다. 가져온 빌드·설치 스크립트는 실행하지 않았다. 아래의 차량 동작은 **upstream 작성자의 보고**이며 이 프로젝트에서 재현한 결과가 아니다.

## 조사한 스냅샷과 최근 변경

| 저장소 | 확인한 HEAD | 작성 시각 / 최근 변경 |
|---|---|---|
| [Light Vibes](https://github.com/dibble9012/light-vibes) | `3bb3fd0eee00eb3c9edc3d6d8e09607099dc3685` | 2026-11-05 15:49:37 -05:00, `Light Vibes 0.5.0`; 복제한 이력에는 1개 커밋 |
| [Gen5W Ambient Sound](https://github.com/nmz787/gen5w-ambient-sound) | `90f723a3437e875557932a54efeaa30007f67aee` | 2026-10-29 02:28:04 -07:00, 문서 명확화. 직전 `e736952dc85280a2c7db267c96e8d3e843a422eb`은 NEMESIS 패키징, `d7d68091983a4e955dc62587d28da9d1450fef43`은 GUI·주파수/색상 설정·시작/중지 구현 |
| [HMG-Seogaemo](https://github.com/seogaemo/hmg-seogaemo) | `26eeb97e0a745528b438d7a946be5450fadbfd04` | 2026-11-07 10:25:07 +09:00, 0.1.46. 직전 `b074ca64eedba849f59de97bb5cbc6b0122c3ed0` / `d75f101cd49c61d578735fd7a2c053fc88459653`은 무선 projection의 junction view 유지 관련 |

HEAD는 이후 바뀔 수 있다. 재검증은 위 SHA를 기준으로 한다. Light Vibes 외 두 저장소는 최근 10개 커밋 깊이로 복제했다.

## 1. Light Vibes: 상태 읽기 후보가 있으나 실시간 변경 구독은 아님

- 구현체는 Android 서비스 앱 중심이 아니라 C 데몬 `src/lightvibesd.c`와 설정용 Java Activity다. `mod/mod.json:13-16`은 HMG-API가 Android 준비 후 root 서비스 `lights`를 시작하고 종료 시 다시 시작하도록 선언한다. 속도는 읽기 권한만 선언한다. [패키지 정의](https://github.com/dibble9012/light-vibes/blob/3bb3fd0eee00eb3c9edc3d6d8e09607099dc3685/mod/mod.json#L1-L16)
- `read_lamp()`는 `com.hkmc.micom.system`에 `requestSMLStatus`에 해당하는 Binder transaction 76을 요청하고 **400ms 대기한 뒤** `SystemService` logcat의 마지막 `SYS_SML_STATUS_M_RESP`를 읽는다. `parse_status_line()`의 결과에서 각 RGB를 high/low 두 바이트로 조합하고 brightness raw와 drive-mode 값을 꺼낸다. 반환값을 직접 받는 getter나 이벤트 리스너 구현이 아니다. [파서·조회](https://github.com/dibble9012/light-vibes/blob/3bb3fd0eee00eb3c9edc3d6d8e09607099dc3685/src/lightvibesd.c#L332-L397)
- 이 readback은 RGB **16bit** 값을 취급한다. LED 컨트롤러의 8bit RGB로 변환할 때 단순 65535→255 스케일링 또는 하위 바이트 채택을 임의 결정하면 안 된다. 같은 upstream의 실제 색상 wheel은 0..255이지만 K8의 표현 범위와 응답은 미확인이다.
- 위 로그 조회에는 새 응답의 timestamp/요청 correlation 검증이 없다. 오래된 응답을 현재 상태로 오인할 수 있으므로 그대로 실시간 동기화 입력으로 재사용하지 않는다. 400ms 고정 대기는 종단 200ms 목표와도 맞지 않는다.
- `light_rgb()`는 transaction 78 / 세 개의 2-byte 배열, `light_config()`는 transaction 77 / mode와 data를 보낸다. 밝기는 mode 5, UI level에 1을 더한다. drive-mode 연동은 mode 9로 바꾼다. **이들은 순정 조명 쓰기 경로**이므로 읽기 전용 진단에 포함하면 안 된다. K8의 ABI가 같다는 증거도 없다. [쓰기 함수](https://github.com/dibble9012/light-vibes/blob/3bb3fd0eee00eb3c9edc3d6d8e09607099dc3685/src/lightvibesd.c#L310-L330)
- `snapshot_base()` / `saved_write()`는 사용자의 색상·밝기·drive-mode와 dirty 표시를 저장한다. `restore_base()`는 이를 순정 조명에 **다시 쓴다**. `startup_restore()`는 비정상 종료 상태일 때 최대 20번 복원을 시도한다. K8 Ambient Sync는 순정을 관찰하는 역할이므로 이 takeover/restore 동작을 이식하지 않는다. [저장·복원](https://github.com/dibble9012/light-vibes/blob/3bb3fd0eee00eb3c9edc3d6d8e09607099dc3685/src/lightvibesd.c#L347-L434), [재시작 복원](https://github.com/dibble9012/light-vibes/blob/3bb3fd0eee00eb3c9edc3d6d8e09607099dc3685/src/lightvibesd.c#L848-L868)
- `drive()`의 변경값 비교, 최소 전송 간격, 한 번에 한 명령만 진행하는 구조는 설계 참고가 된다. 해당 코드는 전송 프로세스를 띄운 뒤 값을 보냈다고 기록하므로 사제 장치의 실제 ACK 검증을 대신하지 않는다. [중복 제거·속도 제한](https://github.com/dibble9012/light-vibes/blob/3bb3fd0eee00eb3c9edc3d6d8e09607099dc3685/src/lightvibesd.c#L626-L645)
- 음악은 ALSA card0 device2 출력 루프백과 2048-point FFT를 사용한다. 루프백 mixer를 바꾸는 작업이므로 초기 진단 범위 밖이다. 통화/Siri가 재생 rate를 바꿀 때 capture를 놓는 방식은 upstream 특정 장치 관찰에 의존한다. [코드 설명](https://github.com/dibble9012/light-vibes/blob/3bb3fd0eee00eb3c9edc3d6d8e09607099dc3685/src/lightvibesd.c#L1-L25), [루프백 조작](https://github.com/dibble9012/light-vibes/blob/3bb3fd0eee00eb3c9edc3d6d8e09607099dc3685/src/lightvibesd.c#L437-L452)
- 속도 모드는 `read_speed()`가 abstract Unix socket `hmgapi-vehicle`에 `read speed`를 보내고 500ms 간격으로 조회한다. 15초간 값을 못 받으면 기존 순정 색상으로 되돌린다. CAN 프레임을 직접 구성하는 구현이 아니다. [속도 조회](https://github.com/dibble9012/light-vibes/blob/3bb3fd0eee00eb3c9edc3d6d8e09607099dc3685/src/lightvibesd.c#L712-L747), [속도 모드](https://github.com/dibble9012/light-vibes/blob/3bb3fd0eee00eb3c9edc3d6d8e09607099dc3685/src/lightvibesd.c#L768-L791)
- README가 보고하는 실차는 **2023 EV6 GT-Line 미국판 / firmware 260507**이다. 속도 모드, 전원 상실 후 색상 복원 등은 upstream도 실차 미검증으로 구분한다. K8 한국판·주행 모드·자동 dim·ON/OFF에 대한 검증은 없다. [시험 범위](https://github.com/dibble9012/light-vibes/blob/3bb3fd0eee00eb3c9edc3d6d8e09607099dc3685/README.md#L85-L111)

## 2. Gen5W Ambient Sound: 마이크 반응 조명 제어, 상태 관찰 기능 없음

- `MicAmbient.main()`은 reflection으로 `android.os.ServiceManager.getService()`를 호출해 Micom Binder를 얻고 interface descriptor를 확인한다. `brightness()` / `color()`가 transaction 77 / 78을 직접 보내며 RGB는 `{0, component}` 두 바이트다. 현재 RGB나 밝기를 조회하거나 순정 UI 변경을 구독하지 않는다. [Binder·AudioRecord 구현](https://github.com/nmz787/gen5w-ambient-sound/blob/90f723a3437e875557932a54efeaa30007f67aee/MicAmbient.java#L10-L47)
- `AudioRecord` 16kHz mono로 2048 sample을 읽는다. `BandAnalyzer`의 FFT는 주파수별 에너지, 대역별 multiplier, 1.3배 전환 hysteresis를 사용한다. `MicAmbient`는 색상·밝기 각각 250ms 간격 및 변경값 비교를 적용한다. 자동 밝기는 **음량으로 만든 값**이며 순정 자동 밝기 읽기가 아니다. [FFT](https://github.com/nmz787/gen5w-ambient-sound/blob/90f723a3437e875557932a54efeaa30007f67aee/BandAnalyzer.java#L1-L70), [worker loop](https://github.com/nmz787/gen5w-ambient-sound/blob/90f723a3437e875557932a54efeaa30007f67aee/MicAmbient.java#L48-L89)
- GUI의 `MainActivity.control()`은 NEMESIS를 통해 UID 1000 worker를 시작/중지한다. detached worker와 PID birth 검증으로 중복 실행을 막지만, manifest에는 Activity만 있고 부팅 receiver/service가 없다. 패키지 `NEMESIS.data`도 application entry만 선언한다. **시동 자동 실행을 이미 제공한다고 볼 수 없다.** [GUI 제어](https://github.com/nmz787/gen5w-ambient-sound/blob/90f723a3437e875557932a54efeaa30007f67aee/MainActivity.java#L149-L165), [Manifest](https://github.com/nmz787/gen5w-ambient-sound/blob/90f723a3437e875557932a54efeaa30007f67aee/AndroidManifest.xml#L1-L8), [패키지](https://github.com/nmz787/gen5w-ambient-sound/blob/90f723a3437e875557932a54efeaa30007f67aee/package/ambient-sound/NEMESIS.data#L1-L13)
- 종료 cleanup은 밝기를 1로 바꾸고 마지막 색상은 남긴다. 원래 색상/밝기를 복원하지 않는다. 전원 상실·강제 종료 때 cleanup을 보장하지 않는다. 순정을 보존하는 observer의 기반으로 그대로 사용하지 않는다. [의도와 한계](https://github.com/nmz787/gen5w-ambient-sound/blob/90f723a3437e875557932a54efeaa30007f67aee/package/ambient-sound/bin/ambient_party_mode.sh#L24-L44), [cleanup](https://github.com/nmz787/gen5w-ambient-sound/blob/90f723a3437e875557932a54efeaa30007f67aee/package/ambient-sound/bin/ambient_party_mode.sh#L280-L309)
- worker shell은 NEEV23 미국판 ABI를 전제로 한다. K8 호환 map, BLE GATT 연동, CAN/LIN 직접 처리, 통화 중 마이크 양보에 대한 구현 증거는 이 소스에서 확인하지 못했다. [ABI 전제](https://github.com/nmz787/gen5w-ambient-sound/blob/90f723a3437e875557932a54efeaa30007f67aee/package/ambient-sound/bin/ambient_party_mode.sh#L1-L4)

## 3. HMG-Seogaemo: 대체 hook host, 현재 설치와 혼합 금지

- 공개 README는 설치 전에 **HMG-API와 HMG Leafia Plus 제거**를 요구한다. `pf_conflicts()`도 HMG-API 설치/잔여물을 block 상태로 보고한다. 사용자의 HMG-API 1.0.2 환경에 추가 설치하면 안 된다. [README](https://github.com/seogaemo/hmg-seogaemo/blob/26eeb97e0a745528b438d7a946be5450fadbfd04/README.md#L1-L17), [충돌 검사](https://github.com/seogaemo/hmg-seogaemo/blob/26eeb97e0a745528b438d7a946be5450fadbfd04/nemesis/package/lib/preflight.sh#L111-L140)
- `core/hookpoints/framework.json`에 `framework.system_service.sml_configuration` / `sml_rgb` / `sml_status`가 있다. 실제 이름은 각각 `setSMLConfiguration(int,int)` / `setSMLRGB(byte[],byte[],byte[])` / `requestSMLStatus()`다. 마지막 메서드도 **void 요청**이다. catalogue에 있다는 사실은 RGB getter 또는 변경 이벤트 제공의 증거가 아니다. [세 hookpoint](https://github.com/seogaemo/hmg-seogaemo/blob/26eeb97e0a745528b438d7a946be5450fadbfd04/core/hookpoints/framework.json#L1738-L1772)
- `hmgapi.hook.HmgApi.zygoteInit()`은 product/onebin/Android identity와 원본 파일 MD5가 맞고 검증한 map일 때만 hook을 활성화한다. `handleBindApplication` 뒤에 module의 `appLoaded()`를 부른다. `load()`는 module별 class loader와 entry를 만들며 실패를 기록한다. 순정 firmware의 구체 구현/호출 프로세스를 검증하기 전에는 interface catalogue만으로 observer를 설치할 수 없다. [host gate와 dispatch](https://github.com/seogaemo/hmg-seogaemo/blob/26eeb97e0a745528b438d7a946be5450fadbfd04/core/hook/src/hmgapi/hook/HmgApi.java#L67-L124), [module load](https://github.com/seogaemo/hmg-seogaemo/blob/26eeb97e0a745528b438d7a946be5450fadbfd04/core/hook/src/hmgapi/hook/HmgApi.java#L128-L155)
- watcher는 Android boot 완료 뒤 enabled module의 선언에 따라 `boot` / `screen_on`을 전달하고, 실패하면 boot/event별 최대 6회 재시도와 5..60초 backoff를 사용한다. 이는 일반 module lifecycle이며 ambient observer 구현은 아니다. [watcher](https://github.com/seogaemo/hmg-seogaemo/blob/26eeb97e0a745528b438d7a946be5450fadbfd04/nemesis/package/svc/watcher.sh#L46-L130)
- 이번 HEAD의 bundled map은 `maps/hyundai/ioniq5/kor/260902/` 하나다. map README는 기존 324개 point의 2026-11-03 실차 reflection과 이후 추가 point의 미검증 상태를 구분한다. Reflection 성공도 실제 ambient 기능 성공을 뜻하지 않는다. **K8/GL3 map 또는 K8 실차 결과는 없다.** [map의 증거 범위](https://github.com/seogaemo/hmg-seogaemo/blob/26eeb97e0a745528b438d7a946be5450fadbfd04/maps/hyundai/ioniq5/kor/260902/README.md#L1-L27)
- 이 fork는 HMG-API catalogue/runtime 일부의 출처와 baseline을 `UPSTREAM.md`에 기록한다. 새 fork HEAD가 설치된 HMG-API 1.0.2와 동일하다는 의미가 아니다. [출처·차이 관리](https://github.com/seogaemo/hmg-seogaemo/blob/26eeb97e0a745528b438d7a946be5450fadbfd04/UPSTREAM.md#L1-L64)

## 라이선스와 재사용 범위

| 프로젝트 | 확인한 조건 | 이번 처리 |
|---|---|---|
| Light Vibes | repository `LICENSE`, `mod/mod.json`에 AGPL-3.0 명시 | 소스 코드를 프로젝트에 복사하지 않았다. 구현 시 해당 소스를 복사/변형하면 저작권 고지·라이선스·Corresponding Source 조건을 검토하고 유지한다. |
| Gen5W Ambient Sound | 이 HEAD의 tracked tree에 LICENSE/COPYING/NOTICE 파일이 없고 README/Java/shell의 명시적 재사용 허락을 찾지 못함 | 공개 열람과 코드 재사용 허락을 구분한다. 코드·색상표·빌드 스크립트는 복사하지 않는다. |
| HMG-Seogaemo | repository `LICENSE`, README, NOTICE에 AGPL-3.0. hook source 일부는 AGPL-3.0-or-later 표기 | 출처와 라이선스 조건을 유지해야 한다. 이번에는 문서 조사만 했으며 runtime/installer를 가져오지 않았다. |

참고: [Light Vibes LICENSE](https://github.com/dibble9012/light-vibes/blob/3bb3fd0eee00eb3c9edc3d6d8e09607099dc3685/LICENSE), [Ambient Sound 전체 tree](https://github.com/nmz787/gen5w-ambient-sound/tree/90f723a3437e875557932a54efeaa30007f67aee), [HMG-Seogaemo NOTICE](https://github.com/seogaemo/hmg-seogaemo/blob/26eeb97e0a745528b438d7a946be5450fadbfd04/NOTICE.md).

## K8 Ambient Sync에 적용할 결론

1. 우선 경로는 기존 HMG-API 환경 안의 **수동 상태 관찰**이다. 순정 setter를 대신 실행하거나 Light Vibes의 takeover/restore를 이식하지 않는다.
2. 후보는 순정 UI의 setter 호출 인자 관찰과 Micom 상태 응답 관찰이다. setter 관찰만으로 MCU 자율 변경, 자동 dim, drive-mode 결과, 초기 상태, ON/OFF를 모두 알 수 있다고 가정하지 않는다.
3. 상태 snapshot의 공개 선례는 있으나 request와 logcat 파싱 방식이며, fresh 응답 식별·K8 ABI·값 범위·성능 측정이 추가로 필요하다. 초기에는 기존 로그를 오프라인 분석한다.
4. 세 프로젝트에는 Lotus Lamp X용 BLE client나 K8 Gen5W의 BLE Central/GATT 실증이 없다. 조명 Binder 호출이 가능하다는 사실로 BLE 직접 연결을 판정할 수 없다.
5. 음악/속도/순환 모드는 기본 관찰→사제 LED 동기화가 실차에서 검증된 뒤 개발한다. 지금 단계에서 기존 모듈을 차량에 설치하거나 순정 설정을 변경할 이유가 없다.

생략한 검증: upstream 빌드/설치, 전체 이력 분석, 실차 및 BLE 장치 시험. 위험: 다른 차종의 Binder transaction/응답 형식을 K8에 그대로 적용하면 오동작할 수 있다.
