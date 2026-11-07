# K8 기존 자료 수집 USB

차량은 GL3HEV.KOR, 펌웨어260902, Android4.4.2/API19를 대상으로 한다.
USB 루트에 이 디렉터리의 `NEMESIS.data`와 `CODEX-collect-k8-ambient.sh`를 함께 둔다.
원래 USB descriptor는 PC에서 먼저 백업한다.

1. 정차·P 상태에서 내비게이션의 정상 화면이 나온 다음 USB를 꽂는다.
2. NEMESIS의 USB 실행 확인에 **Yes/예**를 누른다.
3. 실행 완료 안내가 나온 뒤 USB를 노트북으로 가져온다.

USB에 `K8-AMBIENT-EVIDENCE-날짜-시각-번호` 폴더가 생긴다.
`collection.txt`에서 복사한 파일과 접근 불가 항목, OEM 로그 읽기 결과를 확인한다.
완료 안내는 수집 작업 종료를 뜻하며 개별 파일의 확보 여부는 목록에 기록된다.

기존 Car Report JSON, K8의 car/framework 맵, 과거 map-check, HMG 설치 기록과 기존 HMG 로그를
복사한다. GL3HEV/kor/260902 맵은 제조사 폴더 아래에서 찾아 원래 하위 경로를 보존한다.
USB에만 결과를 쓴다.
원본은 private 자료로 취급해 로컬에서 확인한다.

이번 버전은 system 로그 버퍼의 SystemService 태그를 읽어 SML 응답·RGB 설정·configuration 설정
문구만 `logs/oem-sml.log`에 보존한다. 기존 캐시를 한 번 읽고 종료한다.
`oem_logcat_exit=0`과 `oem_sml_log_lines`로 읽기 성공과 관련 로그 유무를 각각 확인한다.
사용자가 이전에 했던 색상·밝기 조작은 완료로 기록돼 있다. 이번에는 기존 캐시 확보를 먼저 진행한다.
캐시가 순환해 지워졌거나 런타임 출력 조건이 맞지 않으면 해당 파일이 비어 있을 수 있다.

Car Report JSON이 기존 경로에 없으면 수집 목록을 보고 다음 자료 경로를 판단한다.
첫 차량 실행에서는 파일 크기 확인 단계의 SIZE_UNAVAILABLE로0개가 복사됐다.
수정본은 wc 의존성을 제거했고,0개 복사 시 실패 상태를 기록한다.
USB 실행 경로는 공식 [NEMESIS 스크립트 문서](https://github.com/xwtk/hmg_nemesis/blob/345f171850c35742f84f0fbe511d0b7ab50364fc/05-Run-Scripts.md)에 따른다.
