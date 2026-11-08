package io.github.touhougo.chogumi.core.network.api

import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.FailureException
import io.github.touhougo.chogumi.core.foundation.RejectReason
import io.github.touhougo.chogumi.core.network.NEXT_API_HOST
import io.github.touhougo.chogumi.core.network.model.p1.PrivacyDto
import io.github.touhougo.chogumi.core.network.model.p1.PrivacyPatchDto
import io.github.touhougo.chogumi.core.network.model.p1.PrivacyPreferencesPatchDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType

/**
 * 账号隐私设置（p1）。
 *
 * ## 为什么 NSFW 显示开关要用这个端点
 *
 * 服务端把「是否允许看 NSFW」的判定权收在**账号**上（`p1` 的 `auth.allowNsfw`），
 * 它由三项共同决定：账号开了 `show_nsfw_subject`、注册满 60 天、未被封禁。
 * 客户端本地开关**绕不过**这个判定（服务端照样剔除），所以本地开关只能是
 * 「服务端偏好的缓存」，不能是真源 —— 见 `NsfwPreferenceRepository`。
 *
 * ## 强制凭据
 *
 * 两个端点都是**本人**的隐私设置，必须经 [pinSessionToken] 指定凭据：
 * 共享客户端的 `loadTokens` 恒为 null（防串号，见 NetworkModule），没有一方会自动补头。
 * 与 [UserApi.getMe] 的强制凭据同理。
 *
 * ## 403 的语义
 *
 * PATCH 的 403 不是「没登录」，而是**账号当前无权修改**（注册未满 60 天等），
 * 抛出 [RejectReason.NSFW_ACCOUNT_TOO_NEW] 的 [FailureException]（`toFailure()` 原样取回），数据层据此把
 * `canSetNsfwSubject` 置 false 并提示，而不是当成网络错误。
 *
 * ## 500 的语义（2026-09-28 实测）
 *
 * 服务端此端点**当前整体不可用**，与请求体形状无关。对真实账号实测：
 * 只带 `preferences`、完整 `settings` + `preferences`（两者都写当前值）、
 * 空 body、仅 `settings`、`preferences` 三字段齐全 —— **全部 500**；
 * 去掉鉴权才回到 401。即 500 发生在服务端处理内部，不是客户端请求体缺字段，补 settings 也修不好。
 * 这里原样抛出（`toFailure()` 是 5xx 的 [Failure.Http]），数据层据此改用网页个人设置页的表单（`NsfwPreferenceRepository`）。
 */
class PrivacyApi(
    private val client: HttpClient,
) {

    /** 当前账号的隐私设置（含 `preferences.allowNsfw` 等服务端判定结果）。 */
    suspend fun getPrivacy(accessToken: String?): PrivacyDto =
        client.get("$NEXT_API_HOST/p1/privacy") { pinSessionToken(accessToken) }.body()

    /**
     * 修改 `show_nsfw_subject`，返回服务端**重算后**的完整隐私设置。
     *
     * 成功后以响应体为准写缓存（不本地臆测 `allowNsfw`：还得满足 60 天等条件，
     * 客户端算不出来）。`canSetNsfwSubject=false` 时服务端返回 403。
     *
     * 5xx 原样抛出，由数据层改走网页；403 是 [RejectReason.NSFW_ACCOUNT_TOO_NEW]。
     */
    suspend fun patchShowNsfw(accessToken: String?, value: Boolean): PrivacyDto =
        try {
            client.patch("$NEXT_API_HOST/p1/privacy") {
                pinSessionToken(accessToken)
                contentType(ContentType.Application.Json)
                setBody(PrivacyPatchDto(preferences = PrivacyPreferencesPatchDto(showNsfwSubject = value)))
            }.body()
        } catch (e: ClientRequestException) {
            if (e.response.status == HttpStatusCode.Forbidden) {
                throw FailureException(Failure.Rejected(RejectReason.NSFW_ACCOUNT_TOO_NEW))
            }
            throw e
        }
}
