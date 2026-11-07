# 출처와 라이선스

이 프로젝트의 새 소스는 GNU AGPL version 3로 제공한다. 전문은 `LICENSE`에 있다.
HMG-API와 연결하는 진단 모드도 같은 라이선스를 사용한다.

상류 저장소는 조사 목적으로 `.research/`에 복제했으며 이 프로젝트에 포함하지 않는다.
상류 runtime/차량 바이너리/설치 코드/색상 팔레트/인증 자료를 배포하지 않는다.
진단 모드는 공개 API를 호출하는 독자 작성 코드다.

`WporterE1Candidate`는 공개 `wporter82/lotus-lamp-python`의 커밋
`c11390b614deed22880db6dc978cf2042f607631`에 기록된 패킷 사실을 바탕으로
독자 작성한 **모의 전용** 코덱이다. 원본 구현은 MIT이며 구현 소스를 복사하지 않았다.
원본 저작권/허가문: https://github.com/wporter82/lotus-lamp-python/blob/c11390b614deed22880db6dc978cf2042f607631/LICENSE
대상 컨트롤러와 같다는 뜻이 아니며 실제 송신 transport는 포함하지 않는다.

라이선스가 확인되지 않은 Gen5W Ambient Sound와 l0lsec/LotusLampX의 코드는 재사용하지 않았다.
전체 조사 출처와 조건은 `docs/research/`에 SHA 고정 링크로 남겼다.

공식 Temurin JDK와 Android API19 컴파일 자료는 로컬 `.tools/`에만 보관한다.
컴파일 중 사용한 HMG-API 클래스와 테스트 출력은 `build/`에만 남기며 배포하지 않는다.
