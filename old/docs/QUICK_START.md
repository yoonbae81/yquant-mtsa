# 하이브리드 전략 빠른 시작 가이드

## 5분 완성 튜토리얼

### 1단계: UI 분석 (2분)

```bash
# MTS 앱을 7201 잔고 화면으로 이동 후 실행
cd /Users/y/Github/yquant-mtsa/android-prototype
python3 scripts/analyze_ui.py
```

### 2단계: APK 빌드 (2분)

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 3단계: 테스트 (1분)

MTSA 앱을 열고 [잔고] 버튼 클릭 → 결과 확인!

## 상세 단계

### 사전 준비

1. **기기 연결 확인**
```bash
adb devices
# 출력: 824e1b4    device
```

2. **MTS 앱 준비**
- MTS 앱 설치 완료
- 로그인 완료
- 7201 화면(퇴직연금 ETF/리츠 주문)으로 이동

### UI 분석 실행

```bash
cd /Users/y/Github/yquant-mtsa/android-prototype

# 자동 분석 스크립트 실행
python3 scripts/analyze_ui.py
```

**출력 예시**:
```
============================================================
UI Structure Analyzer - Balance Screen
============================================================
Dumping UI hierarchy...
Analyzing balance screen...
  ✓ Found 1 nodes with '종목명'
    - Text: 종목명
      Resource ID: stock_name
      Class: android.widget.TextView
      Clickable: false
      Bounds: [100,200][300,230]
      Found 3 nearby nodes
        → Potential value: 삼성전자 (ID: stock_name_value)
  ✓ Found 1 nodes with '매도가능'
    - Text: 매도가능
      Resource ID: sellable_qty
      Class: android.widget.TextView
      Clickable: false
      Bounds: [100,250][300,280]
      Found 2 nearby nodes
        → Potential value: 10 (ID: sellable_qty_value)
  ✓ Found 1 nodes with '매입단가'
    - Text: 매입단가
      Resource ID: purchase_price
      Class: android.widget.TextView
      Clickable: false
      Bounds: [100,300][300,330]
      Found 2 nearby nodes
        → Potential value: 75,000 (ID: purchase_price_value)

✓ Configuration saved to app/src/main/assets/balance_extraction_rules.json
```

### 생성된 설정 파일 확인

```bash
cat app/src/main/assets/balance_extraction_rules.json
```

**예시 출력**:
```json
{
  "screen_name": "7201_balance",
  "version": "1.0",
  "rules": [
    {
      "label": "종목명",
      "target_text": "삼성전자",
      "resource_id": "stock_name_value",
      "extraction_method": "nearby_text",
      "position_offset": 1,
      "regex_pattern": ".*"
    },
    {
      "label": "매도가능",
      "target_text": "10",
      "resource_id": "sellable_qty_value",
      "extraction_method": "nearby_text",
      "position_offset": 1,
      "regex_pattern": "[\\d,]+"
    },
    {
      "label": "매입단가",
      "target_text": "75,000",
      "resource_id": "purchase_price_value",
      "extraction_method": "nearby_text",
      "position_offset": 1,
      "regex_pattern": "[\\d,]+"
    }
  ],
  "generated_at": "2024-04-26T00:00:00Z"
}
```

### APK 빌드 및 설치

```bash
# 빌드 (최초 1회만 필요)
./gradlew assembleDebug

# 설치
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 테스트

1. MTSA Prototype 앱 실행
2. 접근성 서비스 활성화 확인 (✓ 표시)
3. [잔고] 조회 버튼 클릭
4. 결과 확인:

```
=== 잔고 정보 ===
종목명: 삼성전자
매도가능: 10
매입단가: 75,000
```

## 문제 해결

### "잔고 텍스트를 찾을 수 없습니다"

**원인**: MTS 앱이 올바른 화면에 있지 않음

**해결**:
```bash
# MTS 앱이 7201 화면에 있는지 확인
adb shell dumpsys window windows | grep -E "mCurrentFocus"

# 7201 화면으로 이동
adb shell "am start -n com.truefriend.neosmartarenewal/.ui.main.MTSMainActivity -a ActionDeepLink -f 0x04000000 --es KeyOpenScreenNo 7201 --es KeyOpenScreenData ''"

# 다시 분석 실행
python3 scripts/analyze_ui.py
```

### "데이터를 추출할 수 없습니다"

**원인**: 생성된 규칙이 화면 구조와 맞지 않음

**해결**:
```bash
# 1. 생성된 JSON 확인
cat app/src/main/assets/balance_extraction_rules.json

# 2. 수동으로 규칙 수정
vim app/src/main/assets/balance_extraction_rules.json

# 3. 주요 수정 포인트:
#    - position_offset: 근처 노드 탐색 범위 (기본값: 1)
#    - regex_pattern: 값 패턴 (숫자: [\\d,]+, 텍스트: .*)
#    - extraction_method: nearby_text, resource_id, text 중 선택

# 4. APK 재빌드
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### "adb: command not found"

**해결**:
```bash
export PATH=$PATH:/Users/y/Downloads/platform-tools
```

## 고급 사용법

### 디바이스 지정

```bash
python3 scripts/analyze_ui.py --device 824e1b4
```

### 출력 파일 지정

```bash
python3 scripts/analyze_ui.py --output custom_rules.json
```

### 규칙 수동 테스트

생성된 규칙을 테스트하려면:

```bash
# 1. JSON 파일 수정
vim app/src/main/assets/balance_extraction_rules.json

# 2. 빌드
./gradlew assembleDebug

# 3. 설치
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 4. 로그 확인
adb logcat | grep -E "(ConfigManager|MtsaAccessibility)"
```

## 성공 확인 체크리스트

- [ ] ADB 기기 연결 완료 (`adb devices`)
- [ ] MTS 앱 로그인 완료
- [ ] MTS 앱이 7201 화면에 있음
- [ ] `analyze_ui.py` 실행 완료
- [ ] `balance_extraction_rules.json` 생성 확인
- [ ] APK 빌드 완료
- [ ] APK 설치 완료
- [ ] 접근성 서비스 활성화
- [ ] [잔고] 버튼 클릭
- [ ] 데이터 추출 성공

## 다음 단계

자세한 내용은 다음 문서를 참조하세요:
- [하이브리드 전략 상세 가이드](HYBRID_STRATEGY.md)
- [UI 분석 스크립트 가이드](scripts/README.md)
- [테스트 가이드](BALANCE_TEST_GUIDE.md)
