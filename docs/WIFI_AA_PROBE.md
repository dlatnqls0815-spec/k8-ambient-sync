# 시동 시 Wi-Fi 자동 켜기 — 차 방문 1회 확인 절차

목표: 시동마다 사용자가 Wi-Fi·무선 디버깅을 손으로 켜지 않게 한다. **무선 Android Auto(AA)를 쓰는 동안은 양보**한다.
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

## 구현은 근거가 잡힌 뒤

판별 근거가 확실하면 차량 중계(`CarRelay`)에 "부팅/시동 뒤 AA 미사용이면 Wi-Fi 켜기, AA 시작되면 끄기"를 넣는다.
무선 디버깅은 `persist.service.adb.tcp.enable`을 쓰지 않고 `service.adb.tcp.port`(부팅마다 임시)가 실제로 열리는지부터 정차 시험한다.
설치·Wi-Fi 켜기·adbd 재시작은 **별도 사용자 승인** 후에만 한다.
