package io.github.touhougo.chogumi.feature.home.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.glance.appwidget.AppWidgetId
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.touhougo.chogumi.core.data.util.storageOutcomeOf
import io.github.touhougo.chogumi.core.data.util.outcomeOf
import io.github.touhougo.chogumi.core.foundation.Outcome
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val Context.subjectWidgetRestores by preferencesDataStore("subject-widget-restores")
private val restoreLock = Mutex()
private val RestoredBatch = stringPreferencesKey("restoredBatch")

/**
 * 系统恢复只给旧新 ID，不搬应用私有状态。先持久化整批旧选择，再写新 ID，避免交叉 ID 与中途失败丢选择。
 * 每个目标记已应用批次；重试不会覆盖该实例在恢复后重新选择的作品。批次完成标记也保留，重复广播不再搬一次。
 * 只搬作品选择，不搬 RemoteViews 布局、配色或其他类型的状态。
 */
internal suspend fun restoreSubjectWidgets(context: Context, oldIds: IntArray, newIds: IntArray): Outcome<Unit> = storageOutcomeOf {
    require(oldIds.size == newIds.size)
    restoreLock.withLock {
        val batch = oldIds.joinToString(",") + ":" + newIds.joinToString(",")
        val key = stringPreferencesKey(batch)
        val journal = context.applicationContext.subjectWidgetRestores
        var saved = journal.data.first()[key]
        if (saved == "done") return@withLock
        if (saved == null) {
            val choices = oldIds.map { id ->
                getAppWidgetState(context, PreferencesGlanceStateDefinition, AppWidgetId(id))[SubjectKey] ?: 0
            }
            saved = choices.joinToString(",")
            journal.edit { it[key] = saved }
        }
        val choices = saved.split(',').map(String::toInt)
        val manager = AppWidgetManager.getInstance(context)
        newIds.forEachIndexed { index, id ->
            if (manager.getAppWidgetInfo(id)?.provider == ComponentName(context, SubjectWidgetReceiver::class.java)) {
                updateAppWidgetState(context, AppWidgetId(id)) { state ->
                    if (state[RestoredBatch] != batch) {
                        val subject = choices[index]
                        if (subject > 0) state[SubjectKey] = subject else state.remove(SubjectKey)
                        state[RestoredBatch] = batch
                    }
                }
            }
        }
        journal.edit { it[key] = "done" }
    }
}

/** 恢复工作跨进程持久化；本机读取或写入失败按 WorkManager 退避重试。 */
class SubjectWidgetRestoreWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val oldIds = inputData.getIntArray(OLD_IDS) ?: return Result.failure()
        val newIds = inputData.getIntArray(NEW_IDS) ?: return Result.failure()
        if (oldIds.size != newIds.size || oldIds.isEmpty()) return Result.failure()
        return when (restoreSubjectWidgets(applicationContext, oldIds, newIds)) {
            is Outcome.Failed -> Result.retry()
            is Outcome.Success -> {
                when (outcomeOf { requestWidgetUpdates(applicationContext) }) {
                    is Outcome.Success -> Result.success()
                    is Outcome.Failed -> Result.retry()
                }
            }
        }
    }

    internal companion object {
        private const val OLD_IDS = "oldIds"
        private const val NEW_IDS = "newIds"

        fun enqueue(context: Context, oldIds: IntArray, newIds: IntArray): androidx.work.Operation? {
            if (oldIds.size != newIds.size || oldIds.isEmpty()) return null
            val name = "subject-widget-restore:" + oldIds.joinToString(",") + ":" + newIds.joinToString(",")
            return WorkManager.getInstance(context).enqueueUniqueWork(name, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<SubjectWidgetRestoreWorker>()
                    .setInputData(workDataOf(OLD_IDS to oldIds, NEW_IDS to newIds)).build())
        }
    }
}
