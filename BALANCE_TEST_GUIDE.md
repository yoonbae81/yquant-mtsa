# [잔고] 버튼 테스트 가이드

## 구현 개요

Accessibility Service 기반으로 7201 화면 이동 후 잔고 데이터를 자동으로 추출하는 [잔고] 버튼을 구현했습니다.

## 구현된 기능

### 1. UI 변경사항 (activity_main.xml)
- **[잔고] 조회 버튼** 추가: 기존 7201 화면 이동 버튼 아래에 배치
- **잔고 데이터 표시 영역** 추가: 추출된 종목명, 매도가능, 매입단가를 표시
- **상태 텍스트** 개선: 진행 상황을 사용자에게 명확하게 표시

### 2. MainActivity.kt 변경사항
- **getBalanceAfterDelay()**: [잔고] 버튼 클릭 시 2초 후 접근성 서비스에 명령 전송
- **registerBalanceReceiver()**: 접근성 서비스로부터 잔고 데이터 수신
- **balanceDataTextView**: 수신한 잔고 데이터를 화면에 표시
- **필요한 import 추가**: BroadcastReceiver, Context, IntentFilter

### 3. NeoSmartAccessibilityService.kt 변경사항
- **GET_BALANCE 명령 처리**: handleCommand()에 새로운 명령 추가
- **getBalanceFrom7201()**: 잔고 조회 메인 로직
  - 7201 화면으로 이동
  - 3초 대기 (화면 로딩)
  - "잔고" 텍스트 찾기
  - 잔고 링크 클릭
  - 2초 대기 (데이터 로딩)
  - 잔고 데이터 추출
- **findNodeByText()**: 텍스트로 접근성 노드 찾기 (재귀 탐색)
- **performClick()**: 노드 클릭 수행
- **extractBalanceData()**: 화면에서 종목명, 매도가능, 매입단가 추출
  - 모든 노드 수집
  - 라벨 근처의 값 찾기 (두 가지 방식)
- **collectAllNodes()**: 모든 접근성 노드 재귀적으로 수집
- **findNearbyLabel()**: 라벨 옆의 값 추출
  - 방법 1: 라벨 다음 5개 노드 중 숫자가 포함된 텍스트 찾기
  - 방법 2: "라벨:값" 형식의 텍스트 파싱
- **sendBalanceData()**: 추출된 데이터를 MainActivity로 브로드캐스트
- **필요한 import 추가**: AccessibilityNodeInfo, ArrayList

## 테스트 절차

### 사전 요구사항

1. **MTS 앱 설치**: 한국투자증권 MTS 앱이 기기에 설치되어 있어야 함
2. **접근성 서비스 활성화**:
   - 설정 > 접근성 > 접근성 서비스 > MTSA Prototype > 활성화
3. **MTS 앱 로그인**: 테스트 전에 MTS 앱에 로그인되어 있어야 함
4. **APK 빌드 및 설치**:
   ```bash
   cd /Users/y/Github/yquant-mtsa/android-prototype
   ./gradlew assembleDebug
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

### 테스트 단계

#### 1단계: 앱 실행 및 접근성 서비스 확인
1. MTSA Prototype 앱 실행
2. 상태 텍스트에 "✓ 접근성 서비스가 활성화되었습니다"가 표시되는지 확인
3. 빨간색 경고가 표시되면 접근성 서비스를 활성화

#### 2단계: [잔고] 버튼 클릭
1. "[잔고] 조회" 버튼 클릭
2. 상태 텍스트가 "잔고 조회 중입니다..."로 변경되는지 확인
3. 2초 후 "잔고 데이터를 가져오는 중입니다..."로 변경되는지 확인

#### 3단계: MTS 앱 동작 확인
1. MTS 앱이 자동으로 7201 화면으로 이동하는지 확인
2. 화면이 로딩될 때까지 기다림 (약 3초)
3. "잔고" 텍스트가 자동으로 클릭되는지 확인 (로그로 확인 가능)
4. 잔고 정보가 표시되는지 확인

#### 4단계: 결과 확인
1. 앱으로 돌아와서 "잔고 조회 완료" 메시지가 표시되는지 확인
2. balanceDataTextView에 다음 데이터가 표시되는지 확인:
   ```
   === 잔고 정보 ===
   종목명: [종목명]
   매도가능: [수량]
   매입단가: [가격]
   ```

#### 5단계: 로그 확인 (디버깅)
```bash
adb logcat | grep MtsaAccessibility
```

예상 로그:
```
D/MtsaAccessibility: Received command: GET_BALANCE
D/MtsaAccessibility: 잔고 조회 시작
D/MtsaAccessibility: 7201 화면으로 이동 명령 전송 완료
D/MtsaAccessibility: 잔고 노드 찾음: 잔고
D/MtsaAccessibility: 잔고 조회 완료
D/MtsaAccessibility: 잔고 데이터 전송: === 잔고 정보 ===...
```

## 예상되는 문제 및 해결 방법

### 문제 1: "잔고 텍스트를 찾을 수 없습니다"
**원인**: 7201 화면 구조가 다르거나 로딩이 늦음
**해결**:
- `Thread.sleep(3000)`을 늘려서 대기 시간 증가
- logcat으로 실제 화면 구조 확인
- 텍스트가 다른 언어로 표시되는지 확인

### 문제 2: "데이터를 추출할 수 없습니다"
**원인**: 화면 구조가 예상과 다름
**해결**:
- `findNearbyLabel()` 로직 수정 필요
- 노드 수 확인 후 탐색 범위 조정
- 실제 화면에서 데이터 위치 확인

### 문제 3: 접근성 서비스가 동작하지 않음
**원인**: 권한 부족 또는 서비스 비활성화
**해결**:
- 설정에서 접근성 서비스 다시 활성화
- 앱 권한 확인
- 기기 재부팅

## 향상 방향

1. **더 강력한 데이터 추출**:
   - resourceId 기반 검색 추가
   - OCR을 사용한 텍스트 인식 (필요한 경우)
   - 여러 화면 레이아웃 대응

2. **오류 처리 개선**:
   - 재시도 로직 추가
   - 사용자 피드백 개선
   - 타임아웃 최적화

3. **성능 최적화**:
   - 불필요한 대기 시간 제거
   - 화면 로딩 완료 감지
   - 캐싱 메커니즘

## 파일 변경 요약

### 수정된 파일
1. `android-prototype/app/src/main/res/layout/activity_main.xml`
   - [잔고] 버튼 추가
   - balanceDataTextView 추가

2. `android-prototype/app/src/main/java/com/yquant/mtsa/MainActivity.kt`
   - balanceButton 관련 코드 추가
   - getBalanceAfterDelay() 메서드 추가
   - registerBalanceReceiver() 메서드 추가
   - 필요한 import 추가

3. `android-prototype/app/src/main/java/com/yquant/mtsa/NeoSmartAccessibilityService.kt`
   - GET_BALANCE 명령 처리 추가
   - getBalanceFrom7201() 메서드 추가 (70라인)
   - 데이터 추출 관련 헬퍼 메서드 추가 (6개)
   - 필요한 import 추가

## 결론

이 구현은 Accessibility Service를 사용하여 MTS 앱의 7201 화면에서 잔고 정보를 자동으로 추출하는 프로토타입입니다. 접근성 서비스의 가능성을 테스트하고, 실제 앱 자동화에 대한 인사이트를 얻을 수 있습니다.
