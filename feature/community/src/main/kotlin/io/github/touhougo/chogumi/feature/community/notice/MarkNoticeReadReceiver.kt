package io.github.touhougo.chogumi.feature.community.notice

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.touhougo.chogumi.core.data.repository.NotificationRepository
import io.github.touhougo.chogumi.core.ui.common.SystemNotices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.qualifier.named

/**
 * 社区提醒上的「标为已读」：在服务端标为已读（与铃铛面板里点开同一个仓库函数），撤掉这条通知。标不上时通知照样撤掉，
 * 提醒留在未读里，铃铛面板照常显示；下一轮检查不会再发它（比基线旧）。
 */
class MarkNoticeReadReceiver : BroadcastReceiver(), KoinComponent {
    private val notificationRepository: NotificationRepository by inject()
    private val scope: CoroutineScope by inject(named("appScope"))

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(SystemNotices.EXTRA_NOTICE_ID, 0)
        if (id <= 0) return
        SystemNotices.cancel(context, SystemNotices.NOTICE_TAG, id)
        // 广播结束前进程不会被回收：等这次请求回来再结束
        val pending = goAsync()
        scope.launch {
            try {
                notificationRepository.markSystemNoticeRead(id, intent.getStringExtra(SystemNotices.EXTRA_NOTICE_PROOF))
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        fun intent(context: Context, id: Int, proof: String): PendingIntent = PendingIntent.getBroadcast(
            context,
            id,
            Intent(context, MarkNoticeReadReceiver::class.java).putExtra(SystemNotices.EXTRA_NOTICE_ID, id)
                .putExtra(SystemNotices.EXTRA_NOTICE_PROOF, proof),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
