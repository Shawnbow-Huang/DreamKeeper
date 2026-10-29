package com.dreamkeeper.data.db

import java.time.LocalDate

/**
 * 演示用种子数据。
 *
 * 幂等：库里已有任何一条梦就整体跳过（`dao.count() == 0` 才写）。
 * 门闩：调用点用 `BuildConfig.SEED_DEMO_DATA` 包住（见 Task 5 的 DreamKeeperApp），
 *       把 buildConfigField 改成 "false" 就整体消失，不需要删代码。
 * 定位：这是"看得见的东西"，不是测试夹具——测试仍自己造数据，不依赖种子。
 */
object SeedData {

    const val DREAM_ELEVATOR = "seed-dream-1"
    const val DREAM_CLASSROOM = "seed-dream-2"
    const val DREAM_OLD_HOUSE = "seed-dream-3"
    const val DREAM_STAIRS = "seed-dream-4"
    const val TAG_EMOTION_FALL = "seed-tag-emotion-fall"
    const val MEDIA_VIDEO_CLASSROOM = "seed-media-1"

    suspend fun seedIfEmpty(db: DreamDatabase) {
        val dreams = db.dreamDao()
        if (dreams.count() != 0) return   // 幂等：只写一次

        // 1) 电梯：带一个 EMOTION 标签，evidenceText 非空（设计 §4 要求标签有出处）
        dreams.upsert(dream(DREAM_ELEVATOR, "2026-10-20", "电梯",
            "我在电梯里，电梯一直往上，按不到一楼。数字过了三十还在涨。"))
        db.tagDao().upsert(TagEntity(TAG_EMOTION_FALL, "下坠的预感", TagKind.EMOTION,
            TagSource.AUTO, "电梯一直往上，按不到一楼"))
        db.dreamTagDao().upsertAll(listOf(DreamTagEntity(DREAM_ELEVATOR, TAG_EMOTION_FALL)))

        // 2) 教室：带一个 VIDEO 素材占位（设计 §12「梦 → 视频」的预留口子）
        dreams.upsert(dream(DREAM_CLASSROOM, "2026-10-24", "教室",
            "教室最后一排，黑板上的字在动，像水。同桌递给我一张纸，上面只写了我的名字。"))
        db.mediaAssetDao().upsert(
            MediaAssetEntity(MEDIA_VIDEO_CLASSROOM, DREAM_CLASSROOM, MediaType.VIDEO,
                path = null, prompt = null, model = null, status = "PENDING", createdAt = 0L)
        )

        // 3) 老房子：已有小说化稿，验证「小说化·补写N处」tab 非空时的排版
        dreams.upsert(dream(DREAM_OLD_HOUSE, "2026-10-27", "老房子",
            "老房子，走廊很长，尽头有光。我妈在厨房叫我，声音从楼上传来。").copy(
            novelText = "老房子的走廊比记忆里长。尽头有一扇窗，光从那里斜进来，把地板切成两半。厨房里有人在叫我的名字——可那个声音是从楼上来的。",
            novelVersion = 1,
            novelChangeLog = "补写 2 处：走廊的光、声音的方向",
        ))

        // 4) 楼梯：无标题、正文极短，验证列表里的短条目排版
        dreams.upsert(dream(DREAM_STAIRS, "2026-10-28", null, "一直在下楼梯，每一层都一样。"))
    }

    private fun dream(id: String, date: String, title: String?, text: String) = DreamEntity(
        id = id,
        ownerId = "local",
        dreamDate = LocalDate.parse(date),
        createdAt = 0L,
        title = title,
        rawTranscript = text,
        faithfulText = text,
        novelText = null,
        novelVersion = 0,
        novelChangeLog = null,
        mood = null,
        isDraft = false,
        deletedAt = null,
        updatedAt = 0L,
    )
}
