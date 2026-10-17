package com.hearthassist.core

import com.hearthassist.core.arena.DraftCardMatcher
import com.hearthassist.core.arena.OcrLine
import kotlin.test.Test
import kotlin.test.assertTrue

class MatcherPerfTest {
    @Test
    fun matchesQuicklyAgainstFullPool() {
        val t0 = System.nanoTime()
        val m = DraftCardMatcher(DraftCardMatcher.candidatesFor(TestCards.db.draftable, "koKR"))
        val t1 = System.nanoTime()
        val names = TestCards.db.draftable.shuffled(kotlin.random.Random(1)).take(60).map { it.name("koKR") }
        val lines = names.mapIndexed { i, n ->
            OcrLine("$n 피해를 3 줍니다 전투의 함성 무작위", 100 + (i % 6) * 300, 100 + (i / 6) * 90, 400 + (i % 6) * 300, 140 + (i / 6) * 90)
        }
        repeat(3) { m.match(lines, 2400, 1080) }
        val t2 = System.nanoTime()
        m.match(lines, 2400, 1080)
        val t3 = System.nanoTime()
        println("candidates=${TestCards.db.draftable.size} build=${(t1 - t0) / 1_000_000}ms match=${(t3 - t2) / 1_000_000}ms")
        assertTrue((t3 - t2) / 1_000_000 < 2000)
    }
}
