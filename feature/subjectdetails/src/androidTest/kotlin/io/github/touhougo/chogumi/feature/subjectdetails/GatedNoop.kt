package io.github.touhougo.chogumi.feature.subjectdetails

import androidx.compose.runtime.Composable
import io.github.touhougo.chogumi.core.ui.common.Gated
import io.github.touhougo.chogumi.core.ui.common.rememberLoginGate

/** 画面类用例不测写操作：给要求「已过登录门」回调的组件凑一个什么都不做的参数。 */
@Composable
internal fun gatedNoop(): Gated<() -> Unit> = rememberLoginGate(isLoggedIn = true, onLogin = {}).guard {}

/** 同 [gatedNoop]，带一个参数的写操作（点某一集）。 */
@Composable
internal fun <A> gatedNoop1(): Gated<(A) -> Unit> = rememberLoginGate(isLoggedIn = true, onLogin = {}).guard { _: A -> }
