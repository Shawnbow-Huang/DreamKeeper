package com.dreamkeeper.ui.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置页路由的纯 JVM 单测（D226：仓库没有 ui-test-junit4，导航只证路由 token）。
 *
 * 三条分开断言，是为了让「实现改坏」时能指出坏在哪：
 * 1. `Settings` 这个路由对象存在，route 是稳定 token `settings`（不是空串、不是随手拼的字符串）；
 * 2. 它与现有四个顶层/子页路由**不撞车**——撞车的后果是 NavHost 里后注册的覆盖先注册的，
 *    点「设置」会跳到日历（表现为点了没反应），而 Compose 的 popUpTo 也会弹错栈；
 * 3. Settings 在已知单例路由集合里只出现一次。
 */
class DreamRouteTest {

    /** 所有无参数、可直接实例化的路由；带参数的路由（Day/Detail/Record/FollowUp）不参与 token 唯一性对照。 */
    private val singletonRoutes = listOf(
        DreamRoute.Calendar,
        DreamRoute.Library,
        DreamRoute.Search,
        DreamRoute.Tags,
        DreamRoute.Settings,
    )

    @Test
    fun `settings route token is exactly settings`() {
        assertEquals("settings", DreamRoute.Settings.route)
    }

    @Test
    fun `settings route does not collide with existing destinations`() {
        val routes = singletonRoutes.map { it.route }
        assertEquals(
            "两个路由共用了同一个 token：$routes",
            routes.size,
            routes.toSet().size,
        )
    }

    @Test
    fun `settings destination is listed exactly once`() {
        val settingsLike = singletonRoutes.count { it.route == DreamRoute.Settings.route }
        assertEquals(1, settingsLike)
        assertTrue(DreamRoute.Settings.route.isNotEmpty())
    }
}
