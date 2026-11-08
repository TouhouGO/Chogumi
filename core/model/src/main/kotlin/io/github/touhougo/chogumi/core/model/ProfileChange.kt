package io.github.touhougo.chogumi.core.model

/**
 * 自己资料里 App 能改的一项，一次改一项。只有这四项：用户名、个人主页这些与账号安全相关的不在 App 里改。
 * 文字项按 [normalized] 的样子提交和比对：昵称与签名是一行，去掉首尾空白；自我介绍保留换行（统一成 `\n`），去掉首尾空白。
 */
sealed interface ProfileChange {
    data class Nickname(val value: String) : ProfileChange
    data class Sign(val value: String) : ProfileChange
    data class Bio(val value: String) : ProfileChange

    /** 新头像：已在设备上裁成正方形、压好的 JPEG。 */
    class Avatar(val jpeg: ByteArray) : ProfileChange

    /** 文字项提交与比对时的样子（头像原样）。 */
    fun normalized(): ProfileChange = when (this) {
        is Nickname -> Nickname(value.trim())
        is Sign -> Sign(value.trim())
        is Bio -> Bio(profileBioOf(value))
        is Avatar -> this
    }

    /**
     * 回读的资料 [after] 里这一项是不是已经是改后的样子（先 [normalized]）。头像回读不到图片内容，看的是地址变了没有：
     * 服务端每换一次头像就换一个地址（`?r=` 后的时间戳），[avatarBefore] 是提交之前读到的地址。
     */
    fun landedIn(after: UserBo, avatarBefore: String?): Boolean = when (val change = normalized()) {
        is Nickname -> after.nickname == change.value
        is Sign -> after.sign == change.value
        is Bio -> profileBioOf(after.bio) == change.value
        is Avatar -> after.avatarLarge.isNotEmpty() && after.avatarLarge != avatarBefore
    }
}

/** 资料里能改的几项（昵称、签名、自我介绍、头像三档）换成 [source] 的，其余不动：回读的资料落到已显示的资料上。 */
fun UserBo.withProfileOf(source: UserBo): UserBo = copy(
    nickname = source.nickname,
    sign = source.sign,
    bio = source.bio,
    avatarLarge = source.avatarLarge,
    avatarMedium = source.avatarMedium,
    avatarSmall = source.avatarSmall,
)

/** 自我介绍比对与提交的样子：换行统一成 `\n`（服务端回的是 `\r\n`），去掉首尾空白。 */
private fun profileBioOf(text: String): String = text.replace("\r\n", "\n").trim()
