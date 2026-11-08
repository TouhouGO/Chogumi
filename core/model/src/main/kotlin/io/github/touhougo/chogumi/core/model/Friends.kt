package io.github.touhougo.chogumi.core.model

/**
 * 当前账号关注的人（bgm.tv 的「好友」是单向关注，[ids] 是用户数字 id）。名字旁的「好友」小牌只按它画，名单以服务端为准。
 */
data class Friends(val ids: Set<Int>) {

    /** [userId] 是不是当前账号关注的人：全 App 唯一的判定。用户未知（null）时不是。 */
    fun includes(userId: Int?): Boolean = userId != null && userId in ids

    companion object {
        /** 谁也没关注：游客，或这个账号的名单还没读到。 */
        val NONE = Friends(emptySet())
    }
}
