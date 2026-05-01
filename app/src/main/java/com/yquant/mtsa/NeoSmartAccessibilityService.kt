package com.yquant.mtsa

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.ArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class NeoSmartAccessibilityService : AccessibilityService() {

    private var commandReceiver: BroadcastReceiver? = null
    private lateinit var configManager: ConfigManager
    private var extractionRules: List<ExtractionRule> = emptyList()
    private val textRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }
    private val loginInProgress = AtomicBoolean(false)

    private data class OcrTextBox(
        val text: String,
        val bounds: Rect,
    )

    private data class RetirementAccountTarget(
        val type: String,
        val accountPrefix: String,
        val accountName: String,
    )

    private data class RetirementHoldingSnapshot(
        val accountType: String,
        val accountLabel: String,
        val itemName: String?,
        val ticker: String?,
        val sellableQuantity: String?,
        val averagePurchasePrice: String?,
    )

    companion object {
        private const val TAG = "MtsaAccessibility"
        private const val MTS_PACKAGE = "com.truefriend.neosmartarenewal"
        private const val ACTION_NAVIGATE = "com.yquant.mtsa.ACTION_NAVIGATE"
        private const val ACTION_BALANCE_DATA = "com.yquant.mtsa.ACTION_BALANCE_DATA"
        private const val ACTION_LOGIN_STATUS = "com.yquant.mtsa.ACTION_LOGIN_STATUS"

        private const val CMD_LOGIN = "LOGIN"
        private const val CMD_NAVIGATE_TO_7201 = "NAVIGATE_TO_7201"
        private const val CMD_NAVIGATE_TO_RETIREMENT_ORDER = "NAVIGATE_TO_RETIREMENT_ORDER"
        private const val CMD_GET_BALANCE = "GET_BALANCE"
        private const val CMD_SWITCH_ACCOUNT = "SWITCH_ACCOUNT"

        private const val LOGIN_SCREEN_NO = "6300"
        private const val RETIREMENT_BALANCE_SCREEN_NO = "7202"
        private const val RETIREMENT_ORDER_SCREEN_NO = "7201"

        private val RETIREMENT_ACCOUNT_TARGETS = listOf(
            RetirementAccountTarget(
                type = "IRP",
                accountPrefix = "64923286",
                accountName = "개인형IRP",
            ),
            RetirementAccountTarget(
                type = "DC",
                accountPrefix = "64664736",
                accountName = "DC",
            ),
        )
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "AccessibilityService created")

        configManager = ConfigManager(this)
        extractionRules = configManager.loadRules()
        Log.d(TAG, "Loaded ${extractionRules.size} extraction rules")

        commandReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val command = intent?.getStringExtra("command")
                Log.d(TAG, "Received command: $command")
                handleCommand(command)
            }
        }

        val filter = IntentFilter(ACTION_NAVIGATE)
        registerReceiver(commandReceiver, filter)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event?.let {
            when (it.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    Log.d(TAG, "Window changed: ${it.packageName} / ${it.className}")
                }

                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                    // 상태 전이 확인용으로만 사용하고, 실제 처리는 rootInActiveWindow 재조회 기준으로 진행합니다.
                }

                else -> Unit
            }
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "AccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        commandReceiver?.let { unregisterReceiver(it) }
        Log.d(TAG, "AccessibilityService destroyed")
    }

    private fun handleCommand(command: String?) {
        when (command) {
            CMD_LOGIN -> performLogin()
            CMD_NAVIGATE_TO_7201 -> navigateToRetirementOrderScreen()
            CMD_NAVIGATE_TO_RETIREMENT_ORDER -> navigateToRetirementOrderScreen()
            CMD_GET_BALANCE -> getBalanceFrom7201()
            CMD_SWITCH_ACCOUNT -> switchAccount()
            else -> Log.w(TAG, "Unknown command: $command")
        }
    }

    private fun performLogin() {
        if (!loginInProgress.compareAndSet(false, true)) {
            sendLoginStatus("이미 로그인 자동화가 진행 중입니다.", false)
            return
        }

        Thread {
            try {
                sendLoginStatus("저장된 공동인증서 비밀번호를 확인하는 중입니다...")
                val certPassword = configManager.loadCredentialSettings().certPassword
                if (certPassword.isBlank()) {
                    sendLoginStatus("저장된 공동인증서 비밀번호를 찾지 못했습니다. 환경설정에서 비밀번호를 입력하세요.", false)
                    return@Thread
                }

                sendLoginStatus("공동인증서 로그인 화면으로 이동하는 중입니다...")
                if (!openCertificateLoginScreen()) {
                    sendLoginStatus("공동인증서 로그인 화면을 찾지 못했습니다.", false)
                    return@Thread
                }

                sendLoginStatus("공동인증서 정보를 확인하는 중입니다...")
                if (!waitForCertLoaded()) {
                    dumpCertLoginState()
                    sendLoginStatus("공동인증서를 찾을 수 없습니다. 기기에 인증서가 설치되어 있는지 확인하세요.", false)
                    return@Thread
                }

                sendLoginStatus("보안 키보드를 여는 중입니다...")
                sleep(1000)
                if (!openCertificatePasswordKeyboard()) {
                    dumpAllWindowsInfo()
                    sendLoginStatus("보안 키보드를 열지 못했습니다.", false)
                    return@Thread
                }

                sendLoginStatus("보안 키보드로 비밀번호를 입력하는 중입니다...")
                if (!enterCertificatePassword(certPassword)) {
                    sendLoginStatus("보안 키보드로 비밀번호 입력에 실패했습니다.", false)
                    return@Thread
                }

                sendLoginStatus("로그인 버튼 활성화를 기다리는 중입니다...")
                if (!submitCertificateLogin()) {
                    dumpCertLoginState()
                    sendLoginStatus("로그인 버튼을 실행하지 못했습니다. 비밀번호가 올바른지 확인하세요.", false)
                    return@Thread
                }

                sendLoginStatus("로그인 결과를 확인하는 중입니다...")
                if (!waitForCertificateLoginResult()) {
                    sendLoginStatus("로그인 성공을 확인하지 못했습니다.", false)
                    return@Thread
                }

                sendLoginStatus("공동인증서 로그인이 완료되었습니다.", true)
            } catch (e: Exception) {
                Log.e(TAG, "로그인 자동화 중 오류 발생", e)
                sendLoginStatus("로그인 자동화 중 오류가 발생했습니다: ${e.message}", false)
            } finally {
                loginInProgress.set(false)
            }
        }.start()
    }

    private fun openCertificateLoginScreen(): Boolean {
        val currentRoot = findCertLoginRootInAnyWindow()
        if (currentRoot != null) {
            return true
        }

        launchMtsApp()
        waitForMtsWindow(timeoutMs = 12000)
        return waitForCertificateLoginScreen(timeoutMs = 20000)
    }

    private fun navigateToRetirementOrderScreen() {
        try {
            openDeepLinkScreen(RETIREMENT_ORDER_SCREEN_NO)
            Log.d(TAG, "${RETIREMENT_ORDER_SCREEN_NO} 화면으로 이동 명령 전송 완료")
        } catch (e: Exception) {
            Log.e(TAG, "${RETIREMENT_ORDER_SCREEN_NO} 화면 이동 실패", e)
        }
    }

    private fun navigateToRetirementBalanceDirectScreen() {
        try {
            openDeepLinkScreen(RETIREMENT_BALANCE_SCREEN_NO)
            Log.d(TAG, "${RETIREMENT_BALANCE_SCREEN_NO} 화면으로 이동 명령 전송 완료")
        } catch (e: Exception) {
            Log.e(TAG, "${RETIREMENT_BALANCE_SCREEN_NO} 화면 이동 실패", e)
        }
    }

    private fun openDeepLinkScreen(screenNo: String) {
        val intent = Intent().apply {
            component = ComponentName(MTS_PACKAGE, "$MTS_PACKAGE.ui.main.MTSMainActivity")
            action = "ActionDeepLink"
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("KeyOpenScreenNo", screenNo)
            putExtra("KeyOpenScreenData", "")
        }

        startActivity(intent)
    }

    private fun launchMtsApp() {
        try {
            val intent = packageManager.getLaunchIntentForPackage(MTS_PACKAGE)
            intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            startActivity(intent)
            Log.d(TAG, "MTS 앱 시작 완료")
        } catch (e: Exception) {
            Log.e(TAG, "MTS 앱 시작 실패", e)
        }
    }

    private fun openCertificatePasswordKeyboard(): Boolean {
        val loginRoot = waitForRoot(timeoutMs = 8000) { isCertLoginRoot(it) }
        if (loginRoot == null) {
            Log.w(TAG, "openCertPwdKbd: cert login root not found")
            return false
        }

        val passwordField = firstVisibleNodeByViewId(loginRoot, "tf_cert_login_password")
            ?: firstVisibleNodeByViewId(loginRoot, "et_password")

        if (passwordField == null) {
            Log.w(TAG, "openCertPwdKbd: password field NOT found. Dumping view IDs:")
            dumpViewIds(loginRoot, "certLogin")
            return false
        }

        Log.d(TAG, "openCertPwdKbd: password field found, clickable=${passwordField.isClickable}, visible=${passwordField.isVisibleToUser}, bounds=${boundsStr(passwordField)}")

        repeat(3) { attempt ->
            Log.d(TAG, "openCertPwdKbd: click attempt ${attempt + 1}/3")
            val clicked = focusAndTapPasswordField(passwordField)
            Log.d(TAG, "openCertPwdKbd: interaction result=$clicked")

            if (clicked) {
                sleep(800)
                val transKeyRoot = findTransKeyInAnyWindow()
                if (transKeyRoot != null) {
                    Log.d(TAG, "openCertPwdKbd: TransKey found!")
                    return true
                }
                Log.d(TAG, "openCertPwdKbd: TransKey not found, checking all windows after click")
                for ((idx, window) in windows.withIndex()) {
                    val wr = window.root
                    Log.d(TAG, "  window[$idx]: pkg=${wr?.packageName}, class=${wr?.className}")
                }
            }
            sleep(400)
        }

        Log.w(TAG, "openCertPwdKbd: all attempts failed")
        dumpAllWindowsInfo()
        return false
    }

    private fun focusAndTapPasswordField(passwordField: AccessibilityNodeInfo): Boolean {
        passwordField.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        passwordField.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)

        val candidates = ArrayList<AccessibilityNodeInfo>()
        collectAllNodes(passwordField, candidates)

        val attemptedBounds = linkedSetOf<String>()
        var interacted = false

        for (candidate in candidates) {
            candidate.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            candidate.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)

            if (candidate.isClickable && candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                interacted = true
            }

            val bounds = Rect()
            candidate.getBoundsInScreen(bounds)
            if (bounds.width() <= 0 || bounds.height() <= 0) {
                continue
            }

            val key = bounds.flattenToString()
            if (!attemptedBounds.add(key)) {
                continue
            }

            if (tapCenter(bounds)) {
                interacted = true
            }

            val rightX = (bounds.right - 24).coerceAtLeast(bounds.left + 1).toFloat()
            if (tapPoint(rightX, bounds.centerY().toFloat())) {
                interacted = true
            }
        }

        return interacted
    }

    private fun findTransKeyInAnyWindow(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow
        if (root != null && isTransKeyRoot(root)) return root
        if (root != null && isTransKeyRootGeneric(root)) return root

        for (window in windows) {
            val windowRoot = window.root ?: continue
            if (isTransKeyRoot(windowRoot)) return windowRoot
            if (isTransKeyRootGeneric(windowRoot)) return windowRoot
        }

        return null
    }

    private fun isTransKeyRootGeneric(root: AccessibilityNodeInfo): Boolean {
        val nodes = ArrayList<AccessibilityNodeInfo>()
        collectAllNodes(root, nodes)
        val transKeyIds = listOf("fl_transkey", "keypadContainer", "transkey_navi_complete_button", "transkey_cursur_input")
        return nodes.any { node ->
            val id = node.viewIdResourceName ?: return@any false
            transKeyIds.any { suffix -> id.endsWith("/$suffix") || id.endsWith(":$suffix") }
        }
    }

    private fun dumpViewIds(root: AccessibilityNodeInfo, prefix: String) {
        val nodes = ArrayList<AccessibilityNodeInfo>()
        collectAllNodes(root, nodes)
        val ids = nodes.mapNotNull { it.viewIdResourceName }.distinct().sorted()
        Log.d(TAG, "[$prefix] Total nodes: ${nodes.size}, unique view IDs: ${ids.size}")
        ids.forEach { Log.d(TAG, "[$prefix] $it") }
    }

    private fun dumpAllWindowsInfo() {
        val allWindows = windows
        Log.d(TAG, "Total windows: ${allWindows.size}")
        for ((index, window) in allWindows.withIndex()) {
            val root = window.root
            Log.d(TAG, "window[$index]: type=${window.type}, pkg=${root?.packageName}, class=${root?.className}")
            if (root != null) {
                dumpViewIds(root, "window[$index]")
            }
        }
    }

    private fun boundsStr(node: AccessibilityNodeInfo): String {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        return rect.toShortString()
    }

    private fun waitForCertificateLoginScreen(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val certRoot = findCertLoginRootInAnyWindow()
            if (certRoot != null) {
                return true
            }

            val mtsRoot = findMtsRootInAnyWindow()
            if (mtsRoot != null && tapLoginEntryPoint(mtsRoot)) {
                val loginRoot = waitForRoot(timeoutMs = 5000) { isCertLoginRoot(it) }
                if (loginRoot != null) {
                    return true
                }
            }

            sleep(500)
        }

        return false
    }

    private fun tapLoginEntryPoint(root: AccessibilityNodeInfo): Boolean {
        val candidates = listOf(
            "공동인증서 로그인",
            "공동인증서",
            "인증서 로그인",
            "인증서",
            "로그인",
        )

        candidates.forEach { query ->
            val node = findFirstActionableNodeByTextOrDescription(root, query)
            if (node != null && clickNode(node)) {
                sleep(1000)
                return true
            }
        }

        return false
    }

    private fun enterCertificatePassword(password: String): Boolean {
        clearVirtualKeyboardInput()

        var expectedLength = virtualKeyboardPasswordLength() ?: 0
        for (char in password) {
            if (!pressVirtualKeyboardKey(char, expectedLength)) {
                Log.w(TAG, "Failed to enter virtual key: $char")
                return false
            }
            expectedLength += 1
        }

        return completeVirtualKeyboardEntry()
    }

    private fun clearVirtualKeyboardInput() {
        val root = waitForRoot(timeoutMs = 3000) { isTransKeyRoot(it) } ?: return
        firstVisibleNodeByViewId(root, "ib_clear")?.let { clearButton ->
            clickNode(clearButton)
            sleep(300)
        }
    }

    private fun pressVirtualKeyboardKey(char: Char, previousLength: Int): Boolean {
        repeat(12) {
            val root = waitForRoot(timeoutMs = 4000) { isTransKeyRoot(it) } ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && pressVirtualKeyboardKeyByOcr(root, char, previousLength)) {
                return true
            }

            val keyNode = findVirtualKeyboardKey(root, char)
            if (keyNode != null && clickNode(keyNode)) {
                sleep(350)
                return true
            }

            if (switchVirtualKeyboardForChar(root, char)) {
                sleep(500)
                val switchedRoot = waitForRoot(timeoutMs = 2000) { isTransKeyRoot(it) } ?: return false
                val switchedKeyNode = findVirtualKeyboardKey(switchedRoot, char)
                if (switchedKeyNode != null && clickNode(switchedKeyNode)) {
                    sleep(350)
                    return true
                }
            }

            if (!moveToNextVirtualKeyboardPage(root)) {
                return false
            }
        }

        return false
    }

    private fun pressVirtualKeyboardKeyByOcr(
        root: AccessibilityNodeInfo,
        char: Char,
        previousLength: Int,
    ): Boolean {
        val keyboardBounds = virtualKeyboardBounds(root)
        val boxes = readVisibleTextBoxes() ?: return false
        val keyBox = findOcrKeyBox(boxes, char, keyboardBounds)
        if (keyBox != null && tapCenter(keyBox.bounds)) {
            sleep(350)
            val currentLength = virtualKeyboardPasswordLength()
            if (currentLength == null || currentLength > previousLength) {
                return true
            }
        }

        if (switchVirtualKeyboardByOcr(root, boxes, keyboardBounds)) {
            sleep(500)
            val symbolBoxes = readVisibleTextBoxes() ?: return false
            val symbolKey = findOcrKeyBox(symbolBoxes, char, virtualKeyboardBounds(rootInActiveWindow ?: root))
            if (symbolKey != null && tapCenter(symbolKey.bounds)) {
                sleep(350)
                val currentLength = virtualKeyboardPasswordLength()
                return currentLength == null || currentLength > previousLength
            }

            switchVirtualKeyboardByOcr(rootInActiveWindow ?: root, symbolBoxes, virtualKeyboardBounds(rootInActiveWindow ?: root))
        }

        return false
    }

    private fun findOcrKeyBox(
        boxes: List<OcrTextBox>,
        char: Char,
        keyboardBounds: Rect,
    ): OcrTextBox? {
        val labels = expectedVirtualKeyLabels(char).map { normalizeOcrLabel(it) }.toSet()
        return boxes
            .filter { keyboardBounds.intersectedWith(it.bounds) }
            .filter { box ->
                val normalized = normalizeOcrLabel(box.text)
                normalized in labels || labels.any { normalized.contains(it) }
            }
            .minByOrNull { box ->
                val centerY = box.bounds.centerY()
                val centerX = box.bounds.centerX()
                centerY * 10_000 + centerX
            }
    }

    private fun switchVirtualKeyboardByOcr(
        root: AccessibilityNodeInfo,
        boxes: List<OcrTextBox>,
        keyboardBounds: Rect,
    ): Boolean {
        val switchBox = boxes
            .filter { keyboardBounds.intersectedWith(it.bounds) }
            .firstOrNull { box ->
                val label = normalizeOcrLabel(box.text)
                label == "a/@" || label == "a@" || label.contains("/@")
            }

        if (switchBox != null && tapCenter(switchBox.bounds)) {
            return true
        }

        val switchNode = findFirstActionableNodeByTextOrDescription(root, "a/@")
            ?: findFirstActionableNodeByTextOrDescription(root, "@")
            ?: findFirstActionableNodeByTextOrDescription(root, "특수문자변경")
        return switchNode != null && clickNode(switchNode)
    }

    private fun findVirtualKeyboardKey(root: AccessibilityNodeInfo, char: Char): AccessibilityNodeInfo? {
        val transKeyRoot = firstVisibleNodeByViewId(root, "fl_transkey") ?: root
        val candidates = ArrayList<AccessibilityNodeInfo>()
        collectAllNodes(transKeyRoot, candidates)

        val expectedLabels = expectedVirtualKeyLabels(char)
        return candidates.firstOrNull { node ->
            val label = nodeText(node)
            label.isNotBlank() && expectedLabels.any { expected ->
                label.equals(expected, ignoreCase = false) ||
                    label.contains(expected, ignoreCase = false)
            } && isActionableNode(node)
        }
    }

    private fun expectedVirtualKeyLabels(char: Char): List<String> {
        return when (char) {
            '@' -> listOf("@", "골뱅이")
            '.' -> listOf(".")
            '-' -> listOf("-")
            '_' -> listOf("_")
            else -> listOf(char.toString())
        }
    }

    private fun switchVirtualKeyboardForChar(root: AccessibilityNodeInfo, char: Char): Boolean {
        val queries = when (char) {
            '@', '.', '-', '_' -> listOf("특수문자변경", "문자변경", "키보드 변경")
            else -> listOf("영문", "소문자", "키보드 변경")
        }

        for (query in queries) {
            val node = findFirstActionableNodeByTextOrDescription(root, query)
            if (node != null && clickNode(node)) {
                return true
            }
        }

        return false
    }

    private fun moveToNextVirtualKeyboardPage(root: AccessibilityNodeInfo): Boolean {
        val nextButton = firstVisibleNodeByViewId(root, "transkey_navi_next_button")
            ?: return false

        val beforeSignature = keypadSignature(root)
        if (!clickNode(nextButton)) {
            return false
        }

        val moved = waitForRoot(timeoutMs = 2000) { updated ->
            isTransKeyRoot(updated) && keypadSignature(updated) != beforeSignature
        }

        return moved != null
    }

    private fun keypadSignature(root: AccessibilityNodeInfo): String {
        val transKeyRoot = firstVisibleNodeByViewId(root, "fl_transkey") ?: root
        val nodes = ArrayList<AccessibilityNodeInfo>()
        collectAllNodes(transKeyRoot, nodes)
        return nodes.map { nodeText(it) }
            .filter { it.isNotBlank() }
            .joinToString("|")
    }

    private fun virtualKeyboardPasswordLength(): Int? {
        val root = waitForRoot(timeoutMs = 2000) { isTransKeyRoot(it) } ?: return null
        val node = firstVisibleNodeByViewId(root, "et_password")
            ?: firstVisibleNodeByViewId(root, "etText")
            ?: return null
        return node.text?.length
    }

    private fun completeVirtualKeyboardEntry(): Boolean {
        val root = waitForRoot(timeoutMs = 3000) { isTransKeyRoot(it) } ?: return false
        val completeButton = firstVisibleNodeByViewId(root, "transkey_navi_complete_button")
            ?: firstVisibleNodeByViewId(root, "done")
            ?: findFirstActionableNodeByTextOrDescription(root, "입력완료")
        if (completeButton == null) {
            return completeVirtualKeyboardEntryByOcr(root)
        }

        if (!clickNode(completeButton)) {
            return false
        }

        return waitForRoot(timeoutMs = 5000) { isCertLoginRoot(it) } != null
    }

    private fun completeVirtualKeyboardEntryByOcr(root: AccessibilityNodeInfo): Boolean {
        val keyboardBounds = virtualKeyboardBounds(root)
        val boxes = readVisibleTextBoxes() ?: return false
        val completeBox = boxes
            .filter { keyboardBounds.intersectedWith(it.bounds) }
            .firstOrNull { normalizeOcrLabel(it.text).contains("입력완료") }
            ?: boxes
                .filter { keyboardBounds.intersectedWith(it.bounds) }
                .maxByOrNull { it.bounds.centerY() * 10_000 + it.bounds.centerX() }

        if (completeBox == null || !tapCenter(completeBox.bounds)) {
            return false
        }

        return waitForRoot(timeoutMs = 5000) { isCertLoginRoot(it) } != null
    }

    private fun readVisibleTextBoxes(): List<OcrTextBox>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.w(TAG, "Accessibility screenshot OCR requires Android 11(API 30)+")
            return null
        }

        val bitmap = takeCurrentScreenshotBitmap() ?: return null
        return try {
            recognizeTextBoxes(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    private fun takeCurrentScreenshotBitmap(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return null
        }

        val latch = CountDownLatch(1)
        val bitmapRef = AtomicReference<Bitmap?>()
        val errorRef = AtomicReference<Int?>()

        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val bitmap = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                        ?.copy(Bitmap.Config.ARGB_8888, false)
                    screenshot.hardwareBuffer.close()
                    bitmapRef.set(bitmap)
                    latch.countDown()
                }

                override fun onFailure(errorCode: Int) {
                    errorRef.set(errorCode)
                    latch.countDown()
                }
            },
        )

        if (!latch.await(3, TimeUnit.SECONDS)) {
            Log.w(TAG, "Timed out while taking accessibility screenshot")
            return null
        }

        errorRef.get()?.let { Log.w(TAG, "Accessibility screenshot failed: $it") }
        return bitmapRef.get()
    }

    private fun recognizeTextBoxes(bitmap: Bitmap): List<OcrTextBox>? {
        val image = InputImage.fromBitmap(bitmap, 0)
        val latch = CountDownLatch(1)
        val resultRef = AtomicReference<List<OcrTextBox>?>()
        val errorRef = AtomicReference<Exception?>()

        textRecognizer.process(image)
            .addOnSuccessListener { visionText ->
                val boxes = visionText.textBlocks.flatMap { block ->
                    block.lines.flatMap { line ->
                        line.elements.mapNotNull { element ->
                            val bounds = element.boundingBox
                            if (bounds == null) {
                                null
                            } else {
                                OcrTextBox(element.text, bounds)
                            }
                        }
                    }
                }
                resultRef.set(boxes)
                latch.countDown()
            }
            .addOnFailureListener { e ->
                errorRef.set(e)
                latch.countDown()
            }

        if (!latch.await(4, TimeUnit.SECONDS)) {
            Log.w(TAG, "Timed out while recognizing keyboard text")
            return null
        }

        errorRef.get()?.let { Log.w(TAG, "Keyboard OCR failed: ${it.message}") }
        return resultRef.get()
    }

    private fun virtualKeyboardBounds(root: AccessibilityNodeInfo): Rect {
        val keyboardNode = firstVisibleNodeByViewId(root, "fl_transkey")
            ?: firstVisibleNodeByViewId(root, "keypadContainer")
            ?: root
        val bounds = Rect()
        keyboardNode.getBoundsInScreen(bounds)
        return bounds
    }

    private fun tapCenter(bounds: Rect): Boolean {
        val x = bounds.centerX().toFloat()
        val y = bounds.centerY().toFloat()
        return tapPoint(x, y)
    }

    private fun tapPoint(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        val latch = CountDownLatch(1)
        val successRef = AtomicReference(false)

        dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    successRef.set(true)
                    latch.countDown()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    latch.countDown()
                }
            },
            null,
        )

        latch.await(1, TimeUnit.SECONDS)
        return successRef.get()
    }

    private fun normalizeOcrLabel(value: String): String {
        return value
            .trim()
            .replace(" ", "")
            .replace("\n", "")
            .replace("|", "I")
            .lowercase()
    }

    private fun Rect.intersectedWith(other: Rect): Boolean {
        return left < other.right && right > other.left && top < other.bottom && bottom > other.top
    }

    private fun submitCertificateLogin(): Boolean {
        val deadline = System.currentTimeMillis() + 8000
        while (System.currentTimeMillis() < deadline) {
            val root = findCertLoginRootInAnyWindow()
            if (root != null) {
                val submitButton = firstVisibleNodeByViewId(root, "btn_cert_login_start")
                if (submitButton != null && submitButton.isEnabled) {
                    Log.d(TAG, "Login button enabled, clicking")
                    return clickNode(submitButton)
                }
                Log.d(TAG, "Login button not yet enabled, waiting...")
            }
            sleep(500)
        }

        Log.w(TAG, "Timeout waiting for login button to enable, attempting click anyway")
        val root = waitForRoot(timeoutMs = 3000) { isCertLoginRoot(it) } ?: return false
        val submitButton = firstVisibleNodeByViewId(root, "btn_cert_login_start")
        if (submitButton == null) {
            Log.e(TAG, "Login button not found on cert login screen")
            return false
        }
        Log.w(TAG, "Login button isEnabled=${submitButton.isEnabled}")
        return clickNode(submitButton)
    }

    private fun waitForCertificateLoginResult(): Boolean {
        return waitForRoot(timeoutMs = 15000) { root ->
            !isCertLoginRoot(root) && !isTransKeyRoot(root)
        } != null
    }

    private fun waitForCertLoaded(): Boolean {
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            val root = findCertLoginRootInAnyWindow()
            if (root != null) {
                val certInfo = firstVisibleNodeByViewId(root, "cl_cert_login_info")
                val certInfoEmpty = firstVisibleNodeByViewId(root, "cl_cert_login_info_empty")
                if (certInfo != null && certInfo.isVisibleToUser && certInfoEmpty?.isVisibleToUser != true) {
                    val subjectName = firstVisibleNodeByViewId(root, "tv_cert_login_info_subject_name")
                    Log.d(TAG, "Cert loaded: ${subjectName?.text}")
                    return true
                }
            }
            sleep(300)
        }
        return false
    }

    private fun dumpCertLoginState() {
        val root = findCertLoginRootInAnyWindow() ?: rootInActiveWindow ?: run {
            Log.w(TAG, "[dumpCertLoginState] rootInActiveWindow is null")
            return
        }
        if (root.packageName?.toString() != MTS_PACKAGE) {
            Log.w(TAG, "[dumpCertLoginState] Not in MTS app: ${root.packageName}")
            return
        }

        val nodes = ArrayList<AccessibilityNodeInfo>()
        collectAllNodes(root, nodes)
        Log.d(TAG, "[dumpCertLoginState] Total nodes: ${nodes.size}")

        val certInfo = firstVisibleNodeByViewId(root, "cl_cert_login_info")
        val certInfoEmpty = firstVisibleNodeByViewId(root, "cl_cert_login_info_empty")
        val subjectName = firstVisibleNodeByViewId(root, "tv_cert_login_info_subject_name")
        val passwordField = firstVisibleNodeByViewId(root, "tf_cert_login_password")
        val loginButton = firstVisibleNodeByViewId(root, "btn_cert_login_start")

        Log.d(TAG, "[dumpCertLoginState] certInfo=${certInfo != null} visible=${certInfo?.isVisibleToUser}")
        Log.d(TAG, "[dumpCertLoginState] certInfoEmpty=${certInfoEmpty != null} visible=${certInfoEmpty?.isVisibleToUser}")
        Log.d(TAG, "[dumpCertLoginState] subjectName=${subjectName?.text}")
        Log.d(TAG, "[dumpCertLoginState] passwordField=${passwordField != null} text=${passwordField?.text}")
        Log.d(TAG, "[dumpCertLoginState] loginButton=${loginButton != null} enabled=${loginButton?.isEnabled}")
    }

    private fun isCertLoginRoot(root: AccessibilityNodeInfo): Boolean {
        return hasViewId(root, "cl_cert_login_content") ||
            hasViewId(root, "tv_cert_login_title") ||
            hasViewId(root, "btn_cert_login_start") ||
            hasViewId(root, "tf_cert_login_password")
    }

    private fun isTransKeyRoot(root: AccessibilityNodeInfo): Boolean {
        return hasViewId(root, "fl_transkey") ||
            hasViewId(root, "keypadContainer") ||
            hasViewId(root, "transkey_navi_complete_button")
    }

    private fun waitForRoot(
        timeoutMs: Long,
        intervalMs: Long = 250,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val matched = findRootInAnyWindow(predicate)
            if (matched != null) {
                return matched
            }
            sleep(intervalMs)
        }
        return null
    }

    private fun waitForMtsWindow(timeoutMs: Long): AccessibilityNodeInfo? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val mtsRoot = findMtsRootInAnyWindow()
            if (mtsRoot != null) {
                return mtsRoot
            }
            sleep(250)
        }
        return null
    }

    private fun findCertLoginRootInAnyWindow(): AccessibilityNodeInfo? {
        return findRootInAnyWindow { isCertLoginRoot(it) }
    }

    private fun findMtsRootInAnyWindow(): AccessibilityNodeInfo? {
        val activeRoot = rootInActiveWindow
        if (activeRoot != null && activeRoot.packageName?.toString() == MTS_PACKAGE) {
            return activeRoot
        }

        for (window in windows) {
            val root = window.root ?: continue
            if (root.packageName?.toString() == MTS_PACKAGE) {
                return root
            }
        }

        return null
    }

    private fun findRootInAnyWindow(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val activeRoot = rootInActiveWindow
        if (activeRoot != null && predicate(activeRoot)) {
            return activeRoot
        }

        for (window in windows) {
            val root = window.root ?: continue
            if (predicate(root)) {
                return root
            }
        }

        return null
    }

    private fun hasViewId(root: AccessibilityNodeInfo, id: String): Boolean {
        return findVisibleNodesByViewId(root, id).isNotEmpty()
    }

    private fun findVisibleNodesByViewId(root: AccessibilityNodeInfo, id: String): List<AccessibilityNodeInfo> {
        return root.findAccessibilityNodeInfosByViewId("$MTS_PACKAGE:id/$id")
            ?.filter { it.isVisibleToUser }
            .orEmpty()
    }

    private fun firstVisibleNodeByViewId(root: AccessibilityNodeInfo, id: String): AccessibilityNodeInfo? {
        return findVisibleNodesByViewId(root, id).firstOrNull()
    }

    private fun findFirstActionableNodeByTextOrDescription(
        root: AccessibilityNodeInfo,
        query: String,
    ): AccessibilityNodeInfo? {
        val nodes = ArrayList<AccessibilityNodeInfo>()
        collectAllNodes(root, nodes)
        return nodes.firstOrNull { node ->
            val text = nodeText(node)
            text.contains(query) && isActionableNode(node)
        }
    }

    private fun isActionableNode(node: AccessibilityNodeInfo): Boolean {
        return node.isClickable || node.parent?.isClickable == true
    }

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true
        }

        var parent = node.parent
        while (parent != null) {
            if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            parent = parent.parent
        }
        return false
    }

    private fun nodeText(node: AccessibilityNodeInfo): String {
        return node.text?.toString()?.trim().orEmpty()
            .ifBlank { node.contentDescription?.toString()?.trim().orEmpty() }
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun getBalanceFrom7201() {
        Thread {
            try {
                Log.d(TAG, "퇴직연금 ETF/리츠 잔고 조회 시작")

                if (!navigateToRetirementBalanceScreen()) {
                    sendBalanceData("오류: 퇴직연금 ETF 리츠 잔고 화면으로 이동하지 못했습니다")
                    return@Thread
                }

                val screenRoot = waitForMtsWindow(timeoutMs = 5000)
                if (screenRoot == null) {
                    sendBalanceData("오류: 퇴직연금 잔고 화면을 읽을 수 없습니다")
                    return@Thread
                }

                val snapshots = mutableListOf<RetirementHoldingSnapshot>()
                for (target in RETIREMENT_ACCOUNT_TARGETS) {
                    if (!selectRetirementAccount(target)) {
                        snapshots.add(
                            RetirementHoldingSnapshot(
                                accountType = target.type,
                                accountLabel = target.accountPrefix,
                                itemName = null,
                                ticker = null,
                                sellableQuantity = null,
                                averagePurchasePrice = null,
                            )
                        )
                        Log.w(TAG, "${target.type} 계좌 전환 실패")
                        continue
                    }

                    val refreshedRoot = waitForMtsWindow(timeoutMs = 5000)
                    if (refreshedRoot == null) {
                        Log.w(TAG, "${target.type} 계좌 화면을 읽을 수 없습니다")
                        snapshots.add(
                            RetirementHoldingSnapshot(
                                accountType = target.type,
                                accountLabel = target.accountPrefix,
                                itemName = null,
                                ticker = null,
                                sellableQuantity = null,
                                averagePurchasePrice = null,
                            )
                        )
                        continue
                    }

                    val snapshot = extractRetirementHoldingSnapshot(target, refreshedRoot)
                    snapshots.add(snapshot)
                    Log.d(TAG, "${target.type} 잔고 추출 결과: $snapshot")
                }

                val balanceData = formatRetirementHoldings(snapshots)
                sendBalanceData(balanceData)
                Log.d(TAG, "퇴직연금 ETF/리츠 잔고 조회 완료")
            } catch (e: Exception) {
                Log.e(TAG, "잔고 조회 중 오류 발생", e)
                sendBalanceData("오류: ${e.message}")
            }
        }.start()
    }

    private fun navigateToRetirementBalanceScreen(): Boolean {
        var root = waitForMtsWindow(timeoutMs = 5000)
        if (root == null) {
            launchMtsApp()
            root = waitForMtsWindow(timeoutMs = 12000)
        }

        root ?: return false
        if (isRetirementBalanceScreen(root)) {
            return true
        }

        navigateToRetirementBalanceDirectScreen()
        sleep(2000)
        val directRoot = waitForMtsWindow(timeoutMs = 8000)
        if (directRoot != null && isRetirementBalanceScreen(directRoot)) {
            return true
        }

        if (!openBottomMenu(root)) {
            return false
        }

        val menuRoot = waitForMtsWindow(timeoutMs = 5000) ?: return false
        if (!clickTextOrOcr(menuRoot, listOf("연금"))) {
            return false
        }

        sleep(1500)
        val pensionRoot = waitForMtsWindow(timeoutMs = 5000) ?: return false
        if (isRetirementBalanceScreen(pensionRoot)) {
            return true
        }

        val targetQueries = listOf(
            "퇴직연금ETF리츠 잔고",
            "퇴직연금 ETF 리츠 잔고",
            "퇴직연금ETF/리츠 잔고",
            "퇴직연금 ETF/리츠 잔고",
        )
        if (!clickTextOrOcr(pensionRoot, targetQueries)) {
            return false
        }

        sleep(2000)
        val finalRoot = waitForMtsWindow(timeoutMs = 8000) ?: return false
        return isRetirementBalanceScreen(finalRoot)
    }

    private fun openBottomMenu(root: AccessibilityNodeInfo): Boolean {
        if (clickTextOrOcr(root, listOf("메뉴"))) {
            sleep(1500)
            return true
        }

        if (tapBottomMenuFallback(root)) {
            sleep(1500)
            return true
        }

        return false
    }

    private fun tapBottomMenuFallback(root: AccessibilityNodeInfo): Boolean {
        val bounds = Rect()
        root.getBoundsInScreen(bounds)
        if (bounds.width() <= 0 || bounds.height() <= 0) {
            return false
        }

        val x = bounds.left + (bounds.width() * 0.11f)
        val y = bounds.bottom - (bounds.height() * 0.05f)
        Log.d(TAG, "Fallback tap for bottom menu at ($x, $y)")
        return tapPoint(x, y)
    }

    private fun clickTextOrOcr(root: AccessibilityNodeInfo, queries: List<String>): Boolean {
        if (clickTextNode(root, queries)) {
            return true
        }

        val boxes = readVisibleTextBoxes() ?: return false
        val normalizedQueries = queries.map { normalizeOcrLabel(it) }
        val matches = boxes.filter { box ->
            val normalized = normalizeOcrLabel(box.text)
            normalizedQueries.any { query ->
                normalized.contains(query) || query.contains(normalized)
            }
        }
        if (matches.isEmpty()) {
            return false
        }

        val candidate = if (normalizedQueries.any { it == normalizeOcrLabel("메뉴") }) {
            matches.maxByOrNull { it.bounds.centerY() * 10_000 + it.bounds.centerX() }
        } else {
            matches.minByOrNull { it.bounds.top * 10_000 + it.bounds.left }
        } ?: return false

        Log.d(TAG, "OCR tap matched text='${candidate.text}' for queries=$queries")
        return tapCenter(candidate.bounds)
    }

    private fun clickTextNode(root: AccessibilityNodeInfo, queries: List<String>): Boolean {
        for (query in queries) {
            val actionable = findFirstActionableNodeByTextOrDescription(root, query)
            if (actionable != null && clickNode(actionable)) {
                return true
            }

            val node = findNodeByText(root, query)
            if (node != null && clickNode(node)) {
                return true
            }
        }

        return false
    }

    private fun isRetirementBalanceScreen(root: AccessibilityNodeInfo): Boolean {
        if (hasRetirementBalanceMarkers(root)) {
            return true
        }

        val texts = ArrayList<AccessibilityNodeInfo>()
        collectAllNodes(root, texts)
        val normalizedTexts = texts.map { normalizeOcrLabel(nodeText(it)) }.filter { it.isNotBlank() }
        return normalizedTexts.any { it.contains(normalizeOcrLabel("퇴직연금")) } &&
            normalizedTexts.any { it.contains(normalizeOcrLabel("리츠")) } &&
            normalizedTexts.any { it.contains(normalizeOcrLabel("잔고")) }
    }

    private fun openRetirementBalanceTab(): Boolean {
        val root = waitForMtsWindow(timeoutMs = 5000) ?: return false

        if (hasRetirementBalanceMarkers(root)) {
            return true
        }

        val candidates = listOf("보유잔고 조회", "보유잔고", "잔고")
        for (candidate in candidates) {
            val node = findFirstActionableNodeByTextOrDescription(root, candidate)
                ?: findNodeByText(root, candidate)
            if (node != null && clickNode(node)) {
                sleep(1500)
                val updatedRoot = waitForMtsWindow(timeoutMs = 5000) ?: return false
                if (hasRetirementBalanceMarkers(updatedRoot)) {
                    return true
                }
            }
        }

        return false
    }

    private fun hasRetirementBalanceMarkers(root: AccessibilityNodeInfo): Boolean {
        val nodes = ArrayList<AccessibilityNodeInfo>()
        collectAllNodes(root, nodes)
        val visibleTexts = nodes.map { nodeText(it) }.filter { it.isNotBlank() }
        return visibleTexts.any { it.contains("매도가능") } &&
            visibleTexts.any { it.contains("매입단가") }
    }

    private fun selectRetirementAccount(target: RetirementAccountTarget): Boolean {
        val root = waitForMtsWindow(timeoutMs = 5000) ?: return false
        if (isTargetAccountVisible(root, target)) {
            return true
        }

        val dropdown = findRetirementAccountDropdown(root)
        if ((dropdown == null || !clickNode(dropdown)) && !clickTextOrOcr(root, listOf("DC", "IRP", "개인형IRP"))) {
            return false
        }

        sleep(1500)
        val selectionRoot = waitForMtsWindow(timeoutMs = 5000) ?: return false
        val targetNode = findRetirementAccountNode(selectionRoot, target)
        if ((targetNode == null || !clickNode(targetNode)) && !clickTextOrOcr(selectionRoot, accountQueriesFor(target))) {
            return false
        }

        sleep(2000)
        val updatedRoot = waitForMtsWindow(timeoutMs = 5000) ?: return false
        return isTargetAccountVisible(updatedRoot, target)
    }

    private fun isTargetAccountVisible(root: AccessibilityNodeInfo, target: RetirementAccountTarget): Boolean {
        val nodes = ArrayList<AccessibilityNodeInfo>()
        collectAllNodes(root, nodes)
        val queries = accountQueriesFor(target).map { normalizeOcrLabel(it) }
        return nodes.any { node ->
            val normalized = normalizeOcrLabel(nodeText(node))
            queries.any { query -> normalized.contains(query) }
        }
    }

    private fun findRetirementAccountDropdown(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val nodes = ArrayList<AccessibilityNodeInfo>()
        collectAllNodes(root, nodes)
        return nodes.firstOrNull { node ->
            val text = nodeText(node)
            val normalized = normalizeOcrLabel(text)
            text.contains('-') && (
                normalized.contains(normalizeOcrLabel("개인형IRP")) ||
                    normalized.contains(normalizeOcrLabel("IRP")) ||
                    normalized.contains(normalizeOcrLabel("DC"))
                )
        } ?: nodes.firstOrNull { node ->
            val normalized = normalizeOcrLabel(nodeText(node))
            normalized.contains(normalizeOcrLabel("개인형IRP")) ||
                normalized.contains(normalizeOcrLabel("IRP")) ||
                normalized.contains(normalizeOcrLabel("DC"))
        }
    }

    private fun findRetirementAccountNode(
        root: AccessibilityNodeInfo,
        target: RetirementAccountTarget,
    ): AccessibilityNodeInfo? {
        val nodes = ArrayList<AccessibilityNodeInfo>()
        collectAllNodes(root, nodes)
        val queries = accountQueriesFor(target).map { normalizeOcrLabel(it) }
        return nodes.firstOrNull { node ->
            val normalized = normalizeOcrLabel(nodeText(node))
            queries.any { query -> normalized.contains(query) }
        }
    }

    private fun accountQueriesFor(target: RetirementAccountTarget): List<String> {
        return listOf(target.accountPrefix, target.accountName, target.type)
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun extractRetirementHoldingSnapshot(
        target: RetirementAccountTarget,
        root: AccessibilityNodeInfo,
    ): RetirementHoldingSnapshot {
        val nodes = ArrayList<AccessibilityNodeInfo>()
        collectAllNodes(root, nodes)

        val accountLabel = nodes.map { nodeText(it) }
            .firstOrNull { text ->
                text.contains(target.accountPrefix) ||
                    (text.contains(target.accountName) && text.contains(target.type))
            }
            ?: target.accountPrefix

        val itemName = extractHoldingName(nodes)
        val explicitTicker = extractTicker(itemName)
        val inferredTicker = explicitTicker ?: extractTickerFromNodes(nodes)
        val sellableQuantity = extractLabeledValue(nodes, "매도가능", Regex("[\\d,]+"))
        val averagePurchasePrice = extractLabeledValue(nodes, "매입단가", Regex("[\\d,]+"))

        return RetirementHoldingSnapshot(
            accountType = target.type,
            accountLabel = accountLabel,
            itemName = itemName,
            ticker = inferredTicker,
            sellableQuantity = sellableQuantity,
            averagePurchasePrice = averagePurchasePrice,
        )
    }

    private fun extractHoldingName(nodes: List<AccessibilityNodeInfo>): String? {
        val texts = nodes.map { nodeText(it) }
        val sellableIndex = texts.indexOfFirst { it.contains("매도가능") }
        if (sellableIndex > 0) {
            for (index in sellableIndex - 1 downTo 0) {
                val candidate = texts[index].trim()
                if (isHoldingNameCandidate(candidate)) {
                    return candidate
                }
            }
        }

        return texts.firstOrNull { text ->
            val candidate = text.trim()
            isHoldingNameCandidate(candidate) && extractTicker(candidate) != null
        }
    }

    private fun isHoldingNameCandidate(text: String): Boolean {
        if (text.isBlank()) return false
        if (text.contains("매도가능") || text.contains("매입단가") || text.contains("잔고")) return false
        if (text.contains("IRP") || text.contains("DC") || text.contains("개인형IRP")) return false
        if (text.matches(Regex("[\\d,]+"))) return false
        return text.any { it.isLetter() }
    }

    private fun extractTicker(text: String?): String? {
        if (text.isNullOrBlank()) {
            return null
        }
        val match = Regex("(?<![A-Z0-9])([A-Z0-9]{6})(?![A-Z0-9])").find(text)
        return match?.groupValues?.get(1)
    }

    private fun extractTickerFromNodes(nodes: List<AccessibilityNodeInfo>): String? {
        for (node in nodes) {
            val text = nodeText(node)
            val ticker = extractTicker(text)
            if (ticker != null) {
                return ticker
            }
        }
        return null
    }

    private fun extractLabeledValue(
        nodes: List<AccessibilityNodeInfo>,
        label: String,
        valueRegex: Regex,
    ): String? {
        val texts = nodes.map { nodeText(it) }
        for (i in texts.indices) {
            val currentText = texts[i]
            if (!currentText.contains(label)) {
                continue
            }

            for (j in i + 1 until minOf(i + 5, texts.size)) {
                val candidate = texts[j]
                val match = valueRegex.find(candidate)
                if (match != null) {
                    return match.value
                }
            }

            val inlineMatch = valueRegex.find(currentText.substringAfter(label, ""))
            if (inlineMatch != null) {
                return inlineMatch.value
            }
        }
        return null
    }

    private fun formatRetirementHoldings(snapshots: List<RetirementHoldingSnapshot>): String {
        val builder = StringBuilder()
        builder.append("=== 퇴직연금 ETF/리츠 잔고 ===\n")

        for (snapshot in snapshots) {
            builder.append("[${snapshot.accountType}] ${snapshot.accountLabel}\n")
            builder.append("보유종목: ${snapshot.itemName ?: "미확인"}\n")
            builder.append("ticker: ${snapshot.ticker ?: "미확인"}\n")
            builder.append("매도가능: ${snapshot.sellableQuantity ?: "미확인"}\n")
            builder.append("매입단가: ${snapshot.averagePurchasePrice?.let { "${it}원" } ?: "미확인"}\n\n")
        }

        return builder.toString().trimEnd()
    }

    private fun findNodeByText(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        if (node.text?.contains(text) == true || node.contentDescription?.contains(text) == true) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeByText(child, text)
            if (found != null) {
                return found
            }
        }
        return null
    }

    private fun extractBalanceData(rootNode: AccessibilityNodeInfo): String {
        val balanceInfo = StringBuilder()
        balanceInfo.append("=== 잔고 정보 ===\n")

        val allNodes = ArrayList<AccessibilityNodeInfo>()
        collectAllNodes(rootNode, allNodes)

        Log.d(TAG, "Extracting data using ${extractionRules.size} rules from ${allNodes.size} nodes")

        for (rule in extractionRules) {
            val value = when (rule.extraction_method) {
                "nearby_text" -> extractByNearbyText(allNodes, rule)
                "resource_id" -> extractByResourceId(allNodes, rule)
                "text" -> extractByText(allNodes, rule)
                else -> {
                    Log.w(TAG, "Unknown extraction method: ${rule.extraction_method}")
                    null
                }
            }

            if (value != null) {
                balanceInfo.append("${rule.label}: $value\n")
                Log.d(TAG, "Extracted ${rule.label}: $value")
            } else {
                Log.w(TAG, "Failed to extract ${rule.label}")
            }
        }

        if (balanceInfo.length == 14) {
            balanceInfo.append("데이터를 추출할 수 없습니다.\n")
            balanceInfo.append("화면 구조: ${allNodes.size}개 노드 발견\n")
            balanceInfo.append("사용된 규칙 수: ${extractionRules.size}\n")
        }

        return balanceInfo.toString()
    }

    private fun extractByNearbyText(nodes: List<AccessibilityNodeInfo>, rule: ExtractionRule): String? {
        for (i in 0 until nodes.size - 1) {
            val currentNode = nodes[i]
            if (currentNode.text?.contains(rule.label) == true) {
                val startIdx = i + 1
                val endIdx = minOf(startIdx + rule.position_offset, nodes.size)

                for (j in startIdx until endIdx) {
                    val nextNode = nodes[j]
                    val text = nextNode.text?.toString()
                    if (!text.isNullOrBlank()) {
                        if (rule.regex_pattern != null) {
                            val regex = Regex(rule.regex_pattern)
                            val match = regex.find(text)
                            if (match != null) {
                                return match.value.trim()
                            }
                        } else {
                            return text.trim()
                        }
                    }
                }
            }
        }

        for (node in nodes) {
            val text = node.text
            if (text != null && text.contains(rule.label) && text.contains(":")) {
                val parts = text.split(":")
                if (parts.size >= 2) {
                    val value = parts[1].trim()
                    if (rule.regex_pattern != null) {
                        val regex = Regex(rule.regex_pattern)
                        val match = regex.find(value)
                        if (match != null) {
                            return match.value.trim()
                        }
                    }
                    return value
                }
            }
        }

        return null
    }

    private fun extractByResourceId(nodes: List<AccessibilityNodeInfo>, rule: ExtractionRule): String? {
        if (rule.resource_id.isEmpty()) {
            return null
        }

        for (node in nodes) {
            val nodeId = node.viewIdResourceName ?: continue
            if (nodeId.contains(rule.resource_id)) {
                val text = node.text?.toString()
                if (!text.isNullOrBlank()) {
                    if (rule.regex_pattern != null) {
                        val regex = Regex(rule.regex_pattern)
                        val match = regex.find(text)
                        if (match != null) {
                            return match.value.trim()
                        }
                    }
                    return text.trim()
                }
            }
        }

        return null
    }

    private fun extractByText(nodes: List<AccessibilityNodeInfo>, rule: ExtractionRule): String? {
        for (node in nodes) {
            val text = node.text?.toString() ?: continue
            if (text.contains(rule.target_text)) {
                return text.trim()
            }
        }
        return null
    }

    private fun collectAllNodes(node: AccessibilityNodeInfo, nodes: ArrayList<AccessibilityNodeInfo>) {
        nodes.add(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectAllNodes(child, nodes)
        }
    }

    private fun sendBalanceData(data: String) {
        val intent = Intent(ACTION_BALANCE_DATA)
        intent.putExtra("balance_data", data)
        sendBroadcast(intent)
        Log.d(TAG, "잔고 데이터 전송: $data")
    }

    private fun sendLoginStatus(status: String, success: Boolean = false) {
        val intent = Intent(ACTION_LOGIN_STATUS)
        intent.putExtra("status", status)
        intent.putExtra("success", success)
        sendBroadcast(intent)
        Log.d(TAG, "로그인 상태 전송: $status / success=$success")
    }

    private fun switchAccount() {
        Thread {
            try {
                Log.d(TAG, "계좌 변경 시작")

                val rootNode = rootInActiveWindow
                if (rootNode == null) {
                    Log.e(TAG, "rootInActiveWindow가 null입니다")
                    sendBalanceData("오류: 화면 정보를 가져올 수 없습니다")
                    return@Thread
                }

                val accountDropdown = findNodeByText(rootNode, "64923286")
                if (accountDropdown != null) {
                    Log.d(TAG, "계좌 드롭다운 찾음: ${accountDropdown.text}")
                    clickNode(accountDropdown)
                    sleep(2000)

                    val updatedRoot = rootInActiveWindow
                    if (updatedRoot == null) {
                        sendBalanceData("오류: 계좌 목록을 불러오지 못했습니다")
                        return@Thread
                    }

                    val accounts = mutableListOf<String>()
                    val allNodes = ArrayList<AccessibilityNodeInfo>()
                    collectAllNodes(updatedRoot, allNodes)

                    for (node in allNodes) {
                        val text = node.text?.toString() ?: ""
                        if (text.contains("DC") || text.contains("IRP") || text.contains("64664736") || text.contains("64923286")) {
                            if (!accounts.contains(text)) {
                                accounts.add(text)
                            }
                        }
                    }

                    Log.d(TAG, "발견된 계좌 목록: $accounts")

                    if (accounts.size > 1) {
                        val targetAccount = accounts[1]
                        val searchText = if (targetAccount.length > 10) targetAccount.substring(0, 10) else targetAccount
                        val targetNode = findNodeByText(updatedRoot, searchText)

                        if (targetNode != null) {
                            Log.d(TAG, "다른 계좌 클릭: $targetAccount")
                            clickNode(targetNode)
                            sleep(2000)

                            val refreshedRoot = rootInActiveWindow
                            if (refreshedRoot == null) {
                                sendBalanceData("오류: 계좌 변경 후 화면을 읽지 못했습니다")
                                return@Thread
                            }

                            val balanceData = extractBalanceData(refreshedRoot)
                            sendBalanceData(balanceData)
                        } else {
                            Log.w(TAG, "다른 계좌를 찾을 수 없습니다")
                            sendBalanceData("오류: 다른 계좌를 찾을 수 없습니다")
                        }
                    } else {
                        Log.w(TAG, "다른 계좌가 없습니다")
                        sendBalanceData("오류: 다른 계좌가 없습니다")
                    }
                } else {
                    Log.w(TAG, "계좌 드롭다운을 찾을 수 없습니다")
                    sendBalanceData("오류: 계좌 드롭다운을 찾을 수 없습니다")
                }
            } catch (e: Exception) {
                Log.e(TAG, "계좌 변경 중 오류 발생", e)
                sendBalanceData("오류: ${e.message}")
            }
        }.start()
    }
}
