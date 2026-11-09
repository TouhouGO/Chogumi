package io.github.touhougo.chogumi.core.network

import okhttp3.OkHttpClient

/**
 * 建 OkHttp 客户端时对 builder 的一处调整（例如换一条连 bgm.tv 的线路）。三个接口客户端与图片加载都按 Koin 里登记的全部
 * customizer 依次套上；网络层不知道有哪些、各做什么。
 */
fun interface OkHttpCustomizer {
    fun customize(builder: OkHttpClient.Builder)
}

/** 依次套上 [customizers]。 */
fun OkHttpClient.Builder.customizedBy(customizers: List<OkHttpCustomizer>): OkHttpClient.Builder =
    apply { customizers.forEach { it.customize(this) } }
