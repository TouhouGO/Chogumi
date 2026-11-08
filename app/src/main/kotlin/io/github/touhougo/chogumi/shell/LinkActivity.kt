package io.github.touhougo.chogumi.shell

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity

/**
 * 从外面进来的链接的入口（清单里只有它挂 VIEW 的 intent-filter）：浏览器的 OAuth 回跳，和别的应用里点开的 Bangumi 网页链接。
 * 把地址原样交给 [AppActivity]，并把任务清回它——授权页的 Custom Tab 一并清掉，实例只剩一个；从别的应用的任务里进来时，
 * `NEW_TASK` 把它带回应用自己的任务。[AppActivity] 因此不必是 singleTask，从桌面图标回来不会清掉别的页面。
 */
class LinkActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(this, AppActivity::class.java)
                .setData(intent?.data)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }
}
