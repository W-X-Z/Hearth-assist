@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.hearthassist.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hearthassist.app.AppGraph
import com.hearthassist.app.overlay.SearchHit
import com.hearthassist.app.overlay.SearchRow
import com.hearthassist.core.arena.ArenaScorer
import com.hearthassist.core.cards.HeroClass
import com.hearthassist.core.text.TextMatch

@Composable
fun TierListScreen(onBack: () -> Unit, contentPadding: PaddingValues) {
    val settings by AppGraph.settings.state.collectAsState()
    val db by AppGraph.cards.db.collectAsState()
    val version by AppGraph.stats.version.collectAsState()
    val status by AppGraph.stats.status.collectAsState()
    var heroClass by remember { mutableStateOf<HeroClass?>(null) }
    var query by remember { mutableStateOf("") }
    var scorer by remember { mutableStateOf<ArenaScorer?>(null) }

    LaunchedEffect(heroClass, version) {
        // Cheap when cached; loads class-specific numbers otherwise.
        scorer = AppGraph.stats.cached(heroClass)
        scorer = AppGraph.stats.scorer(heroClass) ?: scorer
    }

    val rows = remember(scorer, db, settings.gameLocale, query, heroClass) {
        val s = scorer ?: return@remember emptyList<SearchHit>()
        val q = TextMatch.normalize(query)
        s.ranking().asSequence()
            .mapNotNull { score -> db[score.cardId]?.let { SearchHit(it, score) } }
            .filter { it.card.collectible }
            .filter { heroClass == null || it.card.cardClass == heroClass?.cardClass || it.card.cardClass == "NEUTRAL" }
            .filter { q.isEmpty() || TextMatch.normalize(it.card.name(settings.gameLocale)).contains(q) }
            .distinctBy { it.card.name(settings.gameLocale) }
            .toList()
    }

    Column(modifier = Modifier.fillMaxSize().padding(contentPadding).padding(horizontal = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← 뒤로") }
            Text("투기장 카드 점수", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
        }
        Text(
            "점수 = 카드를 뽑은 게임의 승률(%), 표본이 적으면 평균 쪽으로 보정 · 출처: Firestone (${status.mode}, ${status.period})",
            color = HaColors.TextDim, fontSize = 11.sp,
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 6.dp)) {
            item { FilterChip(selected = heroClass == null, onClick = { heroClass = null }, label = { Text("전체") }) }
            items(HeroClass.entries) { c ->
                FilterChip(selected = heroClass == c, onClick = { heroClass = c }, label = { Text(c.koName) })
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text("카드 이름 검색") },
            modifier = Modifier.fillMaxWidth(),
        )
        if (scorer == null) {
            Text(
                if (status.loading) "통계를 받는 중…" else "통계가 없습니다. 홈 화면에서 '통계 새로고침'을 눌러 주세요. ${status.error ?: ""}",
                color = HaColors.TextDim, modifier = Modifier.padding(top = 12.dp),
            )
        }
        LazyColumn(modifier = Modifier.fillMaxSize().padding(top = 6.dp)) {
            itemsIndexed(rows, key = { _, hit -> hit.card.id }) { i, hit ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", color = HaColors.TextDim, fontSize = 11.sp, modifier = Modifier.width(30.dp))
                    Column(modifier = Modifier.weight(1f)) { SearchRow(hit, settings.gameLocale) }
                }
            }
        }
    }
}
