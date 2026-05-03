# Python ADB CLI

이 디렉터리에는 Python ADB 컨트롤러의 실행 진입점만 둡니다. Android login helper는 `src/login/`에 있으며, Python CLI는 브로드캐스트 명령으로 helper를 호출합니다. 기존 분석 스크립트는 `old/scripts/` 아래에 참고용으로 보관합니다.

## Entry Points

`scripts/debug`는 저수준 ADB/OCR 확인용입니다. 기기 목록, 화면 캡처, OCR, crop, 단일 tap 같은 작은 실험에 사용합니다.

`scripts/profile`은 캘리브레이션과 profile 관리용입니다. 공유 profile은 `src/pension/profiles/<width>x<height>/` 디렉터리에 저장합니다.

`scripts/run`은 운영 시나리오용 진입점입니다. 좌표를 직접 받지 않고 profile key를 참조해 로그인, 잔고/주문 화면 진입, IRP/DC 계좌 선택, OCR 검증을 실행합니다.

`scripts/mtsa`는 기존 명령 호환용 wrapper입니다. 새 문서와 개발 작업은 `scripts/debug`, `scripts/profile`, `scripts/run`을 기준으로 합니다.

## 준비

```bash
make setup
make check-env
```

## 사용 예

```bash
./scripts/debug devices
./scripts/debug device-info
./scripts/debug capture screenshots/20260503-current.png
./scripts/debug ocr screenshots/20260503-current.png
./scripts/debug --adb /opt/homebrew/bin/adb --serial 824e1b4 login-status
./scripts/debug --adb /opt/homebrew/bin/adb --serial 824e1b4 login-command LOGIN

./scripts/profile init src/pension/profiles/1080x2340
./scripts/profile --profile src/pension/profiles/1080x2340 set-point order.account_password 900 548
./scripts/profile --profile src/pension/profiles/1080x2340 set-region order.holding_quantity 580 1010 430 90
./scripts/profile --profile src/pension/profiles/1080x2340 set-swipe menu.scroll_to_order 540 1830 540 930 450
./scripts/profile --profile src/pension/profiles/1080x2340 validate
./scripts/profile --profile src/pension/profiles/1080x2340 read-region order.holding_quantity --image screenshots/20260503-current.png
./scripts/profile --profile src/pension/profiles/1080x2340 check order --image screenshots/20260503-current.png
./scripts/profile --profile src/pension/profiles/1080x2340 detect-recovery --image screenshots/20260503-current.png

./scripts/run login
./scripts/run --profile src/pension/profiles/1080x2340 validate-scenario open-balance
./scripts/run --profile src/pension/profiles/1080x2340 validate-scenario open-order
./scripts/run --profile src/pension/profiles/1080x2340 open-order
./scripts/run --profile src/pension/profiles/1080x2340 select-account DC
./scripts/run verify-balance --text "IRP 1234 TIGER 미국S&P500 10주 평가금액 100000" --account-type IRP --account-hint 1234 --symbol-name "TIGER 미국S&P500" --min-quantity 10
./scripts/run verify-order --text "DC 9999 KODEX 미국나스닥100 매수 수량 3 금액 45000" --account-type DC --account-hint 9999 --symbol-name "KODEX 미국나스닥100" --side 매수 --quantity 3 --amount 45000
```
