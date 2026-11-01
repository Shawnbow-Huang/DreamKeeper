package com.dreamkeeper.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dreamkeeper.data.db.DreamDatabase
import com.dreamkeeper.data.db.DreamEntity
import com.dreamkeeper.data.db.TranscriptStatus
import com.dreamkeeper.llm.tags.TagExtractor
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/** 自动打标签的落盘：只替换 AUTO，同名的 USER 标签不动。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutoTagStoreTest {

    private lateinit var db: DreamDatabase
    private lateinit var store: GenerationStore

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, DreamDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = RoomGenerationStore(db)
        runBlocking {
            db.dreamDao().upsert(
                DreamEntity(
                    id = "d1",
                    dreamDate = LocalDate.of(2026, 11, 1),
                    createdAt = 1_793_500_000_000L,
                    title = null,
                    rawTranscript = "去澳门",
                    faithfulText = "去澳门",
                    novelText = null,
                    transcriptStatus = TranscriptStatus.OK,
                ),
            )
        }
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun first_run_writes_tags_and_links() = runBlocking {
        store.saveAutoTags(
            "d1",
            listOf(
                TagExtractor.Draft("澳门", "PLACE", "去澳门"),
                TagExtractor.Draft("飞船", "OBJECT", "去澳门"),
                TagExtractor.Draft("家人", "PERSON", "去澳门"),
            ),
        )
        val tags = db.dreamTagDao().listTagsForDream("d1")
        assertEquals(listOf("澳门", "飞船", "家人").sorted(), tags.map { it.name }.sorted())
        assertEquals(3, db.tagDao().count())
    }

    @Test
    fun second_run_replaces_auto_tags_only() = runBlocking {
        // 用户手动标签：名字不同，必须幸存。
        db.tagDao().upsert(com.dreamkeeper.data.db.TagEntity(id = "u1", name = "我选的", source = "USER"))
        db.dreamTagDao().upsertAll(listOf(com.dreamkeeper.data.db.DreamTagEntity("d1", "u1")))

        store.saveAutoTags("d1", listOf(TagExtractor.Draft("澳门", "PLACE", "去澳门")))
        store.saveAutoTags(
            "d1",
            listOf(
                TagExtractor.Draft("飞船", "OBJECT", "去澳门"),
                TagExtractor.Draft("家人", "PERSON", "去澳门"),
            ),
        )

        val names = db.dreamTagDao().listTagsForDream("d1").map { it.name }.sorted()
        assertEquals(listOf("家人", "我选的", "飞船").sorted(), names)
    }

    @Test
    fun delete_tag_link_hides_tag_but_keeps_row() = runBlocking {
        store.saveAutoTags(
            "d1",
            listOf(TagExtractor.Draft("澳门", "PLACE", "去澳门")),
        )
        val tag = db.dreamTagDao().listTagsForDream("d1").single()
        store.deleteTagLink("d1", tag.id)
        assertEquals(0, db.dreamTagDao().listTagsForDream("d1").size)
        // 全局 tags 行保留（别的梦可能还在用）。
        assertEquals(1, db.tagDao().count())
    }
}
