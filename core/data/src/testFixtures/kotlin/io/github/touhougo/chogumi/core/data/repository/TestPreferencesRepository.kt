package io.github.touhougo.chogumi.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import io.github.touhougo.chogumi.core.model.ExperienceMode
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking

/**
 * 测试用 [PreferencesRepository]（唯一实现，供各模块测试共用）。
 *
 * 走真实 DataStore + 真实仓库，**不 mock 键名**：`pref_experience_mode` 等键名由
 * 生产代码的 [PreferenceKeys] 保证，测试里不存在第二份键名清单。
 *
 * 生命周期由调用方持有（`close()` 释放 scope 与临时目录），避免 DataStore 实例在
 * 同一文件上泄漏；每个实例用独立临时目录，互不干扰。
 *
 * @param experienceMode 初始体验模式，默认完整模式（与生产缺省一致）。
 */
class TestPreferencesRepository(
    experienceMode: ExperienceMode = ExperienceMode.FULL,
    showPublicRatings: Boolean = true,
) {
    private val resources = TestResources()
    private val scope = resources.scope
    private val dir = resources.directory("prefs-test")

    val repository: PreferencesRepository = PreferencesRepository(
        PreferenceDataStoreFactory.create(scope = scope) { File(dir, "p.preferences_pb") },
    )

    init {
        // 初始值写入磁盘：下游 `first()` 首次读取就能看到它（DataStore 首读不返回默认值之外的猜测）。
        runBlocking {
            repository.setExperienceMode(experienceMode)
            repository.setShowPublicRatings(showPublicRatings)
        }
    }

    fun close() = resources.close()
}

/**
 * 读失败可控的偏好仓库：[failReads] 为 true 时，`data` 的每次新订阅立刻抛 `IOException`（磁盘读失败）并计入 [failedReads]；
 * 为 false 时读真实 DataStore。写入始终落到真实 DataStore，所以可以先写好偏好再让读失败、放行后读到它。
 * 给「偏好读不出时页面怎么办」与「刷新不重读偏好」的测试用；生命周期由 [resources] 持有。
 */
class FaultablePreferencesRepository(resources: TestResources) {
    val failReads = AtomicBoolean(false)
    val failedReads = AtomicInteger()
    val repository: PreferencesRepository

    init {
        val real = PreferenceDataStoreFactory.create(scope = resources.scope) { File(resources.directory("prefs-fault"), "p.preferences_pb") }
        repository = PreferencesRepository(
            object : DataStore<Preferences> by real {
                override val data: Flow<Preferences> = flow {
                    if (failReads.get()) {
                        failedReads.incrementAndGet()
                        throw IOException("injected disk failure")
                    }
                    emitAll(real.data)
                }
            },
        )
    }
}
