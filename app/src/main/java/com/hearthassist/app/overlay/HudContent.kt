package com.hearthassist.app.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hearthassist.app.AppGraph
import com.hearthassist.app.capture.ScanResult
import com.hearthassist.app.engine.DraftInfo
import com.hearthassist.app.logs.LogState
import com.hearthassist.app.logs.LogStatus
import com.hearthassist.app.settings.SettingsState
import com.hearthassist.app.ui.CardRow
import com.hearthassist.app.ui.HaColors
import com.hearthassist.app.ui.ManaGem
import com.hearthassist.app.ui.TierChip
import com.hearthassist.app.ui.pct
import com.hearthassist.app.ui.scoreText
import com.hearthassist.core.cards.HeroClass
import com.hearthassist.core.game.TrackerSnapshot

@Composable
fun HudContent(ui: OverlayUiState, actions: HudActions, drag: WindowDragController) {
    val settings by AppGraph.settings.state.collectAsState()
    val expanded by ui.expanded.collectAsState()
    val logStatus by AppGraph.logs.status.collectAsState()
    val scale = settings.overlayScale

    Box(modifier = Modifier.alpha(settings.overlayAlpha.coerceIn(0.3f, 1f))) {
        if (!expanded) {
            Bubble(scale, logStatus, drag) { actions.setExpanded(true) }
        } else {
            Column(
                modifier = Modifier
                    .width((236 * scale).dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(HaColors.Surface.copy(alpha = 0.94f))
                    .border(1.dp, HaColors.Gold.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                    .padding(6.dp),
            ) {
                Header(scale, logStatus, drag, actions)
                Tabs(ui, actions, scale)
                Spacer(Modifier.height(4.dp))
                val tab by ui.tab.collectAsState()
                when (tab) {
                    HudTab.DECK -> DeckTab(settings, logStatus, scale)
                    HudTab.OPPONENT -> OpponentTab(settings, scale)
                    HudTab.ARENA -> ArenaTab(ui, actions, settings, scale)
                }
            }
        }
    }
}

@Composable
private fun Bubble(scale: Float, status: LogStatus, drag: WindowDragController, onTap: () -> Unit) {
    Box(
        modifier = Modifier
            .size((46 * scale).dp)
            .clip(CircleShape)
            .background(HaColors.Surface)
            .border(2.dp, HaColors.Gold, CircleShape)
            .windowDrag(drag, onTap),
        contentAlignment = Alignment.Center,
    ) {
        Text("HA", color = HaColors.Gold, fontWeight = FontWeight.Black, fontSize = (15 * scale).sp)
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .size(8.dp)
                .clip(CircleShape)
                .background(statusColor(status)),
        )
    }
}

private fun statusColor(status: LogStatus): Color = when (status.state) {
    LogState.WATCHING -> HaColors.Good
    LogState.CONNECTING -> HaColors.Gold
    else -> HaColors.Bad
}

@Composable
private fun Header(scale: Float, status: LogStatus, drag: WindowDragController, actions: HudActions) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .windowDrag(drag)
                .padding(vertical = 4.dp),
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(statusColor(status)),
            )
            Spacer(Modifier.width(6.dp))
            Text("Hearth Assist", color = HaColors.Gold, fontWeight = FontWeight.Bold, fontSize = (13 * scale).sp)
        }
        SmallButton("—", scale) { actions.setExpanded(false) }
        Spacer(Modifier.width(4.dp))
        SmallButton("✕", scale) { actions.stopService() }
    }
}

@Composable
fun SmallButton(text: String, scale: Float = 1f, color: Color = HaColors.SurfaceHigh, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color)
            .clickable(onClick = onClick)
            .padding(horizontal = (8 * scale).dp, vertical = (4 * scale).dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = HaColors.Text, fontSize = (12 * scale).sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Tabs(ui: OverlayUiState, actions: HudActions, scale: Float) {
    val tab by ui.tab.collectAsState()
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        HudTab.entries.forEach { t ->
            val selected = t == tab
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (selected) HaColors.Gold else HaColors.SurfaceHigh)
                    .clickable { actions.selectTab(t) }
                    .padding(vertical = (4 * scale).dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    t.label,
                    color = if (selected) Color.Black else HaColors.Text,
                    fontSize = (12 * scale).sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun Counts(left: String, right: String, scale: Float) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(left, color = HaColors.TextDim, fontSize = (11 * scale).sp, modifier = Modifier.weight(1f))
        Text(right, color = HaColors.TextDim, fontSize = (11 * scale).sp)
    }
}

@Composable
private fun DeckTab(settings: SettingsState, status: LogStatus, scale: Float) {
    val snap by AppGraph.engine.tracker.collectAsState()
    if (snap.gameNumber == 0) {
        Hint(
            if (status.state == LogState.WATCHING) "게임이 시작되면 덱 트래커가 표시됩니다."
            else "로그를 읽을 수 없습니다 (${status.state.label}).\n앱에서 Shizuku 연결과 log.config 설정을 확인하세요.",
            scale,
        )
        return
    }
    Counts("덱 ${snap.playerDeckCount}장 · 손 ${snap.playerHandCount}장", resultLabel(snap), scale)
    snap.deckName?.let {
        Text(it, color = HaColors.Gold, fontSize = (11 * scale).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (snap.hasDecklist) {
        val deckCount = snap.playerDeckCount.coerceAtLeast(1)
        CardList(scale) {
            items(snap.playerRemaining, key = { it.cardId + it.created }) { c ->
                val chance = if (c.count > 0) " ${(c.count * 100 / deckCount)}%" else ""
                CardRow(c, trailing = "${c.count}$chance", showArt = settings.showCardArt, dim = c.count == 0, scale = scale)
            }
        }
        if (snap.remainingUnknown > 0) {
            Hint("알 수 없는 카드 ${snap.remainingUnknown}장", scale)
        }
    } else {
        Hint("덱 리스트를 모릅니다. 뽑은 카드만 표시합니다.\n(앱에서 덱 코드를 입력할 수 있어요)", scale)
        CardList(scale) {
            items(snap.playerDrawn, key = { it.cardId }) { c ->
                CardRow(c, trailing = "×${c.count}", showArt = settings.showCardArt, scale = scale)
            }
        }
    }
}

@Composable
private fun OpponentTab(settings: SettingsState, scale: Float) {
    val snap by AppGraph.engine.tracker.collectAsState()
    if (snap.gameNumber == 0) {
        Hint("게임 중이 아닙니다.", scale)
        return
    }
    Counts("덱 ${snap.opponentDeckCount}장 · 손 ${snap.opponentHandCount}장", "턴 ${snap.turn}", scale)
    if (snap.opponentPlayed.isEmpty() && snap.opponentCreated.isEmpty()) {
        Hint("상대가 아직 덱의 카드를 공개하지 않았습니다.", scale)
        return
    }
    CardList(scale) {
        items(snap.opponentPlayed, key = { "p" + it.cardId }) { c ->
            CardRow(c, trailing = "×${c.count}", showArt = settings.showCardArt, scale = scale)
        }
        if (snap.opponentCreated.isNotEmpty()) {
            item(key = "created-header") {
                Text("생성된 카드", color = HaColors.TextDim, fontSize = (10 * scale).sp, modifier = Modifier.padding(top = 4.dp))
            }
            items(snap.opponentCreated, key = { "c" + it.cardId }) { c ->
                CardRow(c.copy(created = true), trailing = "×${c.count}", showArt = settings.showCardArt, scale = scale)
            }
        }
    }
}

private fun resultLabel(snap: TrackerSnapshot): String = when {
    snap.inProgress -> "턴 ${snap.turn}"
    snap.result == "WON" -> "승리"
    snap.result == "LOST" || snap.result == "CONCEDED" -> "패배"
    snap.result == "TIED" -> "무승부"
    else -> "종료"
}

@Composable
private fun CardList(scale: Float, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = (330 * scale).dp), content = content)
}

@Composable
fun Hint(text: String, scale: Float) {
    Text(text, color = HaColors.TextDim, fontSize = (11 * scale).sp, modifier = Modifier.padding(vertical = 4.dp))
}

@Composable
private fun ArenaTab(ui: OverlayUiState, actions: HudActions, settings: SettingsState, scale: Float) {
    val draft by AppGraph.engine.draft.collectAsState()
    val capturing by ui.capturing.collectAsState()
    val scanning by ui.scanning.collectAsState()
    val scan by ui.scan.collectAsState()
    val message by ui.message.collectAsState()
    val statsStatus by AppGraph.stats.status.collectAsState()

    ClassPicker(settings.manualClass, draft, actions, scale)
    Spacer(Modifier.height(4.dp))
    if (!capturing) {
        SmallButton("화면 인식 시작", scale, HaColors.Mana) { actions.startCapture() }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            SmallButton(if (scanning) "인식 중…" else "지금 인식", scale, HaColors.Mana) { actions.scanNow() }
            SmallButton(if (settings.autoScan) "자동 ON" else "자동 OFF", scale) { actions.setAutoScan(!settings.autoScan) }
            SmallButton("중지", scale) { actions.stopCapture() }
        }
    }
    Spacer(Modifier.height(4.dp))
    SmallButton("카드 검색", scale) { actions.openSearch() }
    message?.let { Hint(it, scale) }
    ScanList(scan, scale)
    DraftSummary(draft, scale)
    if (statsStatus.error != null) {
        Hint("통계 오류: ${statsStatus.error}", scale)
    } else if (statsStatus.cards == 0) {
        Hint(if (statsStatus.loading) "투기장 통계 받는 중…" else "투기장 통계가 아직 없습니다.", scale)
    }
}

@Composable
private fun ClassPicker(manual: String?, draft: DraftInfo, actions: HudActions, scale: Float) {
    val detected = draft.heroClass
    Text(
        text = "직업: " + (HeroClass.fromCardClass(manual)?.koName ?: detected?.let { "${it.koName} (자동)" } ?: "전체"),
        color = HaColors.Text,
        fontSize = (12 * scale).sp,
    )
    LazyRow(horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.padding(top = 2.dp)) {
        item {
            Chip("자동", manual == null, scale) { actions.setManualClass(null) }
        }
        items(HeroClass.entries) { c ->
            Chip(c.koName, manual == c.cardClass, scale) { actions.setManualClass(c.cardClass) }
        }
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, scale: Float, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) HaColors.Gold else HaColors.SurfaceHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        Text(text, color = if (selected) Color.Black else HaColors.Text, fontSize = (10 * scale).sp)
    }
}

@Composable
private fun ScanList(scan: ScanResult?, scale: Float) {
    if (scan == null) return
    if (scan.cards.isEmpty()) {
        Hint("화면에서 카드를 찾지 못했습니다 (글자 ${scan.linesRead}줄).", scale)
        return
    }
    val best = scan.best
    Column(modifier = Modifier.padding(top = 4.dp)) {
        scan.cards.forEach { d ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 1.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (d === best) HaColors.Gold.copy(alpha = 0.25f) else HaColors.SurfaceHigh)
                    .padding(3.dp),
            ) {
                ManaGem(d.cost ?: 0, scale)
                TierChip(d.score?.tier ?: com.hearthassist.core.arena.Tier.UNKNOWN, scale)
                Text(d.name, color = HaColors.Text, fontSize = (12 * scale).sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    Text(scoreText(d.score), color = HaColors.Gold, fontSize = (12 * scale).sp, fontWeight = FontWeight.Bold)
                    Text("픽 ${pct(d.score?.pickRate)}", color = HaColors.TextDim, fontSize = (9 * scale).sp)
                }
            }
        }
    }
}

@Composable
private fun DraftSummary(draft: DraftInfo, scale: Float) {
    if (draft.pickCount == 0) return
    Column(modifier = Modifier.padding(top = 6.dp)) {
        Text("드래프트 ${draft.pickCount}/30", color = HaColors.TextDim, fontSize = (11 * scale).sp)
        val max = (draft.curve.maxOrNull() ?: 1).coerceAtLeast(1)
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier.height((44 * scale).dp).padding(top = 2.dp),
        ) {
            draft.curve.forEachIndexed { cost, n ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width((22 * scale).dp)) {
                    Box(
                        Modifier
                            .width((14 * scale).dp)
                            .height((28 * scale * n / max).dp.coerceAtLeast(1.dp))
                            .background(HaColors.Mana),
                    )
                    Text(if (cost == 7) "7+" else cost.toString(), color = HaColors.TextDim, fontSize = (9 * scale).sp)
                }
            }
        }
    }
}
