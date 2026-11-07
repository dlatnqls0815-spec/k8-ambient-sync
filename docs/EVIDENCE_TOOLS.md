# 실제 기기 자료를 확인하는 도구

이 단계는 앱 설치나 차량 제어 없이 **읽기 전용 메타데이터와 로컬 캡처 분석**만 수행한다.
raw→K8 상태 매핑이나 실제 BLE 송신을 활성화하지 않는다.

실차에서 필요한 최소 조작 순서는 [CAPTURE_SESSION](CAPTURE_SESSION.md)에 있다.
빨강/초록/파랑 후보는 현재 도구로 비교 가능하지만, Lotus 설치본의 다른 밝기 형식·E2·암호화는
의미를 추정하지 않고 미확인으로 남긴다.

## 1. Galaxy 연결과 앱 메타데이터

USB 디버깅을 켠 본인 Galaxy를 데이터 전송 가능한 케이블로 연결하고 잠금을 해제한다.
휴대폰에 PC 허용 창이 나오면 본인이 확인한다. ADB 연결 상태가 `device`여야 읽을 수 있다.
단순히 개발자 옵션을 켰다는 사실은 PC 연결 성공을 뜻하지 않는다.
[Android 공식 ADB 문서](https://developer.android.com/tools/adb#Enabling).

무선 디버깅도 사용할 수 있다. 휴대폰과 PC를 같은 Wi-Fi에 연결하고 휴대폰의 무선 디버깅을 켠다.
기존에 페어링된 PC라면 표준 ADB mDNS 광고로 재연결할 수 있다. 새 페어링이 필요한 경우
단기 페어링 코드는 이 PC의 로컬 터미널에서 입력하며 채팅·문서·로그에 남기지 않는다.
무선 IP/port·mDNS 광고명·연결 serial도 공개하지 않는다. LAN 전체 탐색이나 새 서비스 설치는 하지 않는다.
[공식 무선 디버깅 절차](https://developer.android.com/tools/adb#wireless-android11-command-line).

```text
python tools/phone_probe.py --adb <ADB 실행 파일 경로>
```

여러 기기 또는 미승인 기기가 있어 대상을 확정할 수 없으면 중단한다.
`--serial`은 로컬에서만 사용할 수 있으며 채팅·GitHub에 일련번호를 적지 않는다.
연결된 대상이 Samsung의 지원 범위 OS인지 확인한 뒤 다음 항목만 남긴다.

- Android 버전/API와 고정 앱 패키지 `com.szelk.ledlamppro`의 versionName/versionCode.
- OS가 BLE feature를 선언했는지 여부. 실제 컨트롤러 연결 성공의 증거는 아니다.

결과 기본 경로는 `private/phone-probe.json`이다. 전체 package dump·기기 식별자·ADB 오류 원문은
저장하거나 출력하지 않는다. APK 추출·설치, 시스템 설정 변경, Bluetooth scan/connect/write,
logcat·전체 버그 보고서 수집은 이 도구의 기능에 없다.

## 2. 기존 앱의 HCI 캡처

실제 패킷이 필요할 때 [DEVICE_DIAGNOSTICS](DEVICE_DIAGNOSTICS.md)의 한 동작씩 기록하는 절차를 따른다.
HCI snoop 활성화와 Bluetooth 재시작은 사용자가 휴대폰에서 직접 수행한다.
이번 도구가 대신 설정을 바꾸거나 조명을 조작하지 않는다.
[AOSP HCI 기록 문서](https://source.android.com/docs/core/connect/bluetooth/verifying_debugging#debugging-with-logs).

원본 bugreport/BTSnoop와 단일 동작의 시각표는 `captures/` 또는 `private/`에만 보관한다.
원본 bugreport에는 다른 앱·네트워크·계정 로그가 들어갈 수 있다.
전체 보고서를 외부 AI/저장소에 보내지 않는다.
[Android bugreport 문서](https://developer.android.com/studio/debug/bug-report).

## 3. TShark 선별 필드 → 후보 분석

원본 HCI/L2CAP 재조립은 Wireshark의 TShark에 맡긴다. 도구를 자동 설치하지 않는다.
TShark가 로컬에 있을 때 원본을 직접 읽고, 이름 해석 없이 다음 필드만 TSV로 내보낸다.
먼저 로컬 `private/` 폴더를 만든다. 다음은 PowerShell 명령이며 입력 경로는 실제 로컬 파일로 바꾼다.

```text
tshark -2 -n -r captures/input.btsnoop -Y "btatt.opcode == 0x12 || btatt.opcode == 0x52" -T fields -E header=y -E separator=/t -E quote=d -E occurrence=a -e frame.number -e frame.time_relative -e bthci_acl.chandle -e bthci_acl.connect_in -e hci_h4.direction -e btl2cap.cid -e btatt.opcode -e btatt.handle -e btatt.value | Set-Content -Encoding utf8 private/att-writes.tsv
python tools/att_evidence.py private/att-writes.tsv
```

UTF-8 BOM이 있는 파일도 읽는다. 입력의 헤더·단일 필드·숫자 범위·크기와
지원되는 방향/CID/opcode를 확인한다. 여러 occurrence가 합쳐진 행을 첫 값만 골라 해석하지 않는다.
지원 범위는 송신 방향의 고정 ATT CID 4, Write Request `0x12`, Write Command `0x52`다.
Signed/Prepare/Execute write, 동적 CID/EATT, 다른 프레이밍은 지원하지 않는다.

연결 handle과 연결 시작 frame이 같은 패킷만 같은 연결 별칭으로 묶는다.
handle이 재사용돼도 연결 시작 frame이 다르면 별도 세션이다.
시작 frame이 없으면 연결 세션 미확인으로 남긴다. UUID는 자동으로 추측하거나 후보값을 채우지 않는다.

기본 결과는 `private/att-evidence.json`이다. 공개 후보와 정확히 일치한 형식만 RGB/밝기/전원으로
정규화하고 알 수 없는 payload는 개수로만 남긴다. raw payload·MAC·절대 시각·기기 이름을 출력하지 않는다.
출력은 로컬 검토용이며 자동 익명화 완료본이나 기기 호환성 인증서가 아니다.

**후보 일치 ≠ 대상 프로토콜 확정.** 앱 조작 시각표·실제 조명 반응·서비스/characteristic discovery·
연결 초기화와 재연결 증거를 대조해야 한다. 이 TSV만으로 LE/Classic, 인증 유무, 상태 ACK,
실제 반영 지연을 확정하지 않는다. `0x52`에 응답이 없다는 사실도 실제 발광 성공의 증거가 아니다.

필드와 export 옵션의 근거:
[TShark](https://www.wireshark.org/docs/man-pages/tshark.html),
[HCI ACL](https://www.wireshark.org/docs/dfref/b/bthci_acl.html),
[H4](https://www.wireshark.org/docs/dfref/h/hci_h4.html),
[L2CAP](https://www.wireshark.org/docs/dfref/b/btl2cap.html),
[ATT](https://www.wireshark.org/docs/dfref/b/btatt.html).

## 4. 검사

```text
python -m unittest discover -s tests/python -p "test_*.py"
python tools/check.py
```

Python 도구 검사는 합성 입력과 모의 ADB만 사용한다. 기본 프로젝트 검사에도 포함한다.
입력 오류, 연결 대상 혼동, raw 정보 유출, 재연결 handle 재사용과 미확인 패킷을 검사한다.
실제 기기 접속·캡처 성공과는 별개의 검사다.

## 5. K8의 기존 상태 읽기

```text
python tools/k8_probe.py --adb <기존 ADB 실행 파일 경로>
```

한국 K8의 Android 4.4.2/API19를 먼저 확인한다. 기본 결과는 `private/k8-probe.json`이다.
고정한 펌웨어 속성·BLE feature 선언·HMG 앱 버전·Hook enable 마커의 읽기 가능 여부와
이미 존재하는 map-check 요약만 읽는다. 여러 기기가 있으면 `--serial`로 로컬에서 선택한다.
출력 경계는 기존 ATT 도구와 같이 private에 제한하며 원본 오류·JSON·dump는 저장하지 않는다.

기존 ADB 연결이 없으면 상태 화면을 수기로 기록한다. TCP ADB 활성화·새 진단 서비스·권한 상승·
설치/검증 명령은 실행하지 않는다. 접근 실패를 설치 안 됨/Hook OFF로 단정하지 않는다.
기존 map-check 성공과 enable 마커만으로 현재 Hook 또는 SML 이벤트 수신을 증명할 수 없다.
