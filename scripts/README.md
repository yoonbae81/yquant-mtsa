# UI 분석 및 설정 생성 스크립트

이 디렉토리에는 MTS 앱의 UI 구조를 분석하고 설정 파일을 자동으로 생성하는 스크립트들이 있습니다.

## 파일들

### connect_adb_windows.bat
Windows에서 ADB 서버를 시작하고 WSL에서 접속할 수 있게 해주는 배치 스크립트입니다.

**기능**:
- Windows에서 ADB 서버 시작 (포트 5037)
- Windows IP 주소 자동 표시
- WSL에서 접속하기 위한 안내 제공
- 연결된 안드로이드 기기 확인

**사용법**:
1. Windows에서 스크립트 실행 (안드로이드 폰이 USB로 연결된 상태)
2. 표시된 Windows IP 주소 확인
3. WSL에서 `./scripts/connect_adb_wsl.sh WINDOWS_IP` 실행 시 해당 IP 사용

```cmd
scripts\connect_adb_windows.bat
```

**요구사항**:
- Windows OS
- Android SDK 설치 (또는 ADB가 PATH에 등록됨)
- USB 디버깅이 활성화된 안드로이드 기기

---

### connect_adb_wsl.sh
WSL에서 Windows ADB 서버에 접속하는 스크립트입니다.

**기능**:
- Windows IP 자동 감지 (vEthernet (WSL) / Windows 172.* 우선, 이후 /etc/resolv.conf 및 ip route 폴백 사용)
- Windows ADB 서버 (포트 5037)에 연결
- ADB_SERVER_SOCKET 환경변수 설정
- 연결된 기기 목록 표시

**사용법**:
```bash
# 자동 감지 (권장)
./scripts/connect_adb_wsl.sh

# Windows IP 지정
./scripts/connect_adb_wsl.sh 192.168.1.100
```

**작동 방식**:
1. Windows에서 `connect_adb_windows.bat` 실행
2. WSL에서 이 스크립트를 실행하여 `ADB_SERVER_SOCKET=tcp:WINDOWS_IP:5037` 설정
3. WSL의 `adb` 클라이언트가 Windows ADB 서버(포트 5037)를 사용
4. 이 흐름은 adb-over-WiFi가 아니므로 기본적으로 `adb tcpip 5555`를 실행하지 않음

**예시**:
```bash
# 연결 후 APK 설치
adb install app/build/outputs/apk/debug/app-debug.apk

# 로그 확인
adb logcat | grep MtsaAccessibility
```

---

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

## WSL + Windows ADB 연결 설정

개발 환경이 WSL이고 안드로이드 폰이 Windows에 USB로 연결된 경우, Windows에서 ADB 서버를 실행하고 WSL에서 이에 접속해야 합니다.
이 워크플로우는 WSL의 adb 클라이언트를 Windows의 adb 서버(포트 5037)에 연결하는 방식이며, Android 기기에 직접 TCP 5555로 붙는 adb-over-WiFi 흐름이 아닙니다.

### 설정 순서

1. **Windows에서 ADB 서버 시작**
   ```cmd
   scripts\connect_adb_windows.bat
   ```
   - 안드로이드 폰이 USB로 연결되어 있는지 확인
   - USB 디버깅이 활성화되어 있는지 확인
   - 화면에 표시되는 Windows IP 주소 기억

2. **WSL에서 Windows ADB 서버에 연결**
   ```bash
   ./scripts/connect_adb_wsl.sh
   ```
   - Windows IP가 자동으로 감지됩니다
   - 수동 지정이 필요한 경우: `./scripts/connect_adb_wsl.sh WINDOWS_IP`

3. **이제 WSL에서 ADB 명령어 사용 가능**
   ```bash
   adb devices
   adb install app/build/outputs/apk/debug/app-debug.apk
   adb logcat | grep MtsaAccessibility
   ```

### 문제 해결

**Windows 방화벽 차단**
- Windows 방화벽에서 ADB(5037 포트) 허용 필요
- 또는 Windows 보안 → 방화벽 → 앱 허용에서 adb.exe 추가

**연결 안 됨**
1. Windows에서 `adb devices`로 기기가 보이는지 확인
2. Windows IP가 올바른지 확인 (cmd에서 `ipconfig`)
3. WSL에서 `ping WINDOWS_IP`로 네트워크 연결 확인

**영구 설정**
```bash
# ~/.bashrc 또는 ~/.zshrc에 추가
export ADB_SERVER_SOCKET=tcp:WINDOWS_IP:5037
```

---

## 추가 정보

자세한 사용법은 [HYBRID_STRATEGY.md](../HYBRID_STRATEGY.md)를 참조하세요.
