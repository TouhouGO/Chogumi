package io.github.touhougo.chogumi.core.model

/**
 * 一份没发出去的话题回复（作品讨论版、小组）：写给谁、写了什么。只存在本机，发出成功、或正文清空时删掉。
 * 同一个话题里回复同一层楼只留一份（[topicType] + [topicId] + [postId] 是它的键）。
 *
 * 回复对象（界面层的 `ReplyTarget`）原样存在 [replyTo] 到 [quote] 这几项里：从「我的帖子」打开草稿时直接还原，
 * 不必等楼层取回再重算。
 *
 * @property topicTitle 话题标题，草稿列表里认是哪一帖。
 * @property postId 回复的是哪一层楼；0 = 回复话题本身。
 * @property replyTo 新回复挂在哪层主楼下；0 = 新开一层。
 * @property floor 被回复楼的楼层号（「#6」「#6-2」）；回复话题时为 null。
 * @property quoteAuthor 被回复的人；回复话题时为 null。
 * @property quoteExcerpt 被回复那层楼的摘要（编辑器顶上的引用条）。
 * @property quote 写在正文开头的引用块；只有回复楼中楼时有。
 * @property updatedAt 最后一次改动，epoch 毫秒（存草稿时使用调用方捕获的操作时刻）；草稿列表新的在前。
 */
data class TopicReplyDraft(
    val topicType: TopicType,
    val topicId: Int,
    val topicTitle: String,
    val postId: Int,
    val replyTo: Int,
    val floor: String?,
    val quoteAuthor: String?,
    val quoteExcerpt: String?,
    val quote: String?,
    val text: String,
    val updatedAt: Long,
)
