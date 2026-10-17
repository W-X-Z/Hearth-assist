package com.hearthassist.app.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hearthassist.app.AppGraph
import com.hearthassist.app.ui.HaColors
import com.hearthassist.app.ui.ManaGem
import com.hearthassist.app.ui.TierChip
import com.hearthassist.app.ui.pct
import com.hearthassist.app.ui.scoreText
import com.hearthassist.core.arena.ArenaScorer
import com.hearthassist.core.arena.CardScore
import com.hearthassist.core.cards.Card
import com.hearthassist.core.cards.CardDb
import com.hearthassist.core.text.TextMatch

data class SearchHit(val card: Card, val score: CardScore?)

/** Name search over draftable cards, best score first. Shared by the overlay and the app. */
fun searchCards(db: CardDb, scorer: ArenaScorer?, locale: String, query: String, limit: Int = 40): List<SearchHit> {
    val q = TextMatch.normalize(query)
    if (q.isEmpty()) return emptyList()
    val pool = scorer?.cardIds?.takeIf { it.isNotEmpty() }
    return db.draftable.asSequence()
        .filter { pool == null || it.id in pool }
        .filter { TextMatch.normalize(it.name(locale)).contains(q) || TextMatch.normalize(it.name("enUS")).contains(q) }
        .distinctBy { it.name(locale) }
        .map { SearchHit(it, scorer?.score(it.id)) }
        .sortedByDescending { it.score?.score ?: -1.0 }
        .take(limit)
        .toList()
}

@Composable
fun SearchContent(actions: HudActions) {
    val settings by AppGraph.settings.state.collectAsState()
    val db by AppGraph.cards.db.collectAsState()
    val version by AppGraph.stats.version.collectAsState()
    val draft by AppGraph.engine.draft.collectAsState()
    var query by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val heroClass = AppGraph.effectiveClass()
    val scorer = remember(version, heroClass, draft.heroClass, settings.manualClass) { AppGraph.stats.cached(heroClass) }
    LaunchedEffect(heroClass) { AppGraph.stats.scorer(heroClass) }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    val hits = remember(query, db, scorer, settings.gameLocale) { searchCards(db, scorer, settings.gameLocale, query) }

    Column(
        modifier = Modifier
            .width(320.dp)
            // No Activity here, so handle Back ourselves (the IME consumes the first press).
            .onPreviewKeyEvent { e ->
                if (e.key == Key.Back && e.type == KeyEventType.KeyUp) {
                    actions.closeSearch()
                    true
                } else {
                    false
                }
            }
            .clip(RoundedCornerShape(12.dp))
            .background(HaColors.Surface)
            .border(1.dp, HaColors.Gold.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "카드 점수 검색 · ${heroClass?.koName ?: "전체"}",
                color = HaColors.Gold, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f),
            )
            SmallButton("닫기") { actions.closeSearch() }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text("카드 이름 (예: 화염구)") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = HaColors.Text,
                unfocusedTextColor = HaColors.Text,
                focusedBorderColor = HaColors.Gold,
                cursorColor = HaColors.Gold,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
                .focusRequester(focus),
        )
        if (scorer == null) Hint("투기장 통계를 불러오는 중이거나 받을 수 없습니다.", 1f)
        LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
            items(hits, key = { it.card.id }) { hit -> SearchRow(hit, settings.gameLocale) }
        }
    }
}

@Composable
fun SearchRow(hit: SearchHit, locale: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(HaColors.SurfaceHigh)
            .padding(4.dp),
    ) {
        ManaGem(hit.card.cost ?: 0)
        TierChip(hit.score?.tier ?: com.hearthassist.core.arena.Tier.UNKNOWN)
        Text(hit.card.name(locale), color = HaColors.Text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            Text(scoreText(hit.score), color = HaColors.Gold, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text(
                "드로우 승률 ${pct(hit.score?.drawnWinrate, 1)} · 픽 ${pct(hit.score?.pickRate)}",
                color = HaColors.TextDim, fontSize = 9.sp,
            )
        }
    }
}
