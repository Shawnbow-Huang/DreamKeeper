package com.dreamkeeper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.dreamkeeper.data.DreamRepository
import com.dreamkeeper.ui.calendar.CalendarScreen
import com.dreamkeeper.ui.calendar.CalendarViewModel
import com.dreamkeeper.ui.day.DayScreen
import com.dreamkeeper.ui.day.DayViewModel
import com.dreamkeeper.ui.detail.DetailScreen
import com.dreamkeeper.ui.library.LibraryScreen
import com.dreamkeeper.ui.library.LibraryViewModel
import com.dreamkeeper.ui.library.LayoutPrefs
import com.dreamkeeper.ui.nav.DreamRoute
import com.dreamkeeper.ui.search.SearchScreen
import com.dreamkeeper.ui.theme.Ember
import com.dreamkeeper.ui.theme.InkDim
import com.dreamkeeper.ui.theme.Night
import com.dreamkeeper.ui.theme.Paper
import java.time.LocalDate

/**
 * 根界面：唯一的导航真源（brief Step 5）。
 *
 * 底部栏只在「日历 / 全部」两个主路由显示；进当日列表、搜索、详情等子页时
 * Scaffold 的 innerPadding 会跟着变，子页不需要自己再算导航栏避让。
 */
@Composable
fun DreamKeeperRoot(
    repo: DreamRepository,
    layoutPrefs: LayoutPrefs,
    initialSearchQuery: String = "",
) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val current = entry?.destination?.route
    val showBottomBar = current == DreamRoute.Calendar.route || current == DreamRoute.Library.route

    // debug 钩子（D67）：带 `dk_prefill_search` 冷启动时直接落到搜索页。
    // 只有 MainActivity 在 BuildConfig.DEBUG 下才会传非空值；这里不判断 DEBUG，免得 release 里多一份逻辑。
    LaunchedEffect(Unit) {
        if (initialSearchQuery.isNotBlank()) {
            nav.navigate(DreamRoute.Search.route) { launchSingleTop = true }
        }
    }

    Scaffold(
        containerColor = Night,
        bottomBar = {
            if (showBottomBar) {
                DreamBottomBar(
                    currentRoute = current,
                    onNavigate = { route -> nav.navigate(route) { launchSingleTop = true } },
                )
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = DreamRoute.Calendar.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(DreamRoute.Calendar.route) {
                val vm: CalendarViewModel = viewModel { CalendarViewModel(repo) }
                CalendarScreen(
                    vm = vm,
                    // 点格子进「当日列表」，**不是**进 Detail（D35）：
                    // 把 date.toString() 当 dreamId 传给详情，详情按 id 查库必然查不到 → 必空白。
                    onDayClick = { date -> nav.navigate(DreamRoute.Day(date.toString()).route) },
                    onSearch = { nav.navigate(DreamRoute.Search.route) },
                    onRecord = { /* Plan 2：这里换成 nav.navigate(DreamRoute.Record(RecordEntry.HOME_BUTTON).route) */ },
                )
            }
            composable("day/{dateIso}") { backStackEntry ->
                val iso = backStackEntry.arguments?.getString("dateIso")!!.also { require(it.isNotBlank()) }
                val date = LocalDate.parse(iso)   // 格式错了就直接炸，不要兜底成"今天"——静默兜底会掩盖路由参数写错
                val vm: DayViewModel = viewModel { DayViewModel(repo, date) }
                DayScreen(
                    vm = vm,
                    onOpen = { dreamId -> nav.navigate(DreamRoute.Detail(dreamId).route) },
                    onBack = { nav.popBackStack() },
                )
            }
            composable(DreamRoute.Library.route) {
                // ViewModel 挂在 Library 这条 NavBackStackEntry 上：切排布、从详情返回都在同一个实例上，
                // 不会每次都重新构造、把排布重新读一遍
                val vm: LibraryViewModel = viewModel { LibraryViewModel(repo, layoutPrefs) }
                LibraryScreen(
                    vm = vm,
                    onOpen = { dreamId -> nav.navigate(DreamRoute.Detail(dreamId).route) },
                )
            }
            // 搜索与详情在 Task 8 落成真页面；下面三个仍是 Plan 2/Plan 4 的占位——重点是
            // **路径已经存在**，后续任务只往里填内容，不会再动根界面结构。
            composable(DreamRoute.Search.route) {
                // 结果行点了必须真的进详情（brief 0.4）：不接这一行，搜索页能进但点了没反应
                SearchScreen(
                    repo = repo,
                    onOpen = { dreamId -> nav.navigate(DreamRoute.Detail(dreamId).route) },
                    onBack = { nav.popBackStack() },
                    initialQuery = initialSearchQuery,
                )
            }
            composable(DreamRoute.Tags.route) { /* Task 9 */ }
            composable("detail/{dreamId}") { backStackEntry ->
                val dreamId = backStackEntry.arguments?.getString("dreamId").orEmpty()
                DetailScreen(
                    dreamId = dreamId,
                    repo = repo,
                    onBack = { nav.popBackStack() },
                )
            }
            composable("record/{entry}") { /* Plan 2：RecordScreen */ }
            composable("followup/{dreamId}") { /* Plan 2：FollowUpScreen */ }
        }
    }
}

/**
 * 自绘底部栏——**不用** M3 `NavigationBar` / `NavigationBarItem`。
 *
 * `NavigationBarItem` 在选中时会在 icon 槽位上方画一颗 32×32 的指示药丸。Plan 1 的底部栏
 * 只有文字没有图标，按 brief 写 `icon = {}` 之后那颗药丸就是一块**没有内容的灰色胶囊**
 * （Task 6 截图里「日历」上方那一块）。M3 没有关掉它的开关，所以这里用一个 `Row` +
 * 两个等宽可点区域重画：选中 = `Paper` 文字 + 下方 2dp `Ember` 短横线，未选中 = `InkDim`。
 *
 * 两条腿要一起看：`navigationBarsPadding()` 让底栏自身垫开手势导航条，
 * 于是 Scaffold 把它们一起算进 `innerPadding`，子页不用再避让第二次。
 */
@Composable
private fun DreamBottomBar(
    currentRoute: String?,
    onNavigate: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Night)
            .navigationBarsPadding(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BottomBarTab(
            label = "日历",
            selected = currentRoute == DreamRoute.Calendar.route,
            onClick = { onNavigate(DreamRoute.Calendar.route) },
        )
        BottomBarTab(
            label = "全部",
            selected = currentRoute == DreamRoute.Library.route,
            onClick = { onNavigate(DreamRoute.Library.route) },
        )
    }
}

/** 等宽的一个 tab：整块可点，48dp 触控下限，选中时文字 `Paper` + 下方 20×2dp `Ember` 指示线 */
@Composable
private fun RowScope.BottomBarTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .weight(1f)
            .heightIn(min = 48.dp)
            .clickable { onClick() }
            .semantics {
                contentDescription = label
                this.selected = selected
            }
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) Paper else InkDim,
        )
        // 指示线始终占位：未选中时画成 Night（与底栏同色，看不见），
        // 这样切换选中不会让文字上下跳 6dp。不引透明色，配色只取 Color.kt 里的常量。
        Box(
            modifier = Modifier
                .padding(top = 4.dp)
                .width(20.dp)
                .height(2.dp)
                .background(if (selected) Ember else Night),
        )
    }
}
