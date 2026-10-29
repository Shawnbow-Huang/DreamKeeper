package com.dreamkeeper.data.db

import androidx.room.withTransaction
import java.time.LocalDate
import java.time.YearMonth

/**
 * 演示用种子数据。
 *
 * 幂等：库里已有任何一条梦就整体跳过（`dao.count() == 0` 才写）。
 * 门闩：调用点用 `BuildConfig.SEED_DEMO_DATA` 包住（见 Task 5 的 DreamKeeperApp），
 *       把 buildConfigField 改成 "false" 就整体消失，不需要删代码。
 * 定位：这是"看得见的东西"，不是测试夹具——测试仍自己造数据，不依赖种子。
 *
 * 日期一律按**当前月**算（D47）：写死月份的话，11 月 1 号起当月 0 个红点——
 * 「共 4 天有梦」变成「共 0 天有梦」，Task 7 的列表、Task 8 的详情截图会一起假绿
 * （界面全正常，只是空，最难查）。四条固定落在 1 / 8 / 15 / 22 号，
 * 这样「当月正好 4 天有梦」在任何月份都成立。
 */
object SeedData {

    const val DREAM_ELEVATOR = "seed-dream-1"
    const val DREAM_CLASSROOM = "seed-dream-2"
    const val DREAM_OLD_HOUSE = "seed-dream-3"
    const val DREAM_STAIRS = "seed-dream-4"

    /**
     * 4 个跨 kind 的种子标签，id 全部写死（用户自己改过名字也不会在重装后变回种子名——
     * 幂等靠 `count() != 0` 整体跳过，不靠 id 覆盖）。
     *
     * 「电梯」与「升降机」是**故意的同义词**：标签页的合并功能需要一对真实可并的标签，
     * 而且它们同时挂在 dream-1 上——正好逼出 `UPDATE OR REPLACE` 那条路径
     * （同一篇梦两个标签并存时普通 UPDATE 会撞主键）。
     */
    const val TAG_OBJECT_ELEVATOR = "seed-tag-object-elevator"
    const val TAG_OBJECT_LIFT = "seed-tag-object-lift"
    const val TAG_EMOTION_FALL = "seed-tag-emotion-fall"
    const val TAG_PLACE_CLASSROOM = "seed-tag-place-classroom"
    const val MEDIA_VIDEO_CLASSROOM = "seed-media-1"

    /**
     * 首次安装灌 4 条示例梦。
     *
     * 整个 `count()` 判断与四条写入必须在**同一事务**里：分开写的话，首启写到一半被杀
     * 会留下永久半套种子——`count` 已经非 0，剩下几条永远不补，用户看到的是残缺数据。
     */
    suspend fun seedIfEmpty(db: DreamDatabase) {
        db.withTransaction {
            val dreams = db.dreamDao()
            if (dreams.count() != 0) return@withTransaction   // 幂等：只写一次

            // 日期按当月算，1 / 8 / 15 / 22 号各自一条（D47）
            val month = YearMonth.now()
            val day1 = month.atDay(1)
            val day8 = month.atDay(8)
            val day15 = month.atDay(15)
            val day22 = month.atDay(22)

            // 1) 电梯：三个标签。两个同义（「电梯」/「升降机」）同时挂在这一条上，
            //    标签页合并时可以拿它演示；另一个是情绪，evidenceText 非空（设计 §4 要求标签有出处）
            dreams.upsert(dream(DREAM_ELEVATOR, day1, "电梯",
                "我在电梯里，电梯一直往上，按不到一楼。数字过了三十还在涨。"))
            db.tagDao().upsert(TagEntity(TAG_OBJECT_ELEVATOR, "电梯", TagKind.OBJECT,
                TagSource.AUTO, "我在电梯里"))
            db.tagDao().upsert(TagEntity(TAG_OBJECT_LIFT, "升降机", TagKind.OBJECT,
                TagSource.USER, "电梯一直往上"))
            db.tagDao().upsert(TagEntity(TAG_EMOTION_FALL, "下坠的预感", TagKind.EMOTION,
                TagSource.AUTO, "电梯一直往上，按不到一楼"))
            db.dreamTagDao().upsertAll(listOf(
                DreamTagEntity(DREAM_ELEVATOR, TAG_OBJECT_ELEVATOR),
                DreamTagEntity(DREAM_ELEVATOR, TAG_OBJECT_LIFT),
                DreamTagEntity(DREAM_ELEVATOR, TAG_EMOTION_FALL),
            ))

            // 2) 教室：带一个 VIDEO 素材占位（设计 §12「梦 → 视频」的预留口子）+ 一个 PLACE 标签
            dreams.upsert(dream(DREAM_CLASSROOM, day8, "教室",
                "教室最后一排，黑板上的字在动，像水。同桌递给我一张纸，上面只写了我的名字。"))
            db.tagDao().upsert(TagEntity(TAG_PLACE_CLASSROOM, "教室", TagKind.PLACE,
                TagSource.AUTO, "教室最后一排"))
            db.dreamTagDao().upsertAll(listOf(DreamTagEntity(DREAM_CLASSROOM, TAG_PLACE_CLASSROOM)))
            db.mediaAssetDao().upsert(
                MediaAssetEntity(MEDIA_VIDEO_CLASSROOM, DREAM_CLASSROOM, MediaType.VIDEO,
                    path = null, prompt = null, model = null, status = "PENDING", createdAt = 0L)
            )

            // 3) 老房子：已有小说化稿，验证「小说化·补写N处」tab 非空时的排版
            dreams.upsert(dream(DREAM_OLD_HOUSE, day15, "老房子",
                "老房子，走廊很长，尽头有光。我妈在厨房叫我，声音从楼上传来。").copy(
                novelText = "老房子的走廊比记忆里长。尽头有一扇窗，光从那里斜进来，把地板切成两半。厨房里有人在叫我的名字——可那个声音是从楼上来的。",
                novelVersion = 1,
                novelChangeLog = renderChangeLog(
                    listOf(
                        ChangeLogEntry("SCENE", "补写", "走廊的光"),
                        ChangeLogEntry("SCENE", "补写", "声音的方向"),
                    )
                ),
            ))

            // 4) 楼梯：无标题、正文极短，验证列表里的短条目排版
            dreams.upsert(dream(DREAM_STAIRS, day22, null, "一直在下楼梯，每一层都一样。"))
        }
    }

    /**
     * 种子里「改动清单」的结构化形状：一条 = 类型 + 动作 + 目标。
     * 只活在播种代码里——`Dream.novelChangeLog` 是 `String?`、列类型是 TEXT，是 Task 5 定下的契约；
     * 为一条种子去改 domain 类型，会连带改 Task 8 的 `novelChangeCount` 与 `DreamRepositoryTest` 的两处断言（D48）。
     */
    private data class ChangeLogEntry(val type: String, val action: String, val target: String)

    /** 压成 Plan 1 的存储契约：「补写 N 处：a、b」（D41 要求「补写 N 处」字样必须保留） */
    private fun renderChangeLog(entries: List<ChangeLogEntry>): String =
        "补写 ${entries.size} 处：" + entries.joinToString("、") { it.target }

    private fun dream(id: String, date: LocalDate, title: String?, text: String) = DreamEntity(
        id = id,
        ownerId = "local",
        dreamDate = date,
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
