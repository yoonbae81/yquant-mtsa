# UI 분석 및 설정 생성 스크립트

이 디렉토리에는 MTS 앱의 UI 구조를 분석하고 설정 파일을 자동으로 생성하는 스크립트들이 있습니다.

## 파일들

### analyze_ui.py
MTS 앱의 UI 계층 구조를 분석하여 추출 규칙(JSON)을 자동으로 생성하는 Python 스크립트입니다.

**기능**:
- UI 계층 구조 덤프 (uiautomator)
- 라벨 검색 ("종목명", "매도가능", "매입단가")
- 근처 노드에서 값 추출
- 정규표현식 패턴 자동 생성
- JSON 설정 파일 생성

**사용법**:
```bash
# 기본 사용 (balance 화면 분석)
python3 scripts/analyze_ui.py

# 디바이스 지정
python3 scripts/analyze_ui.py --device 824e1b4

# 출력 파일 지정
python3 scripts/analyze_ui.py --output custom_rules.json

# 도움말
python3 scripts/analyze_ui.py --help
```

### analyze_and_build.sh
UI 분석부터 APK 빌드까지 전체 과정을 자동화하는 Shell 스크립트입니다.

**기능**:
- 디바이스 연결 확인
- MTS 앱 상태 확인
- UI 분석 스크립트 실행
- APK 빌드
- 설치 가이드 제공

**사용법**:
```bash
./scripts/analyze_and_build.sh
```

## 작업 흐름

1. **MTS 앱 준비**: MTS 앱을 7201 잔고 화면으로 이동
2. **스크립트 실행**: `analyze_ui.py` 또는 `analyze_and_build.sh` 실행
3. **설정 파일 확인**: 생성된 JSON 파일 검토
4. **필요 시 수정**: 규칙을 수동으로 조정
5. **APK 빌드**: 최초 1회만 빌드
6. **테스트**: [잔고] 버튼으로 데이터 추출 확인

## 출력 파일

`analyze_ui.py`는 다음 위치에 JSON 파일을 생성합니다:
- 기본: `app/src/main/assets/balance_extraction_rules.json`
- 지정: `--output` 옵션으로 지정한 경로

## 요구사항

- Python 3.6+
- Android SDK (adb)
- 연결된 Android 기기
- MTS 앱 설치 및 로그인

## 문제 해결

### "No module named 'xml'"
```bash
pip install defusedxml
```

### "adb: command not found"
```bash
export PATH=$PATH:/Users/y/Downloads/platform-tools
```

### "device not found"
```bash
adb devices
# USB 디버깅 활성화 확인
```

## 추가 정보

자세한 사용법은 [HYBRID_STRATEGY.md](../HYBRID_STRATEGY.md)를 참조하세요.
