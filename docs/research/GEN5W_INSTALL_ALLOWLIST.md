# Gen5W 일반 APK 설치 거부 원인 — 2026-11-07

## 결론

차량이 진단 APK를 거부한 원인은 **Gen5W 패키지 관리자의 OEM 서명자 허용 목록**이다.
APK 서명 형식 문제가 아니다. NEMESIS 공식 문서
[04-Packages.md §3, `kind=apk`](https://github.com/xwtk/hmg_nemesis/blob/345f171850c35742f84f0fbe511d0b7ab50364fc/04-Packages.md#3-applications--appn)
(커밋 `345f171`, 사용자 차량 NEMESIS 1.16.4보다 최신 문서)에 다음이 적혀 있다.

- `sys.boot_completed`가 `1`인 동안 이 헤드유닛은 OEM 허용 목록에 없는 서명자를 거부한다.
- 이때 `pm`은 `INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES`를 출력한다. **한 번도 설치된 적 없는 패키지도 마찬가지다.**
- NEMESIS는 `kind=apk` 설치 때 그 `pm install -r` 호출 동안만 root로 `sys.boot_completed=0`을 만들고 원래 값으로 되돌린다.
  `BOOT_COMPLETED` 방송은 다시 보내지 않는다.

## 우리 증거와의 대조

| 문서의 조건 | 우리 시험 |
|---|---|
| 부팅 완료 상태에서 일반 `pm`/`adb install` | 정차 중 무선 ADB `install`(부팅 완료 상태) |
| 처음 설치하는 패키지도 같은 오류 | `io.github.k8ambient.bleprobe`는 차량에 없던 패키지 |
| 서명 형식과 무관 | v1/v2 APK와 SHA-1/RSA v1 전용 APK가 똑같이 거부됨 |

PC에서 두 APK의 서명이 정상이고 순정 4.4 파서에서는 이 오류가 나올 조건이 없다는 [PC 재분석 1장](PC_ANALYSIS_2026-11-07.md)과도 맞는다.
**재서명·다른 키로는 해결되지 않는다.** 설치 로그를 다시 받아 원인을 확정할 필요도 없어졌다.

## 설치 가능한 경로

| 방법 | 동작 | 판단 |
|---|---|---|
| **NEMESIS 패키지(`[app.0] kind=apk`)** | NEMESIS가 root로 허용 목록 검사를 피해 `pm install -r` 실행. APK는 `/data/nemesis/apps/<id>/`에 보관. 제거 시 `pm uninstall` | **권장.** 시스템 파티션 변경 없음. 제거 경로가 있음. 모듈 설치이므로 사용자 승인 필요 |
| 사용자가 직접 `setprop sys.boot_completed 0` 후 설치 | ADB shell 권한으로는 `sys.*` 속성을 바꿀 수 없다. 부팅 상태를 건드려 부작용 위험 | 하지 않음 |
| `/system/priv-app`에 직접 복사 | [cantcs/HKG_Gen5W_ReverseEngineer](https://github.com/cantcs/HKG_Gen5W_ReverseEngineer)가 사용한 방식. 시스템 파티션 변경 | 하지 않음(AGENTS 규칙) |
| 재서명 | 허용 목록 서명자가 아니면 결과가 같다 | 중단 |

NEMESIS 패키지는 HMG-API 파일을 바꾸지 않는 단순 APK 패키지라 HMG-API 충돌 대상이 아니다.
다만 HMG-API와 같은 NEMESIS 위에서 동작하므로 설치 전 Manager의 충돌 검사 결과를 확인한다.

## BLE 관련 공개 정보

NEMESIS 문서(README, 01~05)와 cantcs README 모두 BLE/GATT 언급이 없다.
Bluetooth 언급은 테더링(`bnep*`)뿐이다. 차량 직접 BLE 가능 여부는 여전히 미확인이며,
진단 APK가 설치되면 그 앱으로 처음 시험할 수 있다.

## 사용자 차량 NEMESIS 1.16.4에서도 동작하는가

NEMESIS 소스 `Sources/native/pkg.c`의 `pm_install_apk()`를 확인했다. 1.16.4 커밋 `da77eef`에 이미 있고,
코드 이력상 1.15 소스 공개(`db880ed`) 때부터 들어 있다. 이 코드의 주석도 원인을
"HMG PackageManagerService.installNewPackageLI가 부팅 완료 상태에서 허용 목록 OEM 서명자만 허용, 그 외는 -104"로 적는다.
동작: 설치 순간 `sys.boot_completed`를 `0`으로 바꿈 → `pm install -r` → 원래 값으로 되돌림.

## 준비한 패키지 — DEX 실행 방식(0.2.0)

다른 NEMESIS 패키지(Ambient Sound 작업자, HMG-API 모드)처럼 **APK를 설치하지 않고** 셸 앱(`kind=sh`)이
DEX를 `app_process`로 실행한다. 패키지 관리자를 거치지 않아 허용 목록과 무관하고, 끝나면 남는 앱이 없다.
처음 만든 0.1.0(`kind=apk`)은 이 방식으로 대체했다.

| 경로 | 역할 |
|---|---|
| `ble-probe-dex/src/…/HeadlessProbe.java` | 화면 없는 점검 본체(API 19). 스캔 → (주소가 있으면) 연결 → 서비스·특성 목록 → 연결 해제 |
| `ble-probe-dex/src/…/ProbeLogic.java` | 인자·주소 검사, 광고의 FFF0 판별, 주소 가림, 결과 분류. PC JVM에서 테스트 |
| `ble-probe-dex/nemesis/bin/probe.sh` | NEMESIS가 root로 실행. USB의 주소 파일을 읽고 DEX를 실행, 결과를 USB에 복사 |
| `tools/build_api19_dex.py` | 공식 API 19 `android.jar`로 컴파일 → D8 `--min-api 19` → DEX 035 |
| `tools/build_nemesis_bleprobe.py` | `NEMESIS.data` + 스크립트 + DEX를 zip으로 묶고 USB 설치 구성을 만든다 |

빌드(로컬 도구 경로는 PC마다 다르다):

```bash
python tools/build_api19_dex.py --target probe --jdk <JDK> --d8 <build-tools>/lib/d8.jar
python tools/build_nemesis_bleprobe.py
```

결과는 Git 제외 경로 `private/nemesis-bleprobe/`에 생긴다. 2026-11-08 빌드:
zip `k8ambient.bleprobe-0.2.0.zip` SHA-256 `9d0aa3c3…939b1ad`, DEX `c2a70baa…a1a4bff3`.

안전 범위:

- Bluetooth를 켜지 않는다(꺼져 있으면 `BLUETOOTH_DISABLED`로 끝). 페어링·특성/디스크립터 쓰기·차량 서비스 호출이 없다.
- 설치 훅·상주 서비스가 없다. 시스템 파일과 속성을 바꾸지 않는다. 테스트가 스크립트에 `setprop`, `pm`, `mount` 등이 없음을 확인한다.
- 대상 주소가 아닌 이름 없는 기기는 주소 앞 4바이트를 가린다. 결과 파일은 사용자 USB와 차량 `/data/local/tmp/k8-ble-probe/`에만 남는다.
- `check.py`가 순수 로직 24개 검사를 돌리고, 본체를 실제 API 19 `android.jar`로 컴파일해 4.4에 없는 API를 쓰지 않음을 확인한다.

추정으로 남은 것: API 19의 `BluetoothDevice.connectGatt`가 Context 없이(`null`) 동작한다는 점, root로 실행한
`app_process`에서 BLE 권한 검사를 통과한다는 점. 둘 다 Android 4.4 공개 구조상 가능하지만 차량에서 처음 확인한다.

## 현장 설치·실행 절차 (사용자 승인 후)

1. 기존 USB의 `NEMESIS.data`(HMG-API 패키지 목록)를 백업한다. 따로 USB를 쓰면 더 안전하다.
2. `private/nemesis-bleprobe/usb/`의 `NEMESIS.data`와 `packages/`를 USB 루트에 복사한다.
3. **(선택)** 조명 주소를 알면 USB 루트에 `k8-ble-probe.txt`를 만들고 첫 줄에 주소를 적는다
   (`k8-ble-probe.example.txt` 형식). 없으면 주변 BLE 기기 목록만 만든다.
4. 휴대폰 Lotus 앱을 종료하거나 휴대폰 Bluetooth를 잠시 끈다. 조명이 휴대폰에 연결돼 있으면 광고를 멈출 수 있다.
5. 정차·P 상태에서 USB를 꽂고 설치를 확인한다.
6. Manager → My Packages → K8 BLE Probe → Launch. 최대 약 1분 뒤 마지막 결과가 알림으로 뜬다. 화면을 사진으로 남긴다.
   주소 파일 없이 실행했다면, USB의 `k8-ble-probe-result.txt`에서 조명 주소를 확인해 3번을 하고 다시 Launch한다.
7. 끝나면 Uninstall하고 USB의 원래 `NEMESIS.data`를 복원한다. USB의 결과 파일은 PC `private/`로 옮긴다.

USB 자동 설치가 안 되면 기존 무선 ADB로 zip을 올리고 `nemesis install <zip>`을 쓸 수 있다(ADB shell 권한에서 동작 여부 미확인).

| 마지막 결과 | 의미 |
|---|---|
| `CONNECTED_FFF0_FFF3_WRITE_NO_RESPONSE` / `…_WRITE` | 차량이 조명에 직접 연결하고 쓰기 특성까지 찾음 → **A안(직접 BLE) 진행 근거** |
| `CONNECTED_FFF0_NOT_FOUND` / `CONNECTED_FFF3_NOT_FOUND` / `…_NOT_WRITABLE` | 연결은 됨. 다른 기기 주소이거나 서비스 구성이 다름. 결과 파일의 SERVICE/CHAR 목록으로 분석 |
| `SCAN_DONE devices=N` | 주소 없이 목록만 만듦. 결과 파일의 `fff0_adv=true` 또는 이름으로 조명 주소를 찾는다 |
| `SCAN_TIMEOUT` | 그 주소의 광고를 못 봄. 휴대폰 연결을 끊고 다시 시도 |
| `CONNECT_TIMEOUT` / `CONNECT_FAILED status=…` / `CONNECT_ERROR` | 발견은 했으나 연결 실패. 1~2회 재시도 후 기록 |
| `GATT_UNAVAILABLE` | 차량 Bluetooth 스택이 GATT 클라이언트를 내주지 않음 → A안 어려움, B안 검토 |
| `SCAN_REJECTED` / `SCAN_ERROR` / `NO_ADAPTER` / `BLUETOOTH_DISABLED` | 차량 BLE 스캔 불가 또는 꺼짐 → A안 어려움 |
| `DISCOVERY_*`, `PROBE_ERROR`, `DEX_MISSING` | 결과 파일의 ERROR 줄과 함께 PC에서 분석 |
