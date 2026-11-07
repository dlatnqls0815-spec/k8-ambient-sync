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
