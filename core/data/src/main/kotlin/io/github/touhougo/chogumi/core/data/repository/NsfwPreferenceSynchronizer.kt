package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.data.token.TokenStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * NSFW 偏好的**唯一刷新观察点**。
 *
 * ## 为什么要有这个类（红线：刷新触发点唯一）
 *
 * 刷新时机有三处：登录完成、App 启动（已登录）、进入设置页。前两者都等价于
 * 「当前账号身份发生变化」——若分别写在 `AuthRepository.login()` 与
 * `ensureInitialized()` 里，就是两个触发点，将来加一条登录路径（例如令牌登录、
 * 静默换号）必然会漏掉一处。故这里只观察会话流（[TokenStore.recoveringSessions]）的**会话身份**：
 * **启动恢复**与**新登录**都会让身份从 null 变为一个会话，一个观察点覆盖两者。
 *
 * ## 为什么按会话代数（generation）去重，而不是按 authenticated 布尔位或 userId
 *
 * `authenticated` 只有 true/false 两态，去重后**换号（A→B）与登录一样都是
 * true→true**，观察者会把它当作"没有变化"而跳过刷新 —— 换号后新账号的偏好
 * 就不会刷新，且这是**静默**的。
 *
 * 更糟的是，登录/换号是**两次写库**：`beginSession`（写入 token、清掉 userId →
 * 未认证）再 `completeSession`（写回 userId → 已认证）。`sessionFlow` 的底层是
 * DataStore 的 `MutableStateFlow`（**conflating，会合并中间态**），若观察者在这两次
 * 写之间没被调度到（换号、以及 CI 高负载下的调度延迟），中间态 `authenticated=false`
 * 就被合并掉，观察者只看到 `true → true`，刷新被吞。
 *
 * 按 `userId` 去重也不够：登出再登录**同一个账号**，中间的 null 被合并掉时末值仍是 1001→1001，
 * 重新登录后就不刷新。所以按 [io.github.touhougo.chogumi.core.data.token.AccountSession.generation] 去重：
 * 登出、每次登录都让它加一，令牌刷新不动它。换号、重登无论中间态是否可见，末值都变，必然触发刷新；
 * 登出（→ null）不刷新（无账号可读，缓存按 userId 分区保留）。
 *
 * ## 为什么放在 core:data 而不是 AuthRepository
 *
 * `AuthRepository` 只管会话本身，不依赖偏好仓库（否则登录链路被 NSFW 拉下水，
 * 也违反分层）。本类作为独立组件持有这个副作用，构造时即在 App 级
 * [CoroutineScope] 上开始观察，与 ViewModel/页面无关。
 *
 * ## ⚠️ 必须列进 `BackgroundStartup`（否则本类等于不存在）
 *
 * Koin 的 `single` 是**懒加载**：只有被 `get()` 解析时才构造。本类没有任何调用方
 * 会去 `get()` 它（它纯粹是"注册即生效"的副作用组件），若只按普通 single 注册，
 * `init` 永不执行、观察永不开始 —— 表现为"登录后 NSFW 开关永远不同步"，
 * 且没有任何报错。故 DataModule 里 `BackgroundStartup` 那一行必须列上它，启动时在后台建好。
 * 守卫见 `NsfwPreferenceSynchronizerWiringTest`。
 *
 * ## 观察者必须活得久（异常不得外泄）
 *
 * collect 一旦抛异常就结束，此后换号/重登都不再刷新。故：
 * - [NsfwPreferenceRepository.refresh] 的契约是**只返回错误态、不抛非取消异常**
 *   （见其文档），这里直接调用即可；
 * - 凭证读不出（DataStore IO 异常）时跟的是 [TokenStore.recoveringSessions]：退避后重新订阅，
 *   读出来之后的登录、换号照常刷新。
 */
class NsfwPreferenceSynchronizer(
    private val tokenStore: TokenStore,
    private val nsfwPreferenceRepository: NsfwPreferenceRepository,
    scope: CoroutineScope,
) {
    init {
        scope.launch {
            tokenStore.recoveringSessions
                // 会话身份：未认证统一映射为 null，认证则取会话代数。
                // 不用 authenticated 布尔位或 userId 去重：换号、同号重登的中间态被合并时会漏刷新（见类文档）。
                .map { if (it.authenticated) it.generation else null }
                .distinctUntilChanged()
                .collect { generation ->
                    // 未认证（null）不动：登出/游客没账号可读，缓存保留（换号按 userId 分区隔离）。
                    if (generation != null) nsfwPreferenceRepository.refresh()
                }
        }
    }
}
