package io.github.touhougo.chogumi.core.network.model.web

/**
 * 网页上回复一层楼时表单多带的三项：[postId] 是所在主楼的 id（`related`），[userId] 是那层楼的作者
 * （`sub_reply_uid` 与 `post_uid`，网页按它给对方发提醒）。新开一层不带。
 */
data class WebReplyParent(val postId: Int, val userId: Int)
