package io.github.touhougo.chogumi.core.common.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.touhougo.chogumi.core.common.notice.FailureNotices
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.advance
import io.github.touhougo.chogumi.core.foundation.hiddenFailureOf
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 页面 ViewModel 的唯一基类：持有**一份**不可变的页面状态。
 *
 * - 界面只读 [uiState]；ViewModel 只经 [updateState] 整体换新（原子的读—改—写，并发更新不会互相覆盖）。
 * - 基类不规定状态长什么样：加载中、出错等字段由各页面状态按自己的含义定义，没有要子类逐个实现的模板方法。
 * - 页面上各自加载的区块（作品、角色、关联……）每块是状态里的一个 [Load]，统一经 [launchSection] / [collectSection] 收。
 * - 一次性的失败提示不放进状态，走 [io.github.touhougo.chogumi.core.common.notice.FailureNotices]。
 */
abstract class StateViewModel<S>(initialState: S) : ViewModel() {
    private val state = MutableStateFlow(initialState)

    val uiState: StateFlow<S> = state.asStateFlow()

    /** 当前状态的快照（读最新值用；改状态一律走 [updateState]）。 */
    protected val currentState: S get() = state.value

    protected fun updateState(transform: (S) -> S) {
        state.update(transform)
    }

    /**
     * 跟着一条不是页面区块的读取流走（本机名单：置顶、屏蔽……）：每到一份交给 [onValue]；读取失败发到 [failures]，
     * 提示条上的重试取消旧订阅、重新订阅 [source]。
     */
    protected fun <T> follow(failures: FailureNotices, source: () -> Flow<Load<T>>, onValue: (T) -> Unit) {
        var job: Job? = null
        fun subscribe() {
            job?.cancel()
            job = viewModelScope.launch {
                source().collect { load ->
                    when (load) {
                        is Load.Ready -> onValue(load.value)
                        is Load.Failed -> failures.post(load.failure, retry = ::subscribe)
                        Load.Pending -> Unit
                    }
                }
            }
        }
        subscribe()
    }

    /**
     * 一个区块跟着一条加载流走：先进入等待（已显示的内容不动，重试时失败态换成骨架），之后每拍往前走一步（[advance]）。
     * 区块之间互不影响，一块取不到只落在这一块上。[alongside] 拿到这一拍原样的结果，改别的、须与区块同一次状态更新
     * 落下的字段（例：写入之后的回读一上屏就落下忙态，界面上不会有一拍「新值、还在忙」）。
     *
     * 区块已有内容时来了失败：内容留着，失败发到 [failures]（带 [retry]），判定见 [hiddenFailureOf]。
     * 没有内容时失败落在区块上，界面画重试，不另发提示。[failures] 为 null 只给两种区块：只在没有内容时才重取的
     * （进页一次，失败后重试），和页面自己汇总一轮失败、统一提示一次的。
     */
    protected suspend fun <T> Flow<Load<T>>.collectSection(
        failures: FailureNotices?,
        retry: (() -> Unit)?,
        get: (S) -> Load<T>,
        alongside: S.(Load<T>) -> S = { this },
        set: S.(Load<T>) -> S,
    ) = collectSection(this, failures, retry, get, alongside, set, ::updateState)

    private val sectionRounds = mutableMapOf<Any, Job>()

    /**
     * 起一轮区块收集（[collectSection]），同一个 [key] 的上一轮先取消：连点重试、下拉刷新不会两轮交替写入，
     * 慢的旧一轮也盖不掉新一轮。有内容时失败的提示条上，重试默认就是用同一个 [source] 再起一轮；
     * 重试要换做法时（只在还停在这个分类才重取……）传 [retry]。[afterCollected] 在这一轮的流收完之后接着做
     * （区块落定后的下一步，例：就绪了才开始汇总），被下一轮取消时不会执行。
     * 只在主线程调（ViewModel 的方法与 viewModelScope 都在主线程）。
     */
    protected fun <T> launchSection(
        key: Any,
        failures: FailureNotices?,
        source: suspend () -> Flow<Load<T>>,
        get: (S) -> Load<T>,
        alongside: S.(Load<T>) -> S = { this },
        retry: (() -> Unit)? = null,
        afterCollected: suspend () -> Unit = {},
        set: S.(Load<T>) -> S,
    ) {
        fun round() {
            cancelSection(key)
            sectionRounds[key] = viewModelScope.launch {
                source().collectSection(failures, retry ?: ::round, get, alongside, set)
                afterCollected()
            }
        }
        round()
    }

    /** 等 [key] 这一区块正在跑的一轮落定（例：发出回复后重拉楼层，楼层到了才落到新楼上）；被下一轮取消时也返回。 */
    protected suspend fun joinSection(key: Any) {
        sectionRounds[key]?.join()
    }

    /** 停掉 [key] 这一区块还在跑的一轮（例：换了账号，旧一轮的结果不该再写进来）。 */
    protected fun cancelSection(key: Any) {
        sectionRounds.remove(key)?.cancel()
    }
}

/** 页面与独立控制器共用的分区收集：内容保留、失败通知和同拍状态更新只在这一处裁决。 */
suspend fun <S, T> collectSection(
    source: Flow<Load<T>>,
    failures: FailureNotices?,
    retry: (() -> Unit)?,
    get: (S) -> Load<T>,
    alongside: S.(Load<T>) -> S = { this },
    set: S.(Load<T>) -> S,
    update: ((S) -> S) -> Unit,
) {
    update { it.set(get(it).advance(Load.Pending)) }
    source.collect { load ->
        var hidden: Failure? = null
        update { state ->
            val shown = get(state)
            hidden = shown.hiddenFailureOf(load)
            state.set(shown.advance(load)).alongside(load)
        }
        hidden?.let { failures?.post(it, retry) }
    }
}
