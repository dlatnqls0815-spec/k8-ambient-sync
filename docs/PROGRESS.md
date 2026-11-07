# 진행 상황

기준일: 2026-11-07 (Asia/Seoul).

## 완료
- 로컬 폴더는 코드/커밋/remote 없는 Git 초기 상태였음을 확인.
- 기준 GitHub 저장소 접근, private=true, size=0, default_branch=main 확인.
- Git Credential Manager 인증은 작동하나 gh CLI는 미로그인. 인증정보 출력·복사 없음.
- 사용자가 **로컬 작업만 진행**을 선택. 원격 변경은 하지 않는다.
- 프로젝트 규칙과 필수 문서 초기화.

## 진행 중
- HMG-API 1.0.2, Light Vibes, Ambient Sound, HMG-Seogaemo, NEMESIS 실제 소스 분석.
- Java 7 호환 파서/동기화 코어/모의 컨트롤러와 진단 소스 구현.

## 미확인
- K8 펌웨어/Hook 호환성, 순정 방송의 실제 수신·값 의미.
- Gen5W BLE Central/GATT와 통화·미디어·Android Auto 동시 사용.
- 실제 Lotus 컨트롤러, 초기화/인증/명령/UUID/연결 유지 조건.

## GitHub
원격 Push/PR/merge 하지 않음. 브랜치·커밋은 세션 종료 전 갱신한다.

## 다음 작업
1. 오프라인 검사와 소스 근거를 완성한다.
2. 펌웨어·복구·백업 정보를 확보하고 정차 상태 읽기 진단을 별도 승인받는다.
3. Galaxy의 기존 공식 앱 통신을 수집해 대상 컨트롤러를 확정한다.
4. 근거가 확보된 경우에만 BLE transport와 자동실행을 통합한다.
