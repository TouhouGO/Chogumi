package io.github.touhougo.chogumi

import coil3.intercept.Interceptor
import coil3.network.httpHeaders
import coil3.request.ImageResult
import io.github.touhougo.chogumi.core.network.API_V0_HOST

/**
 * 发往 `api.bgm.tv` 的图片请求带上 App 的 User-Agent：那里拒绝图片库默认的（403）。图片里只有头像入口
 * （core:model `bgmUserAvatarUrl`，贴贴名单的头像）走它，跳转之后的图床请求沿用同一组请求头。
 */
internal class ApiImageUserAgent(private val userAgent: String) : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val url = chain.request.data as? String ?: return chain.proceed()
        if (!url.startsWith("$API_V0_HOST/")) return chain.proceed()
        val headers = chain.request.httpHeaders.newBuilder().set("User-Agent", userAgent).build()
        return chain.withRequest(chain.request.newBuilder().httpHeaders(headers).build()).proceed()
    }
}
