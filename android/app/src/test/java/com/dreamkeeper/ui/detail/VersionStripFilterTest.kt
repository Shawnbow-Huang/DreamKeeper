package com.dreamkeeper.ui.detail

import com.dreamkeeper.data.db.NovelVersionEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T6a / D332：版本线只出小说版本，整理稿（promptId="faithful"）不参与选版。
 */
class VersionStripFilterTest {

    private fun version(no: Int, promptId: String) = NovelVersionEntity(
        id = "v$no",
        dreamId = "dream-1",
        version = no,
        text = "text-$no",
        origin = "GENERATE",
        promptId = promptId,
        promptVersion = 2,
        promptHash = "h$no",
        model = "deepseek-flash",
        createdAt = no.toLong(),
    )

    @Test
    fun `faithful rows are hidden from the version strip`() {
        val all = listOf(version(3, "novel"), version(2, "faithful"), version(1, "novel_revise"))
        assertEquals(listOf(3, 1), visibleVersions(all).map { it.version })
    }

    @Test
    fun `novel and revise rows keep their order`() {
        val all = listOf(version(5, "novel"), version(4, "novel_revise"), version(3, "novel"))
        assertEquals(listOf(5, 4, 3), visibleVersions(all).map { it.version })
        assertEquals(emptyList<Int>(), visibleVersions(all.filter { it.promptId == "faithful" }).map { it.version })
    }
}
