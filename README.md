# yquant-mtsa

Android NeoSmart 앱을 REST API로 제어하는 프로그램.

## 아키텍처

```
[REST Client] → [REST API Server] → [UI Controller] → [NeoSmart App]
```

## 기술 스택

- Python 3.14
- FastAPI (REST API)
- uiautomator2 (UI 조작)
- frida (개발 시 View ID 탐지)

## 환경 변수

```bash
ADB_PATH=/Users/y/Downloads/platform-tools/adb
FRIDA_SERVER_IP=192.168.x.x
```

## API 엔드포인트

- `GET /health` - Health check
- `GET /balance` - 계좌 잔고 조회
- `POST /order` - 매수/매도 주문
- `GET /status` - 주문 상태 조회