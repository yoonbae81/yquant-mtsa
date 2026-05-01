package com.yquant.mtsa

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayList

class NeoSmartAccessibilityService : AccessibilityService() {

    private var commandReceiver: BroadcastReceiver? = null
    private lateinit var configManager: ConfigManager
    private var extractionRules: List<ExtractionRule> = emptyList()

    companion object {
        private const val TAG = "MtsaAccessibility"
        private const val MTS_PACKAGE = "com.truefriend.neosmartarenewal"
        private const val ACTION_NAVIGATE = "com.yquant.mtsa.ACTION_NAVIGATE"
        private const val ACTION_BALANCE_DATA = "com.yquant.mtsa.ACTION_BALANCE_DATA"
        private const val ACTION_LOGIN_STATUS = "com.yquant.mtsa.ACTION_LOGIN_STATUS"

        private const val CMD_LOGIN = "LOGIN"
        private const val CMD_NAVIGATE_TO_7201 = "NAVIGATE_TO_7201"
        private const val CMD_GET_BALANCE = "GET_BALANCE"
        private const val CMD_SWITCH_ACCOUNT = "SWITCH_ACCOUNT"

        private const val LOGIN_SCREEN_NO = "6300"
        private const val TARGET_SCREEN_NO = "7201"
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
            CMD_NAVIGATE_TO_7201 -> navigateToScreen7201()
            CMD_GET_BALANCE -> getBalanceFrom7201()
            CMD_SWITCH_ACCOUNT -> switchAccount()
            else -> Log.w(TAG, "Unknown command: $command")
        }
    }

    private fun performLogin() {
        Thread {
            try {
                sendLoginStatus("로그인 설정을 불러오는 중입니다...")
                val loginConfig = configManager.loadLoginCertConfig()
                if (loginConfig == null) {
                    sendLoginStatus("config.yaml의 login.cert 설정을 읽지 못했습니다.", false)
                    return@Thread
                }

                sendLoginStatus("로그인 화면(6300)으로 이동 중입니다...")
                ensureLoginScreen()

                val loginRoot = waitForRoot(timeoutMs = 15000) { isCertLoginRoot(it) }
                if (loginRoot == null) {
                    sendLoginStatus("공동인증서 로그인 화면을 찾지 못했습니다.", false)
                    return@Thread
                }

                sendLoginStatus("공동인증서 '${loginConfig.name}' 선택 확인 중입니다...")
                if (!selectCertificate(loginConfig.name)) {
                    sendLoginStatus("공동인증서 '${loginConfig.name}'를 선택하지 못했습니다.", false)
                    return@Thread
                }

                sendLoginStatus("공동인증서 비밀번호 입력창을 여는 중입니다...")
                if (!openCertPasswordField()) {
                    sendLoginStatus("비밀번호 입력창을 열지 못했습니다.", false)
                    return@Thread
                }

                sendLoginStatus("가상 키보드로 공동인증서 비밀번호를 입력 중입니다...")
                if (!enterPasswordWithVirtualKeyboard(loginConfig.password)) {
                    sendLoginStatus("가상 키보드로 비밀번호 입력에 실패했습니다.", false)
                    return@Thread
                }

                if (!completeVirtualKeyboardEntry()) {
                    sendLoginStatus("가상 키보드 완료 버튼을 누르지 못했습니다.", false)
                    return@Thread
                }

                sendLoginStatus("로그인 버튼을 눌러 결과를 확인하는 중입니다...")
                if (!submitCertLogin()) {
                    sendLoginStatus("로그인 버튼을 실행하지 못했습니다.", false)
                    return@Thread
                }

                if (!verifyLoginSuccess()) {
                    sendLoginStatus("로그인 성공을 확인하지 못했습니다.", false)
                    return@Thread
                }

                sendLoginStatus("공동인증서 로그인 성공이 확인되었습니다.", true)
            } catch (e: Exception) {
                Log.e(TAG, "로그인 자동화 중 오류 발생", e)
                sendLoginStatus("로그인 자동화 중 오류가 발생했습니다: ${e.message}", false)
            }
        }.start()
    }

    private fun ensureLoginScreen() {
        if (!isMtsAppRunning()) {
            launchMtsApp()
            sleep(2500)
        }

        openDeepLinkScreen(LOGIN_SCREEN_NO)
        sleep(1500)
    }

    private fun navigateToScreen7201() {
        try {
            openDeepLinkScreen(TARGET_SCREEN_NO)
            Log.d(TAG, "7201 화면으로 이동 명령 전송 완료")
        } catch (e: Exception) {
            Log.e(TAG, "7201 화면 이동 실패", e)
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

    private fun isMtsAppRunning(): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        return rootNode.packageName?.toString() == MTS_PACKAGE
    }

    private fun launchMtsApp() {
        try {
            val intent = packageManager.getLaunchIntentForPackage(MTS_PACKAGE)
            intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            Log.d(TAG, "MTS 앱 시작 완료")
        } catch (e: Exception) {
            Log.e(TAG, "MTS 앱 시작 실패", e)
        }
    }

    private fun selectCertificate(certName: String): Boolean {
        if (isCurrentCertificateSelected(certName)) {
            return true
        }

        val loginRoot = waitForRoot(timeoutMs = 8000) { isCertLoginRoot(it) } ?: return false
        firstVisibleNodeByViewId(loginRoot, "tv_cert_login_all")?.let {
            clickNode(it)
            sleep(1000)
        }

        val selected = waitForRoot(timeoutMs = 8000) { root ->
            val directMatch = findVisibleNodesByViewId(root, "tv_cert_login_info_subject_name")
                .any { nodeText(it).contains(certName) }
            if (directMatch) {
                return@waitForRoot true
            }

            val candidate = findFirstNodeByTextOrDescription(root, certName)
            if (candidate != null) {
                clickNode(candidate)
                sleep(800)
            }
            isCurrentCertificateSelected(certName)
        }

        return selected != null && isCurrentCertificateSelected(certName)
    }

    private fun isCurrentCertificateSelected(certName: String): Boolean {
        val root = rootInActiveWindow ?: return false
        return findVisibleNodesByViewId(root, "tv_cert_login_info_subject_name")
            .any { nodeText(it).contains(certName) }
    }

    private fun openCertPasswordField(): Boolean {
        val loginRoot = waitForRoot(timeoutMs = 8000) { isCertLoginRoot(it) } ?: return false
        val passwordField = firstVisibleNodeByViewId(loginRoot, "tf_cert_login_password") ?: return false
        if (!clickNode(passwordField)) {
            return false
        }

        return waitForRoot(timeoutMs = 8000) { isTransKeyRoot(it) } != null
    }

    private fun enterPasswordWithVirtualKeyboard(password: String): Boolean {
        clearVirtualKeyboardIfNeeded()

        var expectedLength = currentPasswordLength() ?: 0
        for (char in password) {
            if (!pressVirtualKey(char, expectedLength)) {
                Log.w(TAG, "Failed to enter virtual key: $char")
                return false
            }
            expectedLength += 1
        }

        return true
    }

    private fun clearVirtualKeyboardIfNeeded() {
        val root = waitForRoot(timeoutMs = 3000) { isTransKeyRoot(it) } ?: return
        firstVisibleNodeByViewId(root, "ib_clear")?.let {
            clickNode(it)
            sleep(300)
        }
    }

    private fun pressVirtualKey(char: Char, previousLength: Int): Boolean {
        repeat(8) {
            val root = waitForRoot(timeoutMs = 4000) { isTransKeyRoot(it) } ?: return false
            val keyNode = findVirtualKeyNode(root, char)
            if (keyNode != null && clickNode(keyNode)) {
                sleep(350)
                val currentLength = currentPasswordLength()
                if (currentLength != null && currentLength > previousLength) {
                    return true
                }
            }

            if (!moveToNextVirtualKeyboardPage(root)) {
                return false
            }
        }

        return false
    }

    private fun findVirtualKeyNode(root: AccessibilityNodeInfo, char: Char): AccessibilityNodeInfo? {
        val transKeyRoot = firstVisibleNodeByViewId(root, "fl_transkey") ?: root
        val candidates = ArrayList<AccessibilityNodeInfo>()
        collectAllNodes(transKeyRoot, candidates)

        val expectedLabels = expectedKeyLabels(char)
        return candidates.firstOrNull { node ->
            val label = nodeText(node)
            label.isNotBlank() && expectedLabels.any { expected ->
                label.equals(expected, ignoreCase = false) ||
                    label.contains(expected, ignoreCase = false)
            } && isActionableNode(node)
        }
    }

    private fun expectedKeyLabels(char: Char): List<String> {
        return when (char) {
            '@' -> listOf("@", "골뱅이")
            '.' -> listOf(".")
            '-' -> listOf("-")
            '_' -> listOf("_")
            else -> listOf(char.toString())
        }
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

    private fun currentPasswordLength(): Int? {
        val root = waitForRoot(timeoutMs = 2000) { isTransKeyRoot(it) } ?: return null
        val node = firstVisibleNodeByViewId(root, "et_password") ?: return null
        return node.text?.length
    }

    private fun completeVirtualKeyboardEntry(): Boolean {
        val root = waitForRoot(timeoutMs = 3000) { isTransKeyRoot(it) } ?: return false
        val completeButton = firstVisibleNodeByViewId(root, "transkey_navi_complete_button")
            ?: firstVisibleNodeByViewId(root, "done")
            ?: return false

        if (!clickNode(completeButton)) {
            return false
        }

        return waitForRoot(timeoutMs = 5000) { isCertLoginRoot(it) } != null
    }

    private fun submitCertLogin(): Boolean {
        val root = waitForRoot(timeoutMs = 5000) { isCertLoginRoot(it) } ?: return false
        val submitButton = firstVisibleNodeByViewId(root, "btn_cert_login_start") ?: return false
        return clickNode(submitButton)
    }

    private fun verifyLoginSuccess(): Boolean {
        val loginGone = waitForRoot(timeoutMs = 15000) { root ->
            !isCertLoginRoot(root) && !isTransKeyRoot(root)
        } != null
        if (!loginGone) {
            return false
        }

        openDeepLinkScreen(TARGET_SCREEN_NO)
        val postLoginRoot = waitForRoot(timeoutMs = 8000) { root ->
            root.packageName?.toString() == MTS_PACKAGE && !isCertLoginRoot(root)
        }

        return postLoginRoot != null
    }

    private fun isCertLoginRoot(root: AccessibilityNodeInfo): Boolean {
        return hasViewId(root, "cl_cert_login_content") ||
            hasViewId(root, "tv_cert_login_title") ||
            hasViewId(root, "btn_cert_login_start")
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
            val root = rootInActiveWindow
            if (root != null && root.packageName?.toString() == MTS_PACKAGE && predicate(root)) {
                return root
            }
            sleep(intervalMs)
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

    private fun findFirstNodeByTextOrDescription(root: AccessibilityNodeInfo, query: String): AccessibilityNodeInfo? {
        val nodes = ArrayList<AccessibilityNodeInfo>()
        collectAllNodes(root, nodes)
        return nodes.firstOrNull { node ->
            nodeText(node).contains(query) && isActionableNode(node)
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
                Log.d(TAG, "잔고 조회 시작")

                navigateToScreen7201()
                sleep(3000)

                val rootNode = rootInActiveWindow
                if (rootNode == null) {
                    Log.e(TAG, "rootInActiveWindow가 null입니다")
                    sendBalanceData("오류: 화면 정보를 가져올 수 없습니다")
                    return@Thread
                }

                val balanceNode = findNodeByText(rootNode, "잔고")
                if (balanceNode != null) {
                    Log.d(TAG, "잔고 노드 찾음: ${balanceNode.text}")
                    clickNode(balanceNode)
                    sleep(2000)
                } else {
                    Log.w(TAG, "잔고 텍스트를 찾을 수 없습니다")
                }

                sleep(1000)
                val refreshedRoot = rootInActiveWindow
                if (refreshedRoot == null) {
                    sendBalanceData("오류: 잔고 화면을 다시 읽을 수 없습니다")
                    return@Thread
                }

                val balanceData = extractBalanceData(refreshedRoot)
                sendBalanceData(balanceData)

                Log.d(TAG, "잔고 조회 완료")
            } catch (e: Exception) {
                Log.e(TAG, "잔고 조회 중 오류 발생", e)
                sendBalanceData("오류: ${e.message}")
            }
        }.start()
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
