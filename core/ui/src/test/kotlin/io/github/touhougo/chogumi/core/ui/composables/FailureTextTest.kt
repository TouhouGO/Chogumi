package io.github.touhougo.chogumi.core.ui.composables

import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.OAuthError
import io.github.touhougo.chogumi.core.foundation.RejectReason
import io.github.touhougo.chogumi.core.common.notice.NoticeText
import io.github.touhougo.chogumi.core.res.R
import kotlin.test.Test
import kotlin.test.assertEquals

/** 失败原因 → 文案资源（[notice]）：全仓唯一的映射，每一支一个样例。 */
class FailureTextTest {

    @Test
    fun `传输层与本地原因各有一句`() {
        assertEquals(NoticeText.Resource(R.string.failure_offline), Failure.Offline.notice())
        assertEquals(NoticeText.Resource(R.string.failure_timeout), Failure.Timeout.notice())
        assertEquals(NoticeText.Resource(R.string.failure_interrupted), Failure.Interrupted.notice())
        assertEquals(NoticeText.Resource(R.string.failure_bad_response), Failure.BadResponse.notice())
        assertEquals(NoticeText.Resource(R.string.failure_not_logged_in), Failure.NotLoggedIn.notice())
        assertEquals(NoticeText.Resource(R.string.failure_session_changed), Failure.SessionChanged.notice())
        assertEquals(NoticeText.Resource(R.string.failure_unknown), Failure.Unknown.notice())
    }

    @Test
    fun `常见状态码有专门文案 其余按区段带上状态码`() {
        assertEquals(NoticeText.Resource(R.string.failure_http_bad_request), Failure.Http(400).notice())
        assertEquals(NoticeText.Resource(R.string.failure_http_unauthorized), Failure.Http(401).notice())
        assertEquals(NoticeText.Resource(R.string.failure_http_forbidden), Failure.Http(403).notice())
        assertEquals(NoticeText.Resource(R.string.failure_http_not_found), Failure.Http(404).notice())
        assertEquals(NoticeText.Resource(R.string.failure_http_too_many_requests), Failure.Http(429).notice())
        assertEquals(NoticeText.Resource(R.string.failure_http_redirect, listOf(302)), Failure.Http(302).notice())
        assertEquals(NoticeText.Resource(R.string.failure_http_server, listOf(503)), Failure.Http(503).notice())
        assertEquals(NoticeText.Resource(R.string.failure_http_other, listOf(418)), Failure.Http(418).notice())
    }

    @Test
    fun `认得出的 OAuth 错误码先于状态码 同义的落回状态码`() {
        assertEquals(NoticeText.Resource(R.string.failure_oauth_invalid_grant), Failure.Http(400, OAuthError.INVALID_GRANT).notice())
        assertEquals(NoticeText.Resource(R.string.failure_oauth_invalid_client), Failure.Http(401, OAuthError.INVALID_CLIENT).notice())
        assertEquals(NoticeText.Resource(R.string.failure_oauth_access_denied), Failure.Http(400, OAuthError.ACCESS_DENIED).notice())
        assertEquals(NoticeText.Resource(R.string.failure_http_unauthorized), Failure.Http(401, OAuthError.UNAUTHORIZED).notice())
        assertEquals(NoticeText.Resource(R.string.failure_http_bad_request), Failure.Http(400, OAuthError.INVALID_REQUEST).notice())
    }

    @Test
    fun `业务拒绝给可操作的说明`() {
        assertEquals(
            NoticeText.Resource(R.string.failure_rule_nsfw_account_too_new),
            Failure.Rejected(RejectReason.NSFW_ACCOUNT_TOO_NEW).notice(),
        )
        assertEquals(
            NoticeText.Resource(R.string.failure_rule_privacy_setting_unavailable),
            Failure.Rejected(RejectReason.PRIVACY_SETTING_UNAVAILABLE).notice(),
        )
    }
}
