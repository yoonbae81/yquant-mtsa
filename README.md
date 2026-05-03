# yquant-mtsa

한국투자증권 KIS Mobile Trading System(MTS) 자동화를 위한 하이브리드 컨트롤러입니다.

현재 프로젝트는 두 개의 서브시스템으로 구성합니다. 공동인증서 TransKey 로그인은 Android login helper가 담당하고, 그 외 계좌 비밀번호 입력, 잔고조회, 주문 화면 진입/검증/실행은 PC/Mac 쪽 Python ADB 컨트롤러가 담당합니다. ADB 컨트롤러는 기기별 캘리브레이션된 좌표/영역과 OCR/이미지 검증을 조합해 주문 자동화 흐름을 관리합니다.

## 구현 로드맵

이 프로젝트의 최종 목표는 한국투자 MTS의 퇴직연금 ETF/리츠 주문 화면을 대상으로, USB로 연결된 Android 기기를 PC/Mac에서 안정적으로 제어하는 자동화 컨트롤러를 제공하는 것입니다.

실제 주문 실행은 기본값이 아닙니다. 최종 주문 버튼은 `real-run` 모드에서만 허용하고, 그 전 단계에서 계좌/종목/방향/수량/금액 검증을 모두 통과해야 합니다.

1. 공통 실행 기반
   - [x] Python `.venv`, `requirements.txt`, `Makefile` 기반 개발환경 유지
   - [x] ADB 실행 레이어와 Tesseract OCR 실행 레이어 안정화
   - [x] `make setup`, `make check-env`, `make test`, `make compile`을 기본 검증 루틴으로 유지
   - [x] 첫 번째 기기 `1080x2340` profile 생성
   - [x] 계좌 비밀번호 팝업과 보안 숫자 키패드 영역 우선 캘리브레이션
   - [x] 보안 키패드가 랜덤 배열인지 반복 캡처로 판별
   - [x] 첫 번째 기기 `1080x2340` 기준 캘리브레이션된 좌표와 OCR 영역 관리

2. 로그인과 화면 진입 공통 흐름
   - [x] 공동인증서 로그인 자동화
   - [x] login helper readiness 확인
   - [x] 한국투자 MTS 실행과 로그인 상태 확인
   - [x] 공동인증서 로그인 명령 전송
   - [ ] 로그인 성공/실패/추가 인증 상태 분류
   - [x] 한글 정보명 기반 라우팅 framework 구현
   - [x] 화면 구조 `SITEMAP`과 상태 기반 `Navigator` 분리
   - [x] `IRP`/`DC` 계좌를 라우트 이름이 아닌 실행 파라미터로 분리
   - [x] 하단 메뉴 아이콘 tap point를 profile에 저장
   - [x] 메뉴 화면 상단 `연금` 탭 tap point를 profile에 저장
   - [x] `퇴직연금` 하위 링크 목록 read region을 profile에 저장
   - [x] 목표 화면 진입 실패 시 재시도/중단 판단 저장

3. 퇴직연금 ETF.리츠 잔고 화면
   - [x] profile의 하단 메뉴 아이콘과 `연금` 탭 tap point로 화면 진입
   - [x] `퇴직연금` 하위 `퇴직연금 ETF.리츠 잔고` 링크 tap point를 profile에 저장
   - [x] `퇴직연금 ETF.리츠 잔고` 화면 진입 anchor read region을 profile에 저장
   - [x] 잔고 화면의 tap point와 read region을 profile에 저장
   - [x] `실시간` 탭 tap point를 profile에 저장
   - [x] 평가손익 행 상세 펼침 tap point를 profile에 저장
   - [x] 계좌번호/계좌유형 read region을 profile에 저장
   - [ ] 종목명/종목코드 read region을 profile에 저장
   - [ ] 보유수량 read region을 profile에 저장
   - [x] 평가금액/손익/수익률 read region을 profile에 저장
   - [x] crop OCR 기반 anchor 검증 구현
   - [x] 숫자/금액/수량 파싱 구현
   - [x] 계좌, 종목, 보유수량, 평가금액 검증 구현
   - [x] before/after screenshot, crop, OCR JSON, decision JSON 저장
   - [x] 저장된 잔고 화면 산출물을 이용한 replay 테스트 구현

4. 퇴직연금 ETF.리츠 주문 화면
   - [x] profile의 하단 메뉴 아이콘과 `연금` 탭 tap point로 화면 진입
   - [x] `퇴직연금` 하위 `퇴직연금 ETF.리츠 주문` 링크 tap point를 profile에 저장
   - [x] `퇴직연금 ETF.리츠 주문` 화면 진입 anchor read region을 profile에 저장
   - [x] 주문 화면의 tap point와 read region을 profile에 저장
   - [x] IRP/DC 계좌 선택 버튼 tap point를 profile에 저장
   - [x] IRP/DC 계좌 선택 sheet의 read region을 profile에 저장
   - [x] IRP/DC 계좌 항목 tap point를 profile에 저장
   - [x] IRP/DC 계좌 선택과 변경 구현
   - [x] 계좌번호/계좌유형 read region을 profile에 저장
   - [x] 선택된 IRP/DC 계좌번호와 계좌유형 검증
   - [x] 퇴직연금 주문 화면에서 `비밀번호` 버튼 검증
   - [x] 계좌 비밀번호 팝업 감지 이벤트 핸들러 구현
   - [x] known state의 IRP/DC 계좌 기준으로 계좌 비밀번호 선택
   - [x] 계좌 비밀번호 입력 루틴 구현
   - [x] 계좌 비밀번호 팝업 열기
   - [x] 계좌유형 충돌/계좌 비밀번호 입력 상태 확인
   - [x] 저장 옵션과 입력 완료 상태 확인
   - [x] 실패/오류 횟수 감지
   - [x] 종목 검색 버튼 tap point를 profile에 저장
   - [x] 종목 검색/선택 및 종목명/종목코드 검증
   - [x] 매수/매도 탭 tap point를 profile에 저장
   - [x] 보유수량/주문가능수량/주문가능금액 read region을 profile에 저장
   - [x] 주문 수량 입력 영역 tap point를 profile에 저장
   - [ ] 수량 입력 및 반영 확인
   - [x] 주문 버튼 탭 전 최종 주문 정보 검증
   - [ ] 주문 버튼 탭 후 확인 팝업 감지
   - [ ] 주문 확인 팝업에서 계좌, 종목, 매수/매도, 수량, 금액 재검증
   - [x] `inspect`, `dry-run`, `confirm-run`, `real-run` 실행 모드 제공
   - [x] `dry-run`에서는 최종 주문 직전 중단
   - [x] `confirm-run`에서는 사용자 승인 후 진행 여부 결정
   - [x] `real-run`에서는 명시적 허용 조건을 만족할 때만 최종 주문 버튼 탭
   - [x] before/after screenshot, crop, OCR JSON, decision JSON 저장
   - [x] 저장된 주문 화면 산출물을 이용한 replay 테스트 구현

5. Recovery Handler 구현
   - [ ] 홍보 팝업, 공지, 이벤트, 세션 연장 안내 등 복구 가능한 오버레이 감지
   - [ ] 닫기/확인/오늘 하루 보지 않기 처리
   - [ ] 복구 후 원래 상태 재검증
   - [ ] 주문 정보 불일치나 알 수 없는 화면은 안전 중단

6. 운용 안정화
    - [ ] 장시간 반복 dry-run
    - [ ] 기기 재연결/해상도 변경/앱 업데이트 대응
    - [ ] 실패 유형별 fixture 축적
    - [ ] profile schema 버전 관리
    - [ ] 실주문 모드 제한 조건 강화

## 현재 방향

- Python ADB 컨트롤러가 전체 상태 머신, 캡처/OCR, profile 기반 좌표 조작, 실행 산출물, 주문 전후 검증을 담당합니다.
- Android login helper는 공동인증서 로그인과 캡처 불가 TransKey 보안키패드 입력만 담당합니다.
- 접근성 트리와 gesture는 조악하므로 주문 본문 자동화의 핵심 경로로 확장하지 않습니다.
- Android helper에 저장되는 공동인증서 비밀번호는 Android Keystore 기반 암호화 저장소를 사용합니다.
- 계좌 비밀번호와 주문 설정은 Python 컨트롤러의 `config.yaml`에서 관리합니다.
- tap point, read region, swipe, anchor 같은 화면별 좌표/영역 정보는 profile JSON에 저장합니다.
- 상위 실행 레이어는 화면별 profile key를 참조해 tap, swipe, OCR, 검증 명령만 내리고 좌표를 하드코딩하지 않습니다.
- 앱 화면/탭 구조는 `sitemap.py`의 `SITEMAP`에 두고, 현재 상태에서 목표 상태까지의 이동 계획은 `navigator.py`가 만듭니다.
- `routes.py`는 “예수금”, “매수”, “매도” 같은 정보명/작업명만 목표 상태로 해석하고, 실제 화면 이동 절차는 Navigator에 위임합니다.
- 실행 중 반복적인 OCR 탐색보다, 사전 캘리브레이션된 tap point/read region을 우선합니다.
- 정상 네비게이션 경로에서는 OCR로 중간 위치를 찾거나 매 단계 성공 여부를 판정하지 않습니다. route 실행기는 Navigator가 만든 profile key를 순서대로 실행하고, 부족한 profile key가 있으면 profile을 보강합니다.
- 화면 전환이 느린 profile key는 tap/swipe entry에 `post_delay_ms`를 기록해 안정화 시간을 profile에서 관리합니다.
- OCR은 최종적으로 읽어야 하는 데이터 추출, 입력값/주문 직전 정보 검증, 실패 후 진단에 사용합니다.
- 계좌 비밀번호 팝업처럼 특정 단계에서 발생할 수 있는 화면은 라우트의 명시적 event gate에서만 이벤트 핸들러로 처리합니다.
- 이벤트 핸들러는 실행 세션의 known state에서 현재 계좌(`IRP`/`DC`)를 읽고, `config.yaml`의 해당 계좌 비밀번호를 사용합니다.
- 계좌 비밀번호 자동저장이 완료된 계좌는 `account_password_saved_accounts`에 기록하고, 같은 계좌에서는 팝업 OCR 없이 event gate를 skip합니다.
- 이벤트 처리 결과와 산출물에는 계좌 유형, 감지/처리 여부, 실패 사유만 남기고 비밀번호 원문은 저장하지 않습니다.

## 정보 라우팅과 상태 관리 원칙

새 기능은 사용자가 최종적으로 원하는 정보 또는 작업을 기준으로 라우팅합니다. 화면 이름, 탭 이름, 정보 이름은 한국투자 MTS에 표시되는 한글 표현을 우선 사용합니다. 영어 식별자로 다시 번역해 외부 라우트 이름을 만들지 않습니다.

예:

```text
정보명: 예수금
계좌 파라미터: IRP 또는 DC
경로: 잔고 -> 계좌 선택 -> 실시간 탭 -> 평가손익 상세 펼침 -> 예수금 읽기

작업명: 매수
계좌 파라미터: IRP 또는 DC
경로: 메뉴 -> 연금 -> 주문 -> 계좌 선택 -> 매수 탭

작업명: 매도
계좌 파라미터: IRP 또는 DC
경로: 메뉴 -> 연금 -> 주문 -> 계좌 선택 -> 매도 탭
```

화면 하위 탭은 `SCREEN > TAB` 구조로 관리합니다.

```text
잔고
  - 실시간
  - 매매손익
  - 체결

주문
  - 매수
  - 매도
  - 정정/취소
  - 체결
  - 잔고
```

계좌는 라우트 이름에 포함하지 않고 실행 파라미터로 받습니다. `IRP 예수금`, `DC 예수금`처럼 계좌별 라우트를 두 벌 만들지 않고, `예수금` 라우트 하나가 `IRP` 또는 `DC` 계좌 파라미터를 받아 같은 경로에서 계좌 선택 단계만 바꿉니다. `매수`, `매도`도 동일하게 계좌별 라우트를 만들지 않고, 같은 주문 화면 경로에서 마지막 탭 선택만 달라집니다. 이 기준은 보유종목, 매매손익, 체결, 주문 검증 같은 이후 기능에도 동일하게 적용합니다.

화면 구조와 네비게이션은 세 계층으로 나눕니다.

- `profile`: 기기/해상도별 tap point, read region, swipe, anchor 저장소입니다. 좌표와 영역만 저장하고 실행 중 상태나 업무 의미는 저장하지 않습니다.
- `SITEMAP`: 한국투자 MTS의 퇴직연금 하위 화면, 탭, 펼침 요소, 화면 인식 anchor를 설명합니다. 예를 들어 `잔고 > 실시간`, `주문 > 매수` 같은 구조를 담습니다.
- `Navigator`: 현재 known state 또는 inspect state에서 목표 화면/계좌/탭/펼침 상태까지 필요한 profile key 목록을 계산합니다. 이미 만족된 단계는 skip하고, 메뉴 화면처럼 중간 위치에 있으면 더 짧은 경로를 사용합니다.

정보 라우팅 레이어는 다음 책임만 가집니다.

- 최종 정보명 또는 작업명으로 필요한 화면, 계좌, 탭, 펼침 상태, read region을 결정합니다.
- 현재 상태와 목표 상태를 Navigator에 넘겨 이동 계획을 받습니다.
- 비밀번호 입력처럼 조건부 처리가 필요한 단계는 일반 OCR 감시가 아니라 명시적 event gate로 계획에 포함합니다.
- 좌표를 직접 들고 있지 않고 profile key만 참조합니다.
- 실행 후 예상되는 상태를 다음 단계의 known state로 넘깁니다.

상태 관리는 `known state`와 `inspect state`를 구분합니다.

- `known state`: 컨트롤러가 방금 이동/탭/선택해서 신뢰할 수 있는 현재 상태입니다. 같은 실행 세션에서는 이 상태를 재사용하고 매번 screenshot/OCR을 하지 않습니다.
- `inspect state`: 앱이 수동 조작, 팝업, 세션 만료, 앱 재시작, 오류 화면 등으로 바뀌었을 가능성이 있을 때 screenshot/OCR/anchor 검증으로 다시 확인한 상태입니다.

기본 흐름은 다음과 같습니다.

```text
1. 실행 세션에 known state가 있으면 먼저 사용
2. 라우트가 목표 상태를 만들고 Navigator가 known state 기준으로 필요한 단계만 계획
3. 계획된 tap/swipe/read를 profile key로 실행
4. 성공한 단계가 바꾼 화면/계좌/탭/펼침 상태를 known state에 반영
5. 실패, 알 수 없는 화면, 복구 팝업, 외부 조작 가능성이 있으면 inspect state로 재동기화
```

profile은 기기/해상도별 좌표와 OCR 영역의 저장소입니다. profile에는 tap point, read region, swipe, anchor만 저장하고, “현재 IRP 계좌에 머문다” 같은 실행 중 상태는 저장하지 않습니다. 실행 중 상태는 run/session context로만 관리합니다. 앱 구조 자체도 profile에 넣지 않습니다. 앱 구조는 `SITEMAP`, 이동 판단은 `Navigator`, 좌표 해석은 profile이 각각 담당합니다.

라우트 실행 중 이벤트성 화면은 모든 tap 뒤에서 감시하지 않습니다. 예를 들어 `주문 -> IRP 계좌 선택 -> 매수 탭` 뒤에는 `order.account_password`를 탭하는 비밀번호 gate와 `계좌 비밀번호 입력` event hook이 명시적으로 들어갑니다. `IRP`가 이미 `account_password_saved_accounts`에 있으면 이 gate는 OCR 없이 skip합니다. 저장되지 않은 계좌라면 handler가 known state의 계좌를 사용해 비밀번호를 입력하고, 성공하면 해당 계좌를 `account_password_saved_accounts`에 추가합니다.

CLI 기준 예시는 다음과 같습니다.

```bash
./scripts/run --profile src/pension/profiles/1080x2340 list-info-routes
./scripts/run --profile src/pension/profiles/1080x2340 inspect-info-state --json runs/current-state.json
./scripts/run --profile src/pension/profiles/1080x2340 plan-info-route 예수금 --account IRP --state-json runs/current-state.json
./scripts/run --profile src/pension/profiles/1080x2340 validate-info-route 예수금 --account DC
./scripts/run --profile src/pension/profiles/1080x2340 plan-info-route 매수 --account IRP
./scripts/run --profile src/pension/profiles/1080x2340 plan-info-route 매도 --account DC
./scripts/run --profile src/pension/profiles/1080x2340 holdings --account IRP --max-pages 8
./scripts/run --profile src/pension/profiles/1080x2340 --config config.yaml order --account IRP --side buy --symbol-code 360750 --quantity 1 --mode dry-run
./scripts/run --profile src/pension/profiles/1080x2340 --config config.yaml handle-account-password-popup --state-json runs/current-state.json --json runs/account-password-event.json
```

보유종목 조회는 보이는 그리드를 좌우로 읽고, 필요하면 세로로 스크롤하며 `--max-pages` 한도까지 반복합니다. ticker 캐시는 기본적으로 `state/holding-tickers.tsv`에 저장합니다. 형식은 종목명과 ticker 두 칸만 사용합니다. 기본 실행에서는 cache miss 종목을 같은 페이지에서 탭해 상세 화면 ticker를 확보한 뒤 TSV에 저장하고, 끝까지 확보하지 못한 ticker가 있으면 성공 응답 대신 `ticker cache is incomplete` 오류로 중단합니다. `--no-resolve-missing-tickers`는 진단용으로만 사용합니다.

```tsv
TIGER 미국S&P500	360750
ACE 미국30년국채액티브(H)	0162Z0
```

## 구조

```text
yquant-mtsa/
├── src/
│   ├── pension/             # Python ADB 컨트롤러 서브시스템
│   │   ├── adb_device.py           # ADB 원시 제어 레이어
│   │   ├── login_bridge.py        # login helper 브로드캐스트 브리지
│   │   ├── device_profile.py       # 기기 profile, tap point/read region
│   │   ├── sitemap.py              # 퇴직연금 하위 화면/탭/anchor 구조
│   │   ├── navigator.py            # 현재 상태에서 목표 상태까지의 이동 계획
│   │   ├── routes.py               # 한글 정보명/작업명을 목표 상태로 변환
│   │   ├── events.py               # known state 기반 이벤트성 팝업 처리
│   │   ├── config.py               # config.yaml의 계좌별 private 설정 로드
│   │   ├── screen_capture.py       # screenshot/crop
│   │   ├── ocr.py                  # Tesseract OCR 래퍼
│   │   ├── keypad.py               # 랜덤 숫자 키패드 OCR 매핑
│   │   ├── screen_state.py         # anchor 기반 화면 검증
│   │   ├── recovery.py             # 복구 가능 팝업 감지
│   │   ├── run_artifacts.py        # 실행 산출물 저장
│   │   └── profiles/
│   │       └── 1080x2340/          # 공유 가능한 해상도별 profile
│   │           ├── manifest.json
│   │           ├── balance.json
│   │           ├── order.json
│   │           ├── order-search.json
│   │           ├── account-select-sheet.json
│   │           ├── account-password.json
│   │           ├── keypads.json
│   │           ├── menu.json
│   │           ├── home.json
│   │           └── recovery.json
│   └── login/       # Android login helper 서브시스템
│       ├── app/
│       ├── build.gradle.kts
│       ├── settings.gradle.kts
│       └── gradlew
├── scripts/
│   ├── profile                     # 캘리브레이션/profile 관리
│   ├── run                         # 운영 시나리오 실행
│   └── debug                       # 저수준 ADB/OCR 디버그
├── tests/                          # Python 단위 테스트
├── docs/
│   ├── adb-controller-design.md    # ADB 컨트롤러 설계서
│   ├── calibration-requirements.md # 캘리브레이션 요구사항
│   ├── implementation-roadmap.md   # 개발 순서와 안정화 기준
│   └── secure-keyboard-strategy.md # 보안키패드 입력 전략
├── old/                            # 기존 Android/접근성 구현과 참고 자료
├── requirements.txt
└── Makefile
```

## 준비

```bash
make setup
make check-env
```

`make setup`은 루트의 `.venv`를 만들고 `requirements.txt` 의존성을 설치합니다.
ADB 또는 Tesseract 실행 파일이 없으면 설치/경로 안내와 함께 실패합니다. 기기 연결 여부는 `make check-env`에서 확인합니다.

## 기본 명령

```bash
make test
make compile
make cli-help
make check-env
make login-build
make login-install ADB=/opt/homebrew/bin/adb
./scripts/debug devices
./scripts/debug device-info
```

## CLI 체계

`scripts/` 아래 실행 파일은 프로젝트 prefix 없이 역할 이름만 사용합니다.

```text
scripts/profile
```

캘리브레이션과 profile 관리 전용입니다. 실주문 버튼을 누르지 않는 도구로 유지합니다. 공유 profile은 `src/pension/profiles/<width>x<height>/` 아래의 화면별 JSON 묶음으로 저장합니다.

```text
scripts/run
```

운영 시나리오 실행 전용입니다. login helper 호출, 잔고/주문 화면 진입, IRP/DC 계좌 선택, dry-run, confirm-run, real-run 같은 흐름을 담당합니다.

```text
scripts/debug
```

저수준 ADB/OCR 확인용입니다. 기기 목록, 스크린샷, OCR, 단일 tap, 단일 region 읽기처럼 작은 실험에 사용합니다.

`scripts/mtsa`는 기존 명령 호환용 wrapper입니다. 새 문서와 자동화 작업은 `scripts/profile`, `scripts/run`, `scripts/debug`를 기준으로 작성합니다.

## CLI 예시

```bash
./scripts/profile init src/pension/profiles/1080x2340
./scripts/profile --profile src/pension/profiles/1080x2340 set-point order.account_password 900 548
./scripts/profile --profile src/pension/profiles/1080x2340 set-region order.account_row 40 490 1000 130
./scripts/profile --profile src/pension/profiles/1080x2340 validate
./scripts/profile --profile src/pension/profiles/1080x2340 read-region order.account_row

./scripts/run login
./scripts/run --profile src/pension/profiles/1080x2340 validate-scenario open-order
./scripts/run --profile src/pension/profiles/1080x2340 open-order
./scripts/run --profile src/pension/profiles/1080x2340 select-account IRP
./scripts/run --profile src/pension/profiles/1080x2340 holdings --account IRP
./scripts/run verify-order --text "IRP 1234 TIGER 미국S&P500 매수 수량 10 금액 100000" --account-type IRP --account-hint 1234 --symbol-name "TIGER 미국S&P500" --side 매수 --quantity 10 --amount 100000

./scripts/debug devices
./scripts/debug capture screenshots/20260503-current.png
./scripts/debug ocr screenshots/20260503-current.png
```

## 문서

- [ADB 컨트롤러 설계서](/Users/y/Github/yquant-mtsa/docs/adb-controller-design.md)
- [캘리브레이션 요구사항](/Users/y/Github/yquant-mtsa/docs/calibration-requirements.md)
- [구현 로드맵](/Users/y/Github/yquant-mtsa/docs/implementation-roadmap.md)
- [보안 키보드 입력 전략](/Users/y/Github/yquant-mtsa/docs/secure-keyboard-strategy.md)

## 제외된 기존 자료

현재 login helper는 `src/login/` 아래로 승격했습니다. 과거 분석 스크립트, 디컴파일/캡처 자료, 이전 문서는 `old/` 아래 참고 자료로 보관합니다.

```text
old/
├── docs/
├── scripts/
├── captures/
```
