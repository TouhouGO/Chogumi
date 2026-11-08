package io.github.touhougo.chogumi.core.ui.common.navigation

import androidx.compose.material3.ShapeDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 起点索引只随认领变化，多个导航项共用的起点在最后一个释放后回收。 */
class PageExpandRegistryTest {
    @Test
    fun `同一token跨tab认领只在最后一页退出时释放`() {
        val registry = PageExpandRegistry()
        val route = Route.SubjectDetails(1)
        listOf(Route.Home, Route.Explore).forEach { tab ->
            registry.press("cover")
            registry.origins["cover"] = OriginLook(ShapeDefaults.Medium, 60f)
            registry.claim(tab, route, tab)
            registry.register(tab, tab, route)
        }
        registry.remove(Route.Home, Route.Home)
        assertTrue(registry.isActive("cover"))
        assertEquals(1, registry.origins.size)
        registry.remove(Route.Explore, Route.Explore)
        assertFalse(registry.isActive("cover"))
        assertEquals(0, registry.origins.size)
    }

    @Test
    fun `未导航的按压释放后不留起点外观`() {
        val registry = PageExpandRegistry()
        registry.press("cover")
        registry.origins["cover"] = OriginLook(ShapeDefaults.Medium, 60f)
        registry.release("cover")
        assertFalse(registry.isActive("cover"))
        assertEquals(0, registry.origins.size)
    }
}
