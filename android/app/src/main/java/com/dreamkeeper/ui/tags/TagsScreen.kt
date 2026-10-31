package com.dreamkeeper.ui.tags

import com.dreamkeeper.ui.theme.DreamColors
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.dreamkeeper.ui.common.glassSurface
import com.dreamkeeper.ui.theme.LocalDream
import androidx.compose.ui.graphics.Color
import com.dreamkeeper.ui.common.glassDialogModifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dreamkeeper.domain.Tag
import kotlinx.coroutines.launch

/** 胶囊圆角：999dp 即「全圆」，与搜索框、分段控件用的是同一种形状语言 */
private val CapsuleShape = RoundedCornerShape(999.dp)

/** 胶囊视觉高度。点区另撑到 48dp（见 [TagCapsule]），视觉上才不是一颗颗大药丸 */
private val CapsuleHeight = 36.dp

/**
 * 标签管理页（设计 §12，brief §0.6）。
 *
 * 三条硬规矩，改之前先看：
 * 1. **分组顺序由 [TAG_GROUPS] 定**，数据源 `TagDao.observeAll` 是 `ORDER BY kind, name`
 *    （字符串序从「动作」开头），所以界面必须自己按常量重排，不能直接照单渲染。
 * 2. 胶囊的**点区**是 48dp，视觉只有 36dp —— 直接给 36dp 高的可点区域，长按会点不中。
 * 3. 所有弹出层的底色都写死 `DreamColors.nightRaised`：M3 的菜单/对话框默认用 `surfaceContainerHigh`，
 *    深色主题下是一块偏紫的灰板，既跳戏又过不了近白闸门（D70）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagsScreen(vm: TagsViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // 一次只可能开一个菜单/弹层，所以都用「当前操作的是哪个标签」来记，不用给每个胶囊各存一份
    var menuTagId by remember { mutableStateOf<String?>(null) }
    var renameTag by remember { mutableStateOf<Tag?>(null) }
    var deleteTag by remember { mutableStateOf<Tag?>(null) }
    var mergeTag by remember { mutableStateOf<Tag?>(null) }

    Box(modifier = Modifier.fillMaxSize().background(DreamColors.night)) {
        Column(modifier = Modifier.fillMaxSize()) {
            TagsTopBar(onBack = onBack)

            when {
                // 还在读库：留白。此时 groups 也是空的，但**不能**画「还没有标签」——会闪一下
                !state.loaded -> Box(modifier = Modifier.weight(1f).fillMaxWidth())

                state.groups.isEmpty() -> EmptyTags(modifier = Modifier.weight(1f))

                else -> LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
                ) {
                    items(state.groups, key = { it.kind }) { group ->
                        GroupBlock(
                            group = group,
                            menuTagId = menuTagId,
                            onOpenMenu = { tag -> menuTagId = tag.id },
                            onDismissMenu = { menuTagId = null },
                            onTap = { scope.launch { snackbarHost.showSnackbar("按标签筛梦还没做，之后会有") } },
                            onRename = { tag -> menuTagId = null; renameTag = tag },
                            onMerge = { tag -> menuTagId = null; mergeTag = tag },
                            onDelete = { tag -> menuTagId = null; deleteTag = tag },
                        )
                    }
                }
            }
        }

        // 点胶囊只给一句人话（筛梦是之后的事）：静默无反应是最难查的「坏了」
        SnackbarHost(
            hostState = snackbarHost,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
        ) { data ->
            Snackbar(
                snackbarData = data,
                containerColor = DreamColors.nightRaised,
                contentColor = DreamColors.paper,
                actionColor = DreamColors.ember,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }

    renameTag?.let { tag ->
        RenameDialog(
            tag = tag,
            onDismiss = { renameTag = null },
            onConfirm = { newName ->
                renameTag = null
                // 撞名不在这里判断：库里有没有同名的只有 ViewModel 知道，
                // 它会把二次确认挂到 state.conflict 上，由下面的对话框接住
                vm.rename(tag.id, newName)
            },
        )
    }

    deleteTag?.let { tag ->
        ConfirmDialog(
            title = "删除「${tag.name}」",
            message = "删除标签不会删掉梦，只去掉标注。",
            confirmLabel = "删除",
            confirmColor = DreamColors.errorText,
            onDismiss = { deleteTag = null },
            onConfirm = {
                deleteTag = null
                vm.delete(tag.id)
            },
        )
    }

    mergeTag?.let { source ->
        val candidates = state.groups.flatMap { it.tags }.filter { it.id != source.id }
        MergeSheet(
            source = source,
            candidates = candidates,
            onDismiss = { mergeTag = null },
            onPick = { target ->
                mergeTag = null
                vm.merge(fromId = source.id, intoId = target.id)
            },
        )
    }

    state.conflict?.let { conflict ->
        ConfirmDialog(
            title = "已有同名标签，要合并吗？",
            message = "「${conflict.from.name}」会消失，它标注的梦都改挂到「${conflict.into.name}」上。",
            confirmLabel = "合并",
            confirmColor = DreamColors.ember,
            onDismiss = vm::dismissConflict,
            onConfirm = vm::confirmConflict,
        )
    }
}

/** 顶栏：「‹」48dp 点区 + 居中标题。右侧留一个等宽占位，标题才是真的在屏幕中间 */
@Composable
private fun TagsTopBar(onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .combinedClickable(onClick = onBack, onLongClick = null)
                .semantics { contentDescription = "返回" },
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "‹", style = MaterialTheme.typography.titleLarge, color = DreamColors.paper)
        }
        Text(
            text = "标签",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            color = DreamColors.paper,
            textAlign = TextAlign.Center,
        )
        Box(modifier = Modifier.size(48.dp))
    }
}

/** 一个分组：小标题（+ 条数）和它底下的胶囊。空组在 ViewModel 里就被滤掉了 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GroupBlock(
    group: TagGroup,
    menuTagId: String?,
    onOpenMenu: (Tag) -> Unit,
    onDismissMenu: () -> Unit,
    onTap: (Tag) -> Unit,
    onRename: (Tag) -> Unit,
    onMerge: (Tag) -> Unit,
    onDelete: (Tag) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = group.title, style = MaterialTheme.typography.titleMedium, color = DreamColors.inkDim)
            Text(
                text = group.tags.size.toString(),
                modifier = Modifier.padding(start = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = DreamColors.inkFaint,
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            group.tags.forEach { tag ->
                TagCapsule(
                    tag = tag,
                    menuOpen = menuTagId == tag.id,
                    onOpenMenu = { onOpenMenu(tag) },
                    onDismissMenu = onDismissMenu,
                    onTap = { onTap(tag) },
                    onRename = { onRename(tag) },
                    onMerge = { onMerge(tag) },
                    onDelete = { onDelete(tag) },
                )
            }
        }
    }
}

/**
 * 一颗胶囊 + 它的长按菜单。
 *
 * 点区（外层 48dp、透明）与视觉（内层 36dp、描边 `DreamColors.inkDim`）**是两层**：
 * 只有一层的话，要么点区不够 48dp，要么胶囊胖成一颗球。
 * 描边不用 `DreamColors.line`：`DreamColors.line` 在 DreamColors.night 上只有 1.34:1，等于没画。
 */
@Composable
private fun TagCapsule(
    tag: Tag,
    menuOpen: Boolean,
    onOpenMenu: () -> Unit,
    onDismissMenu: () -> Unit,
    onTap: () -> Unit,
    onRename: () -> Unit,
    onMerge: () -> Unit,
    onDelete: () -> Unit,
) {
    Box(
        modifier = Modifier.heightIn(min = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .combinedClickable(
                    onClick = onTap,
                    onLongClick = onOpenMenu,
                    onLongClickLabel = "更多操作",
                )
                .semantics { contentDescription = tag.name }
                .heightIn(min = 48.dp)
                .padding(vertical = 6.dp)
                .height(CapsuleHeight)
                .clip(CapsuleShape)
                .background(DreamColors.nightRaised)
                .border(1.dp, DreamColors.inkDim, CapsuleShape)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = tag.name,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                color = DreamColors.paper,
                maxLines = 1,
            )
        }

        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = onDismissMenu,
            containerColor = DreamColors.nightRaised,
            shape = RoundedCornerShape(12.dp),
        ) {
            MenuItem("重命名", DreamColors.paper, onRename)
            MenuItem("合并到…", DreamColors.paper, onMerge)
            MenuItem("删除", DreamColors.errorText, onDelete)
        }
    }
}

@Composable
private fun MenuItem(label: String, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text = label, style = MaterialTheme.typography.bodyMedium, color = color) },
        onClick = onClick,
    )
}

/** 空态：库里有标签才是常态，这里只在「一个标签都没有」时出现 */
@Composable
private fun EmptyTags(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = "还没有标签", style = MaterialTheme.typography.bodyLarge, color = DreamColors.inkDim)
        Text(
            text = "打上标签后，会按人物、地点、物品…分组显示在这里",
            modifier = Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = DreamColors.inkFaint,
            textAlign = TextAlign.Center,
        )
    }
}

/** 重命名弹窗：输入框自己画，M3 的 `OutlinedTextField` 在夜间会带一层亮色描边 */
@Composable
private fun RenameDialog(tag: Tag, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember(tag.id) { mutableStateOf(tag.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = glassDialogModifier(),
        containerColor = Color.Transparent,
        titleContentColor = DreamColors.paper,
        textContentColor = DreamColors.inkDim,
        title = { Text("重命名标签", style = MaterialTheme.typography.titleMedium) },
        text = {
            PillField(
                value = text,
                onValueChange = { text = it },
                placeholder = "标签名字",
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) { Text("确定", color = DreamColors.ember) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = DreamColors.inkDim) }
        },
    )
}

/** 二次确认弹窗（删除 / 撞名合并共用）。底色同样写死 `DreamColors.nightRaised`（D70） */
@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    confirmColor: androidx.compose.ui.graphics.Color,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = glassDialogModifier(),
        containerColor = Color.Transparent,
        titleContentColor = DreamColors.paper,
        textContentColor = DreamColors.inkDim,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = { Text(message, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmLabel, color = confirmColor) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = DreamColors.inkDim) }
        },
    )
}

/**
 * 合并目标选择器：`ModalBottomSheet`（brief §0.6 明确**不许**用 `AlertDialog`——
 * 对话框里的列表一长就滚不动，也放不下搜索框）。
 *
 * `imePadding()` 是必须的：弹出键盘后不垫这一下，列表下半截会被输入法盖住，
 * 用户以为「没有更多标签了」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MergeSheet(
    source: Tag,
    candidates: List<Tag>,
    onDismiss: () -> Unit,
    onPick: (Tag) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    var query by remember { mutableStateOf("") }
    val filtered = candidates.filter { query.isBlank() || it.name.contains(query.trim()) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.glassSurface(LocalDream.current.isDark, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)),
        containerColor = Color.Transparent,
        contentColor = DreamColors.paper,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 8.dp)
                    .width(36.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(DreamColors.inkDim),
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = "合并「${source.name}」到…",
                style = MaterialTheme.typography.titleMedium,
                color = DreamColors.paper,
            )
            Text(
                text = "合并后「${source.name}」会消失，它标注的梦都改挂到目标标签上。",
                modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = DreamColors.inkDim,
            )
            PillField(
                value = query,
                onValueChange = { query = it },
                placeholder = "搜索标签",
            )
            if (filtered.isEmpty()) {
                Text(
                    text = "没有可合并的标签",
                    modifier = Modifier.padding(top = 24.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = DreamColors.inkFaint,
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp).padding(top = 8.dp)) {
                    items(filtered, key = { it.id }) { tag ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .combinedClickable(onClick = { onPick(tag) }, onLongClick = null)
                                .semantics { contentDescription = tag.name },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(text = tag.name, style = MaterialTheme.typography.bodyLarge, color = DreamColors.paper)
                            Text(
                                text = kindTitle(tag.kind),
                                modifier = Modifier.padding(start = 8.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = DreamColors.inkFaint,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 输入框：一个圆角胶囊，光标用 `DreamColors.ember`（与搜索页同一种写法） */
@Composable
private fun PillField(value: String, onValueChange: (String) -> Unit, placeholder: String) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = DreamColors.paper),
        cursorBrush = SolidColor(DreamColors.ember),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { innerTextField ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(CapsuleShape)
                    .background(DreamColors.night)
                    .border(1.dp, DreamColors.inkDim, CapsuleShape)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) {
                    Text(text = placeholder, style = MaterialTheme.typography.bodyLarge, color = DreamColors.inkFaint)
                }
                innerTextField()
            }
        },
    )
}

/** `kind` → 分组中文名；未知 kind 一律显示「其他」（与 [groupTags] 的归一规则一致） */
private fun kindTitle(kind: String): String =
    TAG_GROUPS.firstOrNull { it.first == kind }?.second ?: "其他"
