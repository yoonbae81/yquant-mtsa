# 구현 로드맵

이 문서는 본격 시나리오 구현 전에 개발기간을 줄이고 운영 안정성을 높이기 위한 순서를 정의한다. 기준은 Python ADB 컨트롤러와 Android login helper의 하이브리드 구조이며, `old/` 자료는 참고용으로만 사용한다.

## 1. 먼저 고정할 계약

1. CLI 표면
   - `scripts/debug`: 저수준 ADB/OCR 실험
   - `scripts/profile`: 캘리브레이션과 profile 관리
   - `scripts/run`: 운영 시나리오
   - `scripts/mtsa`: 기존 명령 호환용 wrapper

2. 서브시스템 경계
   - `src/pension`: ADB 제어, 캡처/OCR, 계좌 비밀번호, 잔고조회, 주문 실행/검증
   - `src/login`: 공동인증서 로그인과 캡처 불가 TransKey 입력
   - Python은 helper에 비밀번호를 전달하지 않고 `LOGIN` 같은 명령만 보냄
   - helper는 공동인증서 비밀번호를 Android Keystore 기반 암호화 저장소에서 읽음

3. Profile schema
   - 표준 위치는 `src/pension/profiles/<width>x<height>/`
   - `manifest.json`은 schema version, width, height, density만 보관
   - 화면별 JSON은 `screen`, `anchors`, `tap_points`, `regions`를 보관
   - `recovery.json`은 복구 가능한 overlay allowlist만 보관
   - serial, 비밀번호, 전체 계좌번호는 저장하지 않음

4. 실행 산출물
   - `runs/<run-id>/run.json`
   - step별 before/after screenshot
   - crop 이미지
   - OCR JSON
   - decision JSON
   - redacted ADB command log
   - recovery 시도와 실패 사유

5. Replay fixture
   - 기기 없이 화면 인식, OCR 파싱, recovery 판단을 반복 검증
   - 실패 run에서 선별한 screenshot/crop/OCR JSON만 fixture로 승격

## 2. 권장 구현 순서

1. `profile validate` 추가
2. `screen_state`를 required/optional/forbidden/min_score 기반으로 확장
3. `runs/` 산출물 manifest 계약 고정
4. replay 테스트 추가
5. `debug capture/read-region/check/detect-recovery` 루프 안정화
6. `profile set-point/set-region`으로 1차 profile 완성
7. `run inspect` 구현
8. `run open-order` 구현
9. `run login` 구현
   - login helper readiness 확인
   - `login-command LOGIN` 호출
   - 로그인 후 MTS shell 또는 목표 화면 검증
   - helper 실패 사유를 redacted run artifact로 기록
10. `run order --dry-run` 구현
11. 장시간 dry-run으로 recovery fixture 축적
12. `confirm-run`, `real-run` 순서로 제한적으로 개방

## 3. 안전 기준

`real-run`은 한 가지 옵션만으로 열리지 않는다.

- config에서 실주문 허용
- CLI에서 명시적 `real-run` 선택
- 계좌/종목/방향/수량/금액 검증 통과
- 주문 확인 팝업의 요약 재검증 통과
- 최종 주문 직전 산출물 기록 완료

Recovery Handler는 allowlist 기반 액션만 수행한다. 최종 주문 버튼, 주문 확인 버튼, 주문 수량 변경, 계좌 변경은 recovery 액션으로 등록하지 않는다.
