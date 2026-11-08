package io.github.touhougo.chogumi.core.model.statistics

/**
 * 动漫题材分类：依据 Bangumi 平台公共标签规范，把标签归到标准题材（后宫、搞笑、日常、战斗等核心流派）。
 */
object AnimeTaxonomy {

    /** 标准题材流派及其严格同义别名。 */
    private val GENRE_MAPPINGS: Map<String, List<String>> = mapOf(
        "搞笑" to listOf("搞笑", "喜剧", "沙雕", "幽默", "爆笑"),
        "后宫" to listOf("后宫", "逆后宫"),
        "恋爱" to listOf("恋爱", "爱情", "纯爱", "少女爱情", "青春恋爱"),
        "日常" to listOf("日常", "轻松"),
        "战斗" to listOf("战斗", "热血", "动作", "格斗"),
        "奇幻" to listOf("奇幻", "魔幻", "神魔"),
        "科幻" to listOf("科幻", "SF"),
        "悬疑" to listOf("悬疑", "推理", "惊悚", "心理"),
        "治愈" to listOf("治愈", "温情", "治愈系"),
        "致郁" to listOf("致郁", "催泪", "胃痛", "黑深残", "悲剧"),
        "百合" to listOf("百合", "轻百合"),
        "耽美" to listOf("耽美", "BL"),
        "校园" to listOf("校园", "学园", "青春校园"),
        "冒险" to listOf("冒险"),
        "运动" to listOf("运动", "体育", "竞技"),
        "音乐" to listOf("音乐", "偶像", "乐队"),
        "美食" to listOf("美食", "料理"),
        "历史" to listOf("历史", "时代剧", "古风"),
    )

    /** 别名/标签名到标准流派的正向快速索引 */
    private val TAG_TO_GENRE: Map<String, String> = buildMap {
        for ((genre, aliases) in GENRE_MAPPINGS) {
            put(genre, genre)
            for (alias in aliases) {
                put(alias, genre)
                put(alias.lowercase(), genre)
                put(alias.uppercase(), genre)
            }
        }
    }

    /**
     * 从一组标签中提取出命中的标准题材流派（去重）。
     */
    fun extractGenres(tags: Iterable<String>): Set<String> {
        val result = mutableSetOf<String>()
        for (tag in tags) {
            val trimmed = tag.trim()
            if (trimmed.isEmpty()) continue
            val genre = TAG_TO_GENRE[trimmed] ?: TAG_TO_GENRE[trimmed.lowercase()]
            if (genre != null) {
                result.add(genre)
            }
        }
        return result
    }
}
