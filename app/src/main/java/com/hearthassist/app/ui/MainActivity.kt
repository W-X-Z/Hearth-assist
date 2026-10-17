@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.hearthassist.app.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.hearthassist.app.AppGraph
import com.hearthassist.app.BuildConfig
import com.hearthassist.app.logs.HearthstoneFiles
import com.hearthassist.app.logs.ShizukuAccess
import com.hearthassist.app.logs.ShizukuState
import com.hearthassist.app.overlay.OverlayService
import com.hearthassist.app.settings.LogAccess
import com.hearthassist.core.arena.ArenaStatsUrls
import com.hearthassist.core.deck.Deckstring
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    private val resumeTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AppGraph.init(this)
        setContent {
            HaTheme {
                AppRoot(resumeTick.intValue)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        AppGraph.shizuku.refresh()
        resumeTick.intValue++
    }
}

private enum class Screen { HOME, TIER_LIST }

@Composable
private fun AppRoot(resumeTick: Int) {
    var screen by remember { mutableStateOf(Screen.HOME) }
    BackHandler(enabled = screen != Screen.HOME) { screen = Screen.HOME }
    Scaffold(containerColor = HaColors.Background) { padding ->
        when (screen) {
            Screen.HOME -> HomeScreen(resumeTick, padding) { screen = Screen.TIER_LIST }
            Screen.TIER_LIST -> TierListScreen(onBack = { screen = Screen.HOME }, contentPadding = padding)
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = HaColors.Surface),
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, color = HaColors.Gold, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            content()
        }
    }
}

@Composable
private fun StatusLine(ok: Boolean?, text: String) {
    val (mark, color) = when (ok) {
        true -> "●" to HaColors.Good
        false -> "●" to HaColors.Bad
        null -> "●" to HaColors.Gold
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(mark, color = color, fontSize = 12.sp)
        Spacer(Modifier.padding(start = 6.dp))
        Text(text, fontSize = 14.sp)
    }
}

@Composable
private fun Note(text: String) {
    Text(text, color = HaColors.TextDim, fontSize = 12.sp)
}

@Composable
private fun HomeScreen(resumeTick: Int, padding: PaddingValues, openTierList: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by AppGraph.settings.state.collectAsState()
    val running by OverlayService.running.collectAsState()
    val shizukuState by AppGraph.shizuku.state.collectAsState()
    val logStatus by AppGraph.logs.status.collectAsState()
    val cardInfo by AppGraph.cards.info.collectAsState()
    val statsStatus by AppGraph.stats.status.collectAsState()
    val tracker by AppGraph.engine.tracker.collectAsState()
    val draft by AppGraph.engine.draft.collectAsState()
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    // Re-read permission state every time the activity resumes.
    val canOverlay = remember(resumeTick) { Settings.canDrawOverlays(context) }
    val notificationsOk = remember(resumeTick) {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
    val storageOk = remember(resumeTick) {
        Build.VERSION.SDK_INT > Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }
    val hearthstoneInstalled = remember(resumeTick) { isInstalled(context, HearthstoneFiles.PACKAGE) }
    // Permission state is re-read on resume (resumeTick), so the callback has nothing to do.
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text("Hearth Assist", color = HaColors.Gold, fontWeight = FontWeight.Black, fontSize = 26.sp)
        Note("하스스톤 모바일용 덱 트래커 · 투기장 카드 점수  v${BuildConfig.VERSION_NAME}")
        message?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = HaColors.Gold, fontSize = 13.sp)
        }

        Section("오버레이") {
            StatusLine(running, if (running) "실행 중" else "꺼짐")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = canOverlay,
                    onClick = {
                        if (running) OverlayService.stop(context) else OverlayService.start(context)
                    },
                ) { Text(if (running) "오버레이 중지" else "오버레이 시작") }
                if (!hearthstoneInstalled) Note("하스스톤이 설치되어 있지 않습니다")
            }
            Note("시작 후 하스스톤을 실행하세요. 떠 있는 'HA' 버튼을 눌러 트래커를 펼치고, 투기장 탭에서 '화면 인식 시작'을 누르면 드래프트 카드 위에 점수가 표시됩니다.")
        }

        Section("1. 권한") {
            StatusLine(canOverlay, "다른 앱 위에 표시")
            if (!canOverlay) {
                OutlinedButton(onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")),
                    )
                }) { Text("권한 설정 열기") }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                StatusLine(notificationsOk, "알림 (실행 중 표시)")
                if (!notificationsOk) {
                    OutlinedButton(onClick = { permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("알림 허용") }
                }
            }
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
                StatusLine(storageOk, "저장소 (하스스톤 로그 읽기)")
                if (!storageOk) {
                    OutlinedButton(onClick = { permissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE) }) { Text("저장소 허용") }
                }
            }
        }

        Section("2. 하스스톤 로그 연결 (덱 트래커)") {
            Note("Android 11 이상에서는 다른 앱의 폴더를 읽으려면 Shizuku가 필요합니다. Shizuku 앱에서 '무선 디버깅으로 시작' 후 아래에서 권한을 허용하세요.")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LogAccess.entries.forEach { mode ->
                    FilterChip(
                        selected = settings.logAccess == mode,
                        onClick = { AppGraph.settings.update { it.copy(logAccess = mode) } },
                        label = { Text(logAccessLabel(mode), fontSize = 12.sp) },
                    )
                }
            }
            StatusLine(shizukuState == ShizukuState.READY, "Shizuku: ${shizukuState.label}")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (shizukuState) {
                    ShizukuState.NOT_INSTALLED, ShizukuState.OUTDATED -> OutlinedButton(onClick = { openShizukuDownload(context) }) { Text("Shizuku 설치") }
                    ShizukuState.NOT_RUNNING -> OutlinedButton(onClick = { launchApp(context, ShizukuAccess.SHIZUKU_PACKAGE) }) { Text("Shizuku 열기") }
                    ShizukuState.NO_PERMISSION -> Button(onClick = { AppGraph.shizuku.requestPermission() }) { Text("권한 요청") }
                    ShizukuState.READY -> Unit
                }
            }
            StatusLine(
                when (logStatus.state) {
                    com.hearthassist.app.logs.LogState.WATCHING -> true
                    com.hearthassist.app.logs.LogState.STOPPED, com.hearthassist.app.logs.LogState.CONNECTING -> null
                    else -> false
                },
                "로그: ${logStatus.state.label}" + (logStatus.source?.let { " ($it)" } ?: ""),
            )
            logStatus.dir?.let { Note(it) }
            if (logStatus.lastActivity > 0) Note("마지막 로그: " + DateFormat.getTimeInstance().format(Date(logStatus.lastActivity)))
            Button(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    val result = AppGraph.logs.installLogConfig()
                    message = result.getOrElse { "실패: ${it.message}" }
                    busy = false
                }
            }) { Text("log.config 설정 (최초 1회)") }
            Note("설정 후 하스스톤을 완전히 종료했다가 다시 실행해야 로그가 기록됩니다. 오버레이가 켜져 있는 동안 로그를 읽습니다.")
            if (tracker.gameNumber > 0) {
                Note("최근 게임: 덱 ${tracker.playerDeckCount}장, 손 ${tracker.playerHandCount}장" + (tracker.deckName?.let { " · $it" } ?: ""))
            }
            if (draft.pickCount > 0) {
                Note("투기장 드래프트: ${draft.heroClass?.koName ?: "?"} ${draft.pickCount}/30")
            }
        }

        Section("3. 데이터") {
            StatusLine(cardInfo.loaded, "카드 ${cardInfo.count}장 (${cardInfo.source})")
            OutlinedButton(enabled = !busy, onClick = {
                busy = true
                message = "카드 데이터를 받는 중…"
                scope.launch {
                    message = AppGraph.cards.update(settings.gameLocale).fold(
                        { "카드 데이터 업데이트 완료 ($it 장)" },
                        { "카드 데이터 업데이트 실패: ${it.message}" },
                    )
                    busy = false
                }
            }) { Text("카드 데이터 업데이트 (새 확장팩)") }

            Spacer(Modifier.height(4.dp))
            StatusLine(
                if (statsStatus.loading) null else statsStatus.cards > 0,
                if (statsStatus.cards > 0) "투기장 통계: 카드 ${statsStatus.cards}장" else "투기장 통계 없음",
            )
            statsStatus.lastUpdated?.let { Note("통계 갱신: $it") }
            statsStatus.error?.let { Note("오류: $it") }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ArenaStatsUrls.MODES.forEach { m ->
                    FilterChip(
                        selected = settings.statsMode == m,
                        onClick = { AppGraph.settings.update { it.copy(statsMode = m) } },
                        label = { Text(if (m == "arena") "투기장" else "언더그라운드", fontSize = 12.sp) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("last-patch" to "최근 패치", "past-7" to "7일", "past-3" to "3일").forEach { (p, label) ->
                    FilterChip(
                        selected = settings.statsPeriod == p,
                        onClick = { AppGraph.settings.update { it.copy(statsPeriod = p) } },
                        label = { Text(label, fontSize = 12.sp) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        message = AppGraph.stats.refresh().fold({ "통계 새로고침 완료 ($it 장)" }, { "통계 새로고침 실패: ${it.message}" })
                        busy = false
                    }
                }) { Text("통계 새로고침") }
                Button(onClick = openTierList) { Text("티어 리스트") }
            }
            Note("점수는 Firestone이 공개하는 투기장 통계(카드를 뽑은 게임의 승률)를 바탕으로 계산합니다.")
        }

        Section("4. 설정") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("게임 언어", modifier = Modifier.weight(1f))
                listOf("koKR" to "한국어", "enUS" to "English").forEach { (loc, label) ->
                    FilterChip(
                        selected = settings.gameLocale == loc,
                        onClick = { AppGraph.settings.update { it.copy(gameLocale = loc) } },
                        label = { Text(label, fontSize = 12.sp) },
                    )
                }
            }
            ToggleRow("드래프트 화면 자동 인식", settings.autoScan) { v -> AppGraph.settings.update { it.copy(autoScan = v) } }
            ToggleRow("게임 시작 시 트래커 자동 펼치기", settings.autoOpenTracker) { v -> AppGraph.settings.update { it.copy(autoOpenTracker = v) } }
            ToggleRow("카드 이미지 표시", settings.showCardArt) { v -> AppGraph.settings.update { it.copy(showCardArt = v) } }
            SliderRow("인식 간격 ${"%.1f".format(settings.scanIntervalMs / 1000f)}초", settings.scanIntervalMs / 1000f, 1f..6f) { v ->
                AppGraph.settings.update { it.copy(scanIntervalMs = (v * 1000).toLong()) }
            }
            SliderRow("오버레이 불투명도 ${(settings.overlayAlpha * 100).toInt()}%", settings.overlayAlpha, 0.3f..1f) { v ->
                AppGraph.settings.update { it.copy(overlayAlpha = v) }
            }
            SliderRow("오버레이 크기 ${(settings.overlayScale * 100).toInt()}%", settings.overlayScale, 0.7f..1.5f) { v ->
                AppGraph.settings.update { it.copy(overlayScale = v) }
            }
        }

        Section("5. 덱 코드 (일반 대전)") {
            Note("보통은 로그에서 덱을 자동으로 읽습니다. 안 될 때 하스스톤에서 복사한 덱 코드를 붙여 넣으세요.")
            var code by remember(settings.manualDeckCode) { mutableStateOf(settings.manualDeckCode.orEmpty()) }
            OutlinedTextField(
                value = code,
                onValueChange = { code = it },
                placeholder = { Text("AAECA...") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 4,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val extracted = Deckstring.extractCode(code)
                    val valid = extracted != null && runCatching { Deckstring.decode(extracted) }.isSuccess
                    if (valid) {
                        AppGraph.settings.update { it.copy(manualDeckCode = extracted) }
                        message = "덱 코드를 저장했습니다."
                    } else {
                        message = "올바른 덱 코드가 아닙니다."
                    }
                }) { Text("저장") }
                OutlinedButton(onClick = {
                    AppGraph.settings.update { it.copy(manualDeckCode = null) }
                    code = ""
                }) { Text("지우기") }
            }
        }
        Spacer(Modifier.height(24.dp))
        Note("카드 데이터: HearthstoneJSON · 투기장 통계: Firestone · 이 앱은 Blizzard와 관련이 없습니다.")
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f), fontSize = 14.sp)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column {
        Text(label, fontSize = 13.sp, color = Color.Unspecified)
        Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range)
    }
}

private fun logAccessLabel(mode: LogAccess) = when (mode) {
    LogAccess.AUTO -> "자동"
    LogAccess.SHIZUKU -> "Shizuku"
    LogAccess.DIRECT -> "직접"
    LogAccess.OFF -> "끄기"
}

private fun isInstalled(context: android.content.Context, pkg: String): Boolean = try {
    context.packageManager.getPackageInfo(pkg, 0)
    true
} catch (e: PackageManager.NameNotFoundException) {
    false
}

private fun launchApp(context: android.content.Context, pkg: String) {
    context.packageManager.getLaunchIntentForPackage(pkg)?.let { context.startActivity(it) }
}

private fun openShizukuDownload(context: android.content.Context) {
    val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${ShizukuAccess.SHIZUKU_PACKAGE}"))
    try {
        context.startActivity(market)
    } catch (e: ActivityNotFoundException) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")))
    }
}
