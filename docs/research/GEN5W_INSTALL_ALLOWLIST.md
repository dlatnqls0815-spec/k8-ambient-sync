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

## 준비한 패키지

`python tools/build_nemesis_bleprobe.py`가 검증된 진단 APK(SHA-256 `edbc7b9b…f4831d`)를 감싼다.
결과물은 Git 제외 경로 `private/nemesis-bleprobe/`에 생긴다.

| 파일 | 내용 |
|---|---|
| `k8ambient.bleprobe-0.1.0.zip` | `NEMESIS.data` + `app/k8-ble-probe.apk`. 같은 APK면 바이트까지 같은 zip |
| `usb/NEMESIS.data`, `usb/packages/…zip` | USB 루트에 그대로 복사하는 설치용 구성(`type=install`) |
| `SHA256SUMS.txt` | zip과 APK 해시 |

패키지 내용:

- id `k8ambient.bleprobe`, 버전 0.1.0, `[app.0] kind=apk`, Android 패키지 `io.github.k8ambient.bleprobe`.
- **root 훅 스크립트·서비스 없음.** 시스템 파일을 바꾸지 않는다. NEMESIS가 하는 일은 APK 설치와 제거뿐이다.
- 제거: Manager → My Packages → K8 BLE Probe → Uninstall. NEMESIS가 `pm uninstall`로 앱까지 지운다.
- 테스트(`tests/python/test_nemesis_bleprobe.py`)는 NEMESIS `zip.c` 규칙(zip64·암호화 금지, `..`·절대 경로 금지)과
  설명 길이 제한, USB 구성, 같은 입력의 같은 출력을 확인한다. 실제 차량 설치는 아직 하지 않았다.

## 현장 설치 절차 (사용자 승인 후)

1. 기존 USB의 `NEMESIS.data`(HMG-API 패키지 목록)를 백업한다. 따로 USB를 쓰면 더 안전하다.
2. `private/nemesis-bleprobe/usb/` 안의 `NEMESIS.data`와 `packages/` 폴더를 USB 루트에 복사한다.
3. 정차·P 상태에서 USB를 꽂는다 → NEMESIS 설치 확인을 누른다.
4. Manager에서 K8 BLE Probe를 실행하고 조명 주소를 입력 → **연결 점검**. 상태 문구를 사진으로 남긴다.
   점검 앱은 스캔·연결·서비스 목록 확인만 하며 조명에 명령을 보내지 않는다.
5. 끝나면 Manager에서 Uninstall. USB의 원래 `NEMESIS.data`를 복원한다.

USB 자동 실행이 안 되면 기존 무선 ADB로 zip을 `/data/local/tmp`에 올리고 `nemesis install <zip>`을 쓸 수 있다.
ADB shell 권한에서 이 명령이 동작하는지는 아직 확인하지 않았다.

| 결과 문구 | 의미 |
|---|---|
| `CONNECTED_FFF0_FFF3_WRITE_NO_RESPONSE` / `CONNECTED_FFF0_FFF3` | 차량이 조명에 직접 연결하고 쓰기 특성까지 찾음 → A안(직접 BLE) 진행 근거 |
| `CONNECTED_FFF0_NOT_FOUND` / `CONNECTED_FFF3_NOT_FOUND` | 연결은 됨. 서비스 구성이 다르거나 다른 기기 주소 |
| `SCAN_TIMEOUT` | 12초 안에 그 조명 광고를 못 봄. 휴대폰 Lotus 앱이 연결 중이면 끄고 다시 시도 |
| `CONNECT_TIMEOUT` / `CONNECT_REJECTED` / `CONNECT_ERROR` | 발견은 했으나 연결 실패. 1~2회 재시도 후에도 같으면 기록 |
| `SCAN_REJECTED` / `SCAN_ERROR` / `NO_ADAPTER` / `BLUETOOTH_DISABLED` | 차량 BLE 스캔 불가 또는 꺼짐 → A안 어려움, B안(Galaxy 중계) 검토 |
| `DISCOVERY_*` | 연결 후 서비스 탐색 실패. 재시도 후 기록 |
