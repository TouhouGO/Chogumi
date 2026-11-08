package io.github.touhougo.chogumi.core.ui.composables

import io.github.touhougo.chogumi.core.model.RelatedImagesBo
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 人物 / 角色的头像该取哪一档图。
 *
 * ## 为什么不用 `grid`（2026-10-06 实测）
 *
 * 同一角色两个接口给的 url：
 *
 * | 档位 | p1 | v0 |
 * |---|---|---|
 * | `large` | `/pic/crt/l/…` 原图 | 同左 |
 * | `medium` | `/r/200/pic/crt/l/…` 宽 200 整图 | `/r/400/…` 宽 400 整图 |
 * | `grid` | `/r/100x100/pic/crt/l/…` **居中**裁的方块 | `/pic/crt/g/…` 顶部取景、仅 75×75 |
 *
 * p1 的 `grid` 把全身立绘居中裁成方块，头被切掉（代表角色、关联人物、出演角色、收藏角色都是 p1 来源）；
 * v0 的 `grid` 取景对但太糊。所以头像取整张的 `medium`，由 `CircleAvatar` 顶对齐裁圆。
 *
 * 而**条目封面是另一回事**：`SubjectImages.large` 就是海报本身（3:4 竖版），
 * 海报卡片要的正是它。两个规则分开，所以这里是两个函数而不是一个。
 */
class PersonImageTest {

    @Test
    fun `头像优先取整张的 medium`() {
        val all = RelatedImagesBo(large = "L", common = "C", medium = "M", small = "S", grid = "G")
        assertEquals("M", all.avatarUrl(), "medium 是等比缩放的整图，清晰度够且能顶对齐裁出脸")
    }

    @Test
    fun `缺 medium 依次回退 large、small、grid`() {
        assertEquals("L", RelatedImagesBo(large = "L", small = "S", grid = "G").avatarUrl())
        assertEquals("S", RelatedImagesBo(small = "S", grid = "G").avatarUrl())
        assertEquals("G", RelatedImagesBo(grid = "G").avatarUrl(), "只剩 grid 时也要给点什么，总比空白强")
        assertEquals(null, RelatedImagesBo().avatarUrl())
    }

    @Test
    fun `空串按缺失处理`() {
        // 服务端会用空串而不是缺键表示"没有这张图"，空串当有值会渲染成破图
        assertEquals("S", RelatedImagesBo(medium = "", large = " ", small = "S").avatarUrl())
        assertEquals(null, RelatedImagesBo(medium = "", grid = "  ").avatarUrl())
    }

    @Test
    fun `条目封面仍用海报档位而不是方图`() {
        val images = RelatedImagesBo(large = "L", common = "C", medium = "M", small = "S", grid = "G")
        assertEquals("L", images.posterUrl(), "条目封面要的是 3:4 海报，large 才是它")
        assertEquals("C", RelatedImagesBo(common = "C", grid = "G").posterUrl(), "缺 large 才退 common")
        assertEquals("G", RelatedImagesBo(grid = "G").posterUrl(), "只剩 grid 时也要给点什么，总比空白强")
    }
}
