# Lotus Lamp X 프로토콜 조사

조사일: 2026-11-07. **공개 구현의 동작을 확인했으며, 사용자의 컨트롤러와 같은 장치인지는 확인하지 않았다. 실제 BLE 연결·송신·APK 추출은 수행하지 않았다.**

## 앱 식별과 적용 범위

[공식 Google Play 항목](https://play.google.com/store/apps/details?id=com.szelk.ledlamppro)은 LotusLamp X의 패키지를 `com.szelk.ledlamppro`로 식별한다. 개발자 설명은 RGB, RGBIC, 전구, 차량용 조명을 함께 지원한다고 밝힌다. 따라서 앱 이름만으로 BLE/Classic 여부, 장치 종류, 패킷 형식을 결정할 수 없다. Lotus Lantern(`wl.smartled`), LotusLanternX, HappyLighting의 자료도 이름이 비슷하다는 이유로 혼용하지 않는다. 설치된 갤럭시 앱의 패키지·버전 확인이 먼저다.

## 수집한 소스와 최근 변경

각 저장소는 `.research/`에 분석용으로 복제했으며 프로젝트 소스에 포함하지 않는다. 다운로드한 코드는 실행하지 않았다.

| 저장소 | 조사한 HEAD / 커밋 날짜 | 최근 변경 / 라이선스 |
|---|---|---|
| [wporter82/lotus-lamp-python](https://github.com/wporter82/lotus-lamp-python) | `c11390b614deed22880db6dc978cf2042f607631` / 2026-02-08 | HEAD 버전 1.1.0, 이전 `6a1f1bbbf7a7cec3d722fb1e97a0368c02728ed7` 타이머 지원. [MIT](https://github.com/wporter82/lotus-lamp-python/blob/c11390b614deed22880db6dc978cf2042f607631/LICENSE#L1-L21). |
| [l0lsec/LotusLampX](https://github.com/l0lsec/LotusLampX) | `ba7294d9d71555658cf9eb921ce810cb7b94b1a0` / 2026-03-18 | 초기 구현·문서 정리 3개 커밋. 조사한 Git 트리에 LICENSE 파일이 없다. 공개 열람을 코드 재사용 허가로 취급하지 않는다. |
| [dave-code-ruiz/elkbledom](https://github.com/dave-code-ruiz/elkbledom) | `f84eb4a17abf4a8da8c7424e436f6cdc51937937` / 2026-10-29 | 1.6.6. 직전 병합은 MELK 로그인 후 꺼짐 및 OFF 후 색상 복원 수정. [MIT](https://github.com/dave-code-ruiz/elkbledom/blob/f84eb4a17abf4a8da8c7424e436f6cdc51937937/LICENSE). |

이 문서는 소스 분석 결과이며 위 라이브러리를 종속성으로 설치하거나 통째로 복사하지 않는다. MIT 코드를 재사용하면 저작권·허가문을 동봉한다.

## E1 후보: 확인된 코드와 충돌

`lotus-lamp-python`의 `LotusLamp`는 MELK-OA10 5F를 대상으로 한다. 기본 서비스 `0000fff0-0000-1000-8000-00805f9b34fb`, 쓰기 characteristic `0000fff3-0000-1000-8000-00805f9b34fb`, notify 후보 `0000fff4-0000-1000-8000-00805f9b34fb`를 정의한다([controller.py:47–49](https://github.com/wporter82/lotus-lamp-python/blob/c11390b614deed22880db6dc978cf2042f607631/lotus_lamp/controller.py#L47-L49)). `_send_command`는 `response=False`로 쓴다([219–230](https://github.com/wporter82/lotus-lamp-python/blob/c11390b614deed22880db6dc978cf2042f607631/lotus_lamp/controller.py#L219-L230)). 이것은 **후보 장치의 공개 구현**에 대한 증거다.

아래 바이트는 분석·모의 테스트용 비교이며 실제 장치용 명령표가 아니다. 인덱스는 0부터 센다.

| 항목 | wporter82 실제 코드 | l0lsec 실제 코드 | 판정 |
|---|---|---|---|
| RGB | `7E 07 05 03 R G B 10 EF` | 동일 | 두 구현 일치. 사용자 장치 적용 미확인. |
| 밝기 | `7E 07 01 P FF FF FF 00 EF`, P=0..100 | `7E 04 01 P FF FF FF 00 EF`, 설명 범위 0..255 | 길이 필드와 단위가 충돌. 자동 선택·자동 환산 금지. |
| ON | `7E 07 04 01 00 FF FF 00 EF` | 기본 RGB형은 `7E 07 04 FF 01 01 02 01 EF` | 매개변수 충돌. |
| OFF | `7E 07 04 00 00 FF FF 00 EF` | 기본 RGB형은 `7E 07 04 00 01 00 02 01 EF` | 매개변수 충돌. |

근거: wporter82 `LotusLamp.set_rgb`, `set_brightness`, `power_on`, `power_off`([controller.py:234–355](https://github.com/wporter82/lotus-lamp-python/blob/c11390b614deed22880db6dc978cf2042f607631/lotus_lamp/controller.py#L234-L355)); l0lsec `_protocol_len`, `build_e1_frame`, `cmd_on_off`, `cmd_brightness`, `cmd_color`([protocol.py:57–135](https://github.com/l0lsec/LotusLampX/blob/ba7294d9d71555658cf9eb921ce810cb7b94b1a0/cli/protocol.py#L57-L135)).

wporter82의 문서는 byte 7이 항상 `00`라고 쓰지만 RGB 함수는 `10`을 쓴다. 문서보다 실제 함수를 우선해 기록했으며 이 불일치도 실측 필요 이유다([PROTOCOL.md:51–64](https://github.com/wporter82/lotus-lamp-python/blob/c11390b614deed22880db6dc978cf2042f607631/docs/PROTOCOL.md#L51-L64)). 이 두 후보의 RGB/밝기/전원 함수에는 체크섬 계산이 없다. 모든 Lotus Lamp X 장치가 체크섬이나 인증을 쓰지 않는다는 뜻은 아니다.

## 다른 장치군과 초기화의 위험

l0lsec은 코드 주석에서 MELK-OA10의 E1과 `COLORFUL_CAR`의 E2 경로를 구별한다([protocol.py:1–11](https://github.com/l0lsec/LotusLampX/blob/ba7294d9d71555658cf9eb921ce810cb7b94b1a0/cli/protocol.py#L1-L11)). 해당 APK 버전·원본 클래스는 이 조사에서 확보하지 않았으므로 앱 전체의 구현으로 독립 검증한 사실은 아니다.

elkbledom은 같은 FFF3/FFF4를 쓰는 MELK-OG10과 MELK-OF10에도 서로 다른 전원·밝기 형식을 정의한다([models.json:269–305](https://github.com/dave-code-ruiz/elkbledom/blob/f84eb4a17abf4a8da8c7424e436f6cdc51937937/custom_components/elkbledom/models.json#L269-L305)). UUID만 일치해도 호환 판정을 내릴 수 없다.

특히 이 구현은 MELK/MODELX 연결 시 초기화 write를 자동 송신하고([elkbledom.py:688–718](https://github.com/dave-code-ruiz/elkbledom/blob/f84eb4a17abf4a8da8c7424e436f6cdc51937937/custom_components/elkbledom/elkbledom.py#L688-L718)), 최근 수정은 그 초기화가 조명을 끌 수 있다고 설명한다([772–781](https://github.com/dave-code-ruiz/elkbledom/blob/f84eb4a17abf4a8da8c7424e436f6cdc51937937/custom_components/elkbledom/elkbledom.py#L772-L781)). **타사 라이브러리의 connect/debug 호출도 읽기 전용이라고 가정하지 않는다.**

## 아직 필요한 증거

| 확인 항목 | 현재 상태 / 다음 증거 |
|---|---|
| 실제 장치 식별, BLE 또는 Classic | 미확인. 기존 앱의 정상 연결 HCI 기록에서 구별한다. 광고명은 공개 문서에 그대로 올리지 않는다. |
| 서비스·characteristic·write 속성 | 위 UUID는 후보만 존재. 기존 앱의 GATT discovery와 write handle 대응표가 필요하다. |
| RGB·밝기·ON/OFF | 한 동작씩 실행한 앱 원본 write와 화면 관찰을 대조한다. |
| 초기화·인증·암호화·체크섬 | 미확인. 연결 시작 전부터 기록하고 보안 절차 여부를 확인한다. |
| 단일 연결·keepalive·재연결 | 다른 장치의 보고를 일반화하지 않는다. 기존 앱의 정상 연결/종료/재연결을 기록한다. |
| notify 상태 조회 | characteristic 존재와 실제 알림·의미는 별개다. 실측 전 상태 읽기 구현으로 취급하지 않는다. |
| 안전한 전송 간격·200ms 성능 | 미측정. 모의 시간 결과는 실차 지연 증거가 아니다. |

초기 공개 자료 조사에서는 실제 설치본을 확보하지 않았다. 이후 사용자 요청으로 본인 Galaxy에
무선 ADB 연결해 `5.21.03`/`425`와 base APK SHA-256을 확보하고 관련 DEX를 제한적으로 분석했다.
실제 E1/E2 builder·조건부 암호화·GATT 호출의 위치는
[설치본 정적 분석](LOTUS_APK_5_21_03.md)에 기록했다. 현재 설치본과 공개 구현의 패키지명·버전·
밝기/전원 형식을 혼동하지 않는다. 앱에 경로가 존재한다는 사실은 사용자 컨트롤러가 그 경로를
쓴다는 증거가 아니다. 서명 검증·실제 HCI·대상 분기는 미확인이다.
APK·분할 APK·원본 decompile 출력은 Git에 넣지 않는다.

다음 단계는 [비파괴 진단 절차](../DEVICE_DIAGNOSTICS.md)에 따른 최소 캡처다. 프로필은 실측된 패킷·단위·초기화까지 일치한 뒤 명시적으로 확정한다.
