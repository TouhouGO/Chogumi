package io.github.touhougo.chogumi.feature.home.widget

import androidx.work.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetRefreshWorkerTest {

    @Test
    fun `刷新请求无需联网`() {
        assertEquals(NetworkType.NOT_REQUIRED, WidgetRefreshWorker.refreshRequest().workSpec.constraints.requiredNetworkType)
    }
}
