package io.github.touhougo.chogumi.core.ui.composables

import io.github.touhougo.chogumi.core.foundation.Failure
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [resolveLoadMoreStatus] 纯函数单元测试。
 *
 * 验证规则：
 * 1. 出错优先（error != null 时恒为 ERROR，覆盖 loadingMore/hasMore 各种分支）；
 * 2. 加载中（error == null 且 loadingMore == true 时恒为 LOADING）；
 * 3. 到底有文案（!hasMore 且 endText 非空时为 END）；
 * 4. 到底无文案（!hasMore 且 endText 为空或空白时为 ABSENT 缺席）；
 * 5. 空列表到底也缺席（空态由各页自己说，不叠一句「没有更多」）；
 * 6. 还有下一页（hasMore == true 且未在 loading 时为 IDLE 待续取）；
 * 7. 调用方给了暂停说明且还有下一页时为 PAUSED（不自动续取），空列表也显示；出错、加载中优先于它。
 */
class LoadMoreStatusTest {

    @Test
    fun 出错优先() {
        assertEquals(
            LoadMoreStatus.ERROR,
            resolveLoadMoreStatus(loadingMore = true, hasMore = true, error = Failure.Timeout, endText = "到底"),
        )
        assertEquals(
            LoadMoreStatus.ERROR,
            resolveLoadMoreStatus(loadingMore = false, hasMore = false, error = Failure.Offline, endText = null),
        )
    }

    @Test
    fun 加载中() {
        assertEquals(
            LoadMoreStatus.LOADING,
            resolveLoadMoreStatus(loadingMore = true, hasMore = true, error = null, endText = null),
        )
        assertEquals(
            LoadMoreStatus.LOADING,
            resolveLoadMoreStatus(loadingMore = true, hasMore = false, error = null, endText = "到底"),
        )
    }

    @Test
    fun 到底有文案() {
        assertEquals(
            LoadMoreStatus.END,
            resolveLoadMoreStatus(loadingMore = false, hasMore = false, error = null, endText = "没有更多结果了"),
        )
    }

    @Test
    fun 到底无文案等于缺席() {
        assertEquals(
            LoadMoreStatus.ABSENT,
            resolveLoadMoreStatus(loadingMore = false, hasMore = false, error = null, endText = null),
        )
        assertEquals(
            LoadMoreStatus.ABSENT,
            resolveLoadMoreStatus(loadingMore = false, hasMore = false, error = null, endText = ""),
        )
        assertEquals(
            LoadMoreStatus.ABSENT,
            resolveLoadMoreStatus(loadingMore = false, hasMore = false, error = null, endText = "   "),
        )
    }

    @Test
    fun 空列表到底等于缺席() {
        assertEquals(
            LoadMoreStatus.ABSENT,
            resolveLoadMoreStatus(
                loadingMore = false, hasMore = false, error = null,
                endText = "没有更多结果了", hasItems = false,
            ),
        )
        // 出错与加载中不看列表是否为空（续取失败照样要给重试入口）
        assertEquals(
            LoadMoreStatus.ERROR,
            resolveLoadMoreStatus(loadingMore = false, hasMore = true, error = Failure.Offline, hasItems = false),
        )
    }

    @Test
    fun 还有下一页等于待续取() {
        assertEquals(
            LoadMoreStatus.IDLE,
            resolveLoadMoreStatus(loadingMore = false, hasMore = true, error = null, endText = null),
        )
        assertEquals(
            LoadMoreStatus.IDLE,
            resolveLoadMoreStatus(loadingMore = false, hasMore = true, error = null, endText = "不应触发到底"),
        )
    }

    @Test
    fun 暂停只在还有下一页时出现且不自动续取() {
        assertEquals(
            LoadMoreStatus.PAUSED,
            resolveLoadMoreStatus(loadingMore = false, hasMore = true, error = null, pausedText = "往前翻了 400 条"),
        )
        // 空列表也要给「继续往前找」：整页只剩这一行
        assertEquals(
            LoadMoreStatus.PAUSED,
            resolveLoadMoreStatus(loadingMore = false, hasMore = true, error = null, hasItems = false, pausedText = "停下"),
        )
        // 到底了就没有可继续的
        assertEquals(
            LoadMoreStatus.END,
            resolveLoadMoreStatus(loadingMore = false, hasMore = false, error = null, endText = "到底", pausedText = "停下"),
        )
        assertEquals(
            LoadMoreStatus.ERROR,
            resolveLoadMoreStatus(loadingMore = false, hasMore = true, error = Failure.Offline, pausedText = "停下"),
        )
        assertEquals(
            LoadMoreStatus.LOADING,
            resolveLoadMoreStatus(loadingMore = true, hasMore = true, error = null, pausedText = "停下"),
        )
    }
}
