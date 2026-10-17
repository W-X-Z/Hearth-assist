package com.hearthassist.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.hearthassist.core.arena.CardScore
import com.hearthassist.core.arena.Tier
import com.hearthassist.core.game.TrackedCard
import java.util.Locale

object HaColors {
    val Background = Color(0xFF14100C)
    val Surface = Color(0xFF231A12)
    val SurfaceHigh = Color(0xFF33261A)
    val Gold = Color(0xFFE9C46A)
    val Text = Color(0xFFF3EDE4)
    val TextDim = Color(0xFFB7A994)
    val Mana = Color(0xFF2F6FDB)
    val Good = Color(0xFF6BCB77)
    val Bad = Color(0xFFE76F51)

    fun tier(t: Tier): Color = when (t) {
        Tier.S -> Color(0xFFFF8C1A)
        Tier.A -> Color(0xFFB567F2)
        Tier.B -> Color(0xFF3D9BE9)
        Tier.C -> Color(0xFF52B36B)
        Tier.D -> Color(0xFF8C8C8C)
        Tier.UNKNOWN -> Color(0xFF5A5A5A)
    }

    fun rarity(r: String?): Color = when (r) {
        "LEGENDARY" -> Color(0xFFF39C12)
        "EPIC" -> Color(0xFFA35BD6)
        "RARE" -> Color(0xFF4A90E2)
        else -> Color(0xFFDDDDDD)
    }
}

fun tileUrl(cardId: String) = "https://art.hearthstonejson.com/v1/tiles/$cardId.png"

fun pct(v: Double?, digits: Int = 0): String =
    if (v == null) "-" else String.format(Locale.US, "%.${digits}f%%", v * 100)

fun scoreText(s: CardScore?): String =
    s?.score?.let { String.format(Locale.US, "%.1f", it) } ?: "-"

@Composable
fun ManaGem(cost: Int, scale: Float = 1f) {
    Box(
        modifier = Modifier
            .size((20 * scale).dp)
            .clip(CircleShape)
            .background(HaColors.Mana),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = cost.toString(),
            color = Color.White,
            fontSize = (11 * scale).sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** One deck-tracker style row: mana, name over the card's tile art, count on the right. */
@Composable
fun CardRow(
    card: TrackedCard,
    trailing: String,
    showArt: Boolean,
    dim: Boolean = false,
    scale: Float = 1f,
    trailingColor: Color = HaColors.Gold,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height((26 * scale).dp)
            .padding(vertical = 1.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(HaColors.SurfaceHigh)
            .alpha(if (dim) 0.4f else 1f),
    ) {
        if (showArt) {
            AsyncImage(
                model = tileUrl(card.cardId),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxHeight()
                    .width((110 * scale).dp)
                    .align(Alignment.CenterEnd)
                    .alpha(0.75f),
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            0.0f to HaColors.SurfaceHigh,
                            0.45f to HaColors.SurfaceHigh,
                            1.0f to Color.Transparent,
                        ),
                    ),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ManaGem(card.cost, scale)
            Text(
                text = (if (card.created) "✦ " else "") + card.name,
                color = HaColors.rarity(card.rarity).takeIf { card.rarity == "LEGENDARY" } ?: HaColors.Text,
                fontSize = (12 * scale).sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (trailing.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color(0xCC000000))
                        .padding(horizontal = 4.dp),
                ) {
                    Text(trailing, color = trailingColor, fontSize = (11 * scale).sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun TierChip(tier: Tier, scale: Float = 1f) {
    Box(
        modifier = Modifier
            .size((22 * scale).dp)
            .clip(RoundedCornerShape(4.dp))
            .background(HaColors.tier(tier)),
        contentAlignment = Alignment.Center,
    ) {
        Text(tier.label, color = Color.White, fontWeight = FontWeight.Black, fontSize = (13 * scale).sp)
    }
}

/** Big score badge drawn above an offered draft card. */
@Composable
fun ScoreBadge(score: CardScore?, best: Boolean, scale: Float = 1f) {
    val tier = score?.tier ?: Tier.UNKNOWN
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xE6140E08))
            .border(if (best) 3.dp else 1.5.dp, if (best) HaColors.Gold else HaColors.tier(tier), RoundedCornerShape(10.dp))
            .padding(horizontal = (10 * scale).dp, vertical = (4 * scale).dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TierChip(tier, scale)
            Text(scoreText(score), color = HaColors.Text, fontSize = (20 * scale).sp, fontWeight = FontWeight.Black)
        }
        Text(
            text = "픽률 ${pct(score?.pickRate)} · 6승+ ${pct(score?.pickRateHighWins)}",
            color = HaColors.TextDim,
            fontSize = (9 * scale).sp,
        )
        if (best) {
            Text("추천", color = HaColors.Gold, fontSize = (10 * scale).sp, fontWeight = FontWeight.Bold)
        }
    }
}
