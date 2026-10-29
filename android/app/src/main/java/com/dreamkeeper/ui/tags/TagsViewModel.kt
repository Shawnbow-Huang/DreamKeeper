package com.dreamkeeper.ui.tags

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dreamkeeper.data.DreamRepository
import com.dreamkeeper.data.db.TagDao
import com.dreamkeeper.data.db.TagEntity
import com.dreamkeeper.data.db.TagKind
import com.dreamkeeper.domain.Tag
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 一个分组标题 + 它底下的标签。顺序由 [TAG_GROUPS] 定，**不由 SQL 定**（brief §0.6） */
data class TagGroup(val kind: String, val title: String, val tags: List<Tag>)

/** 重命名撞上了已有名字：等用户决定要不要把 [from] 并进 [into]（D75） */
data class TagNameConflict(val from: Tag, val into: Tag, val requestedName: String)

data class TagsUiState(
    val groups: List<TagGroup> = emptyList(),
    /** 区分「还在读库」与「库里真的没有标签」：两者都是空表，但空态文案不该闪 */
    val loaded: Boolean = false,
    val conflict: TagNameConflict? = null,
)

/**
 * 人物 → 地点 → 物品 → 动作 → 情绪 → 其他。
 *
 * **这就是排序的唯一定义**。不许改成 `ORDER BY kind`：`kind` 存的是 `PERSON/PLACE/...`
 * 这样的字符串，字典序会从「动作（ACTION）」开头，设计 §6 要的是「人物」在最前。
 */
val TAG_GROUPS: List<Pair<String, String>> = listOf(
    TagKind.PERSON to "人物",
    TagKind.PLACE to "地点",
    TagKind.OBJECT to "物品",
    TagKind.ACTION to "动作",
    TagKind.EMOTION to "情绪",
    TagKind.OTHER to "其他",
)

/**
 * 按 [TAG_GROUPS] 的顺序分组；空组不画标题。
 *
 * 未知 `kind`（老数据 / 以后新加的类别）归到「其他」而不是丢掉——
 * 界面少一个标签比多一个空组难查得多。
 */
fun groupTags(tags: List<Tag>): List<TagGroup> {
    val known = TAG_GROUPS.mapTo(HashSet()) { it.first }
    val byKind = tags.groupBy { if (it.kind in known) it.kind else TagKind.OTHER }
    return TAG_GROUPS.mapNotNull { (kind, title) -> byKind[kind]?.let { TagGroup(kind, title, it) } }
}

/**
 * 标签管理页（设计 §12 / brief §0.6）。
 *
 * 三个动作必须分清「改哪里」：
 * - 重命名只改 `tags.name`；撞名时**先查再问**，绝不 `upsert`（REPLACE 会删行留孤儿，D75）。
 * - 合并要动关联表，且必须在事务里（[DreamRepository.mergeTags]）。
 * - 删除只断关联 + 删标签行，**不动梦**（`TagDao.delete` 内部是事务）。
 */
class TagsViewModel(
    private val repo: DreamRepository,
    private val tagDao: TagDao,
) : ViewModel() {

    private val conflict = MutableStateFlow<TagNameConflict?>(null)

    val state: StateFlow<TagsUiState> =
        combine(repo.observeTags(), conflict) { tags, pending ->
            TagsUiState(groups = groupTags(tags), loaded = true, conflict = pending)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TagsUiState())

    /** 把 [fromId] 的关联并到 [intoId]，再删掉源标签。同 id 早退（仓库层也守一次） */
    fun merge(fromId: String, intoId: String) {
        if (fromId == intoId) return
        viewModelScope.launch { repo.mergeTags(fromId, intoId) }
    }

    /**
     * 改名。三种情况：空名/没变 = 什么都不做；新名字没被占 = 直接改；被占 = 弹二次确认。
     *
     * 撞名分支**不许**降级成 `upsert(tag.copy(name = new))`：`tags.name` 有唯一索引，
     * REPLACE 会删掉目标那一行、把它的关联留成孤儿（D75）。
     */
    fun rename(tagId: String, rawName: String) {
        val name = rawName.trim()
        if (name.isEmpty()) return
        viewModelScope.launch {
            val current = tagDao.findById(tagId) ?: return@launch
            if (current.name == name) return@launch
            val existing = tagDao.findByName(name)
            when {
                existing == null -> tagDao.rename(tagId, name)
                existing.id == tagId -> Unit          // 理论上到不了，保险
                else -> conflict.value = TagNameConflict(
                    from = current.toDomain(),
                    into = existing.toDomain(),
                    requestedName = name,
                )
            }
        }
    }

    /** 「已有同名标签，要合并吗？」→「合并」：把源标签的关联并进同名那个，源标签消失 */
    fun confirmConflict() {
        val pending = conflict.value ?: return
        conflict.value = null
        viewModelScope.launch { repo.mergeTags(pending.from.id, pending.into.id) }
    }

    /** 「取消」：什么都不改，把提示收回去 */
    fun dismissConflict() {
        conflict.value = null
    }

    /** 删标签不删梦：只断标注（`TagDao.delete` 内部已包事务） */
    fun delete(tagId: String) {
        viewModelScope.launch { tagDao.delete(tagId) }
    }
}

private fun TagEntity.toDomain() = Tag(id = id, name = name, kind = kind, source = source, evidenceText = evidenceText)
