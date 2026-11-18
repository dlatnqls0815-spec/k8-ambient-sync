# 시동 시 Wi-Fi 자동 켜기 — 차 방문 1회 확인 절차

목표(2026-11-18 사용자 확정): 시동하면 헤드유닛이 **휴대폰 핫스팟에 자동 접속**하고 **무선 ADB가 켜져 있게** 한다.
**무선 Android Auto(AA)를 쓰는 동안은 양보**한다. 엠비언트 연동은 지금처럼 휴대폰 중계(B안)로 두고 Wi-Fi 경로는 만들지 않는다.
근거: 헤드유닛 Wi-Fi 칩이 하나라 AP(무선 AA)와 일반 Wi-Fi 클라이언트가 동시에 안정적이지 않다
(StopDragon/korea-projection 기록, Gemini 조사 2026-11-18). 아래는 구현 전 **읽기 전용** 확인이다.

도구: `tools/probe_wifi_aa.py` — 허용 목록 명령만 읽는다(setprop·svc·settings put·파일 쓰기 없음, Wi-Fi 비밀번호 파일은 이름만).
결과는 `private/vehicle-adb/WIFI-AA-<시각>-<라벨>/`(커밋 금지).

```powershell
python tools/probe_wifi_aa.py --label aa_off
python tools/probe_wifi_aa.py --diff private/vehicle-adb/WIFI-AA-...-aa_off private/vehicle-adb/WIFI-AA-...-aa_on
```

## 한 번 갈 때 찍을 스냅샷 (정차, 차량 ADB 연결 상태)

| 라벨 | 차 상태 |
|---|---|
| `aa_off` | 휴대폰 무선 AA 연결 안 됨(블루투스만 연결). 평소 시동 직후 |
| `aa_on` | 휴대폰 무선 AA가 화면에 떠서 실제로 쓰는 중 |
| `after_aa` | AA를 끊은 직후 |
| `wifi_client` | (별도 승인 후) 사용자가 화면에서 일반 Wi-Fi를 켜 핫스팟에 붙은 상태 |

`aa_off`와 `aa_on`의 차이에서 "무선 AA 사용 중" 판별 근거(프로세스, 연결된 단말 IP, 포트, 로그)를 찾는다.
AP(wlan0)는 AA를 안 써도 떠 있으므로 AP 유무는 근거가 못 된다.

## 5555 포트 영구 열기 시험 (별도 승인 필요, 정차)

참고: Open-HMG/hmgapi-adb(AGPL-3.0)는 props만 바꾼다 — `persist.service.adb.tcp.enable=0`(무선 AA와 충돌한 스위치 끄기),
`persist.adb.tcp.port=5555` + adbd 재시작. 우리는 그 모듈을 설치하지 않고(HMG-API 혼합 설치 금지) 같은 props 효과만 시험한다.
K8 GL3 HEV는 HMG-API 지원표에 직접 없고, 같은 한국 소프트웨어 260902의 다른 차종만 있다. 호환은 미확인이다.

시험 전에 현재 값을 먼저 기록한다(스냅샷 `aa_off`의 `getprop_wifi.txt`의 persist.service.adb.tcp.enable·persist.adb.tcp.port·service.adb.tcp.port).
사용자 승인 뒤에만, 정차 상태에서 아래 순서로 한다. 각 단계마다 결과를 보고 중단할 수 있다.

1. `setprop persist.adb.tcp.port 5555`, adbd 재시작 → 휴대폰 핫스팟 접속 상태에서 PC가 `adb connect <차량 IP>:5555`로 붙는지.
2. 무선 AA 연결을 시도해 AA가 정상인지(충돌 여부). 이상하면 즉시 `setprop persist.adb.tcp.port ""`로 원복.
3. 시동을 껐다 켜서 설정이 유지되는지. `persist.service.adb.tcp.enable`은 건드리지 않는다(현재 값만 기록, 바꾸려면 별도 승인).

되돌리기: 시험 전 값으로 setprop 후 adbd 재시작. 값이 비어 있었으면 `""`로 되돌린다.

## 구현은 근거가 잡힌 뒤

판별 근거가 확실하면 차량 중계(`CarRelay`)에 "부팅/시동 뒤 AA 미사용이면 Wi-Fi 켜기, AA 시작되면 끄기"를 넣는다.
무선 디버깅은 `persist.service.adb.tcp.enable`을 쓰지 않고 `service.adb.tcp.port`(부팅마다 임시)가 실제로 열리는지부터 정차 시험한다.
설치·Wi-Fi 켜기·adbd 재시작은 **별도 사용자 승인** 후에만 한다.
