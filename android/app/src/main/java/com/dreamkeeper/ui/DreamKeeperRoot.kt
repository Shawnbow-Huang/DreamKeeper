package com.dreamkeeper.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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
import com.dreamkeeper.ui.nav.DreamRoute
import com.dreamkeeper.ui.theme.Night
import java.time.LocalDate

/**
 * 根界面：唯一的导航真源（brief Step 5）。
 *
 * 底部栏只在「日历 / 全部」两个主路由显示；进当日列表、搜索、详情等子页时
 * Scaffold 的 innerPadding 会跟着变，子页不需要自己再算导航栏避让。
 */
@Composable
fun DreamKeeperRoot(repo: DreamRepository) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val current = entry?.destination?.route
    val showBottomBar = current == DreamRoute.Calendar.route || current == DreamRoute.Library.route

    Scaffold(
        containerColor = Night,
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(containerColor = Night) {
                    NavigationBarItem(
                        selected = current == DreamRoute.Calendar.route,
                        onClick = { nav.navigate(DreamRoute.Calendar.route) { launchSingleTop = true } },
                        icon = {}, label = { Text("日历") },
                    )
                    NavigationBarItem(
                        selected = current == DreamRoute.Library.route,
                        onClick = { nav.navigate(DreamRoute.Library.route) { launchSingleTop = true } },
                        icon = {}, label = { Text("全部") },
                    )
                }
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
                    onSearch = { /* Task 8：nav.navigate(DreamRoute.Search.route) */ },
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
            composable(DreamRoute.Library.route) { /* Task 7：LibraryScreen(...) */ }
            // 下面五个路由 Plan 1 允许是空屏/占位——重点是**路径已经存在**，
            // Plan 2 的录音与 Plan 4 的通知只往里填内容，不会再动根界面结构。
            composable(DreamRoute.Search.route) { /* Task 8 */ }
            composable(DreamRoute.Tags.route) { /* Task 9 */ }
            composable("detail/{dreamId}") { /* Task 8 */ }
            composable("record/{entry}") { /* Plan 2：RecordScreen */ }
            composable("followup/{dreamId}") { /* Plan 2：FollowUpScreen */ }
        }
    }
}
