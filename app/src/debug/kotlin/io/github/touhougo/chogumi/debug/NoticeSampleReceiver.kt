package io.github.touhougo.chogumi.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.touhougo.chogumi.core.data.util.outcomeOf
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.feature.community.notice.CommunityNotices
import io.github.touhougo.chogumi.feature.home.notice.AiringReminders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.qualifier.named

/**
 * 调试包才有：用 adb 立刻发系统通知，看样子、点开与按钮，不等真的回复或开播。发的是真的数据（最近的提醒、在追的作品），
 * 照真的样子发（`CommunityNotices.postSamples`、`AiringReminders.postSamples`）；不看设置里的开关，系统里关着通知时什么也不发。
 *
 * ```
 * adb shell am broadcast -n io.github.touhougo.chogumi.debug/io.github.touhougo.chogumi.debug.NoticeSampleReceiver --es kind community
 * ```
 *
 * kind：`community` 最近 3 条提醒（已读的也算），`message` 未读短信（`--ei count 5` 定条数，默认 3），
 * `airing` 在追作品每部一条开播提醒，`digest` 一条每日汇总。命令行的 `data=` 写发了几条（`digest` 写汇总里有几部）。
 *
 * 测整条链路（开关、基线、前台、读未读数）不用等后台任务：`check` 立刻跑一轮真的社区提醒检查（`CommunityNotices.check`），
 * `run` 立刻跑一次真的放送提醒（`AiringReminders.run`）。它们照真的规矩来：开关关着、刚打开的第一轮、App 在前台时都不发。
 */
class NoticeSampleReceiver : BroadcastReceiver(), KoinComponent {
    private val community: CommunityNotices by inject()
    private val airing: AiringReminders by inject()
    private val scope: CoroutineScope by inject(named("appScope"))

    override fun onReceive(context: Context, intent: Intent) {
        val kind = intent.getStringExtra(EXTRA_KIND)
        val pending = goAsync()
        scope.launch {
            try {
                val result = outcomeOf {
                    when (kind) {
                        "community" -> "posted ${community.postSamples()}"
                        "message" -> {
                            community.postMessageSample(intent.getIntExtra(EXTRA_COUNT, DEFAULT_MESSAGES))
                            "posted 1"
                        }
                        "airing" -> "posted ${airing.postSamples(digest = false)}"
                        "digest" -> "posted ${airing.postSamples(digest = true)}"
                        // 发没发由真的规矩定，这里不数
                        "check" -> "ran".also { community.check() }
                        "run" -> "ran".also { airing.run() }
                        else -> "kind: community | message | airing | digest | check | run"
                    }
                }
                pending.resultData = when (result) {
                    is Outcome.Success -> result.value
                    is Outcome.Failed -> "failed: ${result.failure}"
                }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val EXTRA_KIND = "kind"
        const val EXTRA_COUNT = "count"
        const val DEFAULT_MESSAGES = 3
    }
}
