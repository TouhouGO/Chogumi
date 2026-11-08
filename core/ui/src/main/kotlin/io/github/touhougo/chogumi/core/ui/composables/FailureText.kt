package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.runtime.Composable
import io.github.touhougo.chogumi.core.common.notice.NoticeText
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.OAuthError
import io.github.touhougo.chogumi.core.foundation.RejectReason
import io.github.touhougo.chogumi.core.res.R

/**
 * 失败原因 → 文案（字符串资源 + 参数），**全仓唯一的映射**。界面用 [text]；数据层与 ViewModel 只传 [Failure]。
 *
 * OAuth / p1 错误码比状态码更具体，先看它；`invalid_request` / `unauthorized` 与对应状态码同义，落到状态码文案。
 */
fun Failure.notice(): NoticeText.Resource = when (this) {
    Failure.Offline -> NoticeText.Resource(R.string.failure_offline)
    Failure.Timeout -> NoticeText.Resource(R.string.failure_timeout)
    Failure.Interrupted -> NoticeText.Resource(R.string.failure_interrupted)
    is Failure.Http -> oauthNotice(oauth) ?: statusNotice(status)
    Failure.Storage -> NoticeText.Resource(R.string.failure_storage)
    Failure.BadResponse -> NoticeText.Resource(R.string.failure_bad_response)
    Failure.NotLoggedIn -> NoticeText.Resource(R.string.failure_not_logged_in)
    Failure.SessionChanged -> NoticeText.Resource(R.string.failure_session_changed)
    is Failure.Rejected -> when (reason) {
        RejectReason.NSFW_ACCOUNT_TOO_NEW -> NoticeText.Resource(R.string.failure_rule_nsfw_account_too_new)
        RejectReason.PRIVACY_SETTING_UNAVAILABLE -> NoticeText.Resource(R.string.failure_rule_privacy_setting_unavailable)
        RejectReason.SUBJECT_UNCOLLECT_UNAVAILABLE -> NoticeText.Resource(R.string.failure_rule_subject_uncollect_unavailable)
        RejectReason.MONO_UNCOLLECT_UNAVAILABLE -> NoticeText.Resource(R.string.failure_rule_mono_uncollect_unavailable)
        RejectReason.UNFRIEND_UNAVAILABLE -> NoticeText.Resource(R.string.failure_rule_unfriend_unavailable)
        RejectReason.TIMELINE_DELETE_UNAVAILABLE -> NoticeText.Resource(R.string.failure_rule_timeline_delete_unavailable)
        RejectReason.BLOCK_UNAVAILABLE -> NoticeText.Resource(R.string.failure_rule_block_unavailable)
        RejectReason.GROUP_MEMBERSHIP_UNAVAILABLE -> NoticeText.Resource(R.string.failure_rule_group_membership_unavailable)
        RejectReason.PM_RECEIVER_NOT_FOUND -> NoticeText.Resource(R.string.failure_rule_pm_receiver_not_found)
        RejectReason.PM_NOT_SENT -> NoticeText.Resource(R.string.failure_rule_pm_not_sent)
        RejectReason.PM_DELETE_UNAVAILABLE -> NoticeText.Resource(R.string.failure_rule_pm_delete_unavailable)
        RejectReason.WEB_LOGIN_REQUIRED -> NoticeText.Resource(R.string.failure_rule_web_login_required)
        RejectReason.LOGIN_CAPTCHA_WRONG -> NoticeText.Resource(R.string.failure_rule_login_captcha_wrong)
        RejectReason.LOGIN_CREDENTIALS_WRONG -> NoticeText.Resource(R.string.failure_rule_login_credentials_wrong)
        RejectReason.LOGIN_TOO_MANY_ATTEMPTS -> NoticeText.Resource(R.string.failure_rule_login_too_many_attempts)
        RejectReason.LOGIN_FAILED -> NoticeText.Resource(R.string.failure_rule_login_failed)
        RejectReason.LOGIN_CALLBACK_MISMATCH -> NoticeText.Resource(R.string.failure_rule_login_callback_mismatch)
        RejectReason.VERIFICATION_FAILED -> NoticeText.Resource(R.string.failure_rule_verification_failed)
        RejectReason.POSTING_NOT_ALLOWED -> NoticeText.Resource(R.string.failure_rule_posting_not_allowed)
        RejectReason.EDIT_NOT_ALLOWED -> NoticeText.Resource(R.string.failure_rule_edit_not_allowed)
        RejectReason.GROUP_MEMBERS_ONLY -> NoticeText.Resource(R.string.failure_rule_group_members_only)
        RejectReason.IMAGE_UNREADABLE -> NoticeText.Resource(R.string.failure_rule_image_unreadable)
        RejectReason.UPDATE_PACKAGE_MISMATCH -> NoticeText.Resource(R.string.failure_rule_update_package_mismatch)
    }
    Failure.Unknown -> NoticeText.Resource(R.string.failure_unknown)
}

/** 按当前语言取出失败文案。 */
@Composable
fun Failure.text(): String = notice().resolve()

private fun oauthNotice(error: OAuthError?): NoticeText.Resource? = when (error) {
    OAuthError.INVALID_GRANT -> NoticeText.Resource(R.string.failure_oauth_invalid_grant)
    OAuthError.INVALID_CLIENT -> NoticeText.Resource(R.string.failure_oauth_invalid_client)
    OAuthError.ACCESS_DENIED -> NoticeText.Resource(R.string.failure_oauth_access_denied)
    OAuthError.INVALID_REQUEST, OAuthError.UNAUTHORIZED, null -> null
}

private fun statusNotice(status: Int): NoticeText.Resource = when (status) {
    400 -> NoticeText.Resource(R.string.failure_http_bad_request)
    401 -> NoticeText.Resource(R.string.failure_http_unauthorized)
    403 -> NoticeText.Resource(R.string.failure_http_forbidden)
    404 -> NoticeText.Resource(R.string.failure_http_not_found)
    429 -> NoticeText.Resource(R.string.failure_http_too_many_requests)
    in 300..399 -> NoticeText.Resource(R.string.failure_http_redirect, listOf(status))
    in 500..599 -> NoticeText.Resource(R.string.failure_http_server, listOf(status))
    else -> NoticeText.Resource(R.string.failure_http_other, listOf(status))
}
