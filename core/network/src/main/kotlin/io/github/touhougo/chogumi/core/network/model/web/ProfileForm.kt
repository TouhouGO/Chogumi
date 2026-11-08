package io.github.touhougo.chogumi.core.network.model.web

/**
 * 要在网页个人设置里改的资料：为 null 的项不动（按页面上现在的值提交）。[avatarJpeg] 是要上传的头像（JPEG 字节）。
 * 用户名、个人主页这些不在这里：App 不改它们。
 */
class ProfileForm(
    val nickname: String? = null,
    val sign: String? = null,
    val bio: String? = null,
    val avatarJpeg: ByteArray? = null,
)
