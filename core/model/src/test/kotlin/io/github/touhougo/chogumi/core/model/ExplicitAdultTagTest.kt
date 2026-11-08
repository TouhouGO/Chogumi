package io.github.touhougo.chogumi.core.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 成人向词表的唯一判定函数。
 *
 * 用例分五组：
 * 1. **每个收录词都命中**——三档匹配（整名 / 子串 / 切词）；
 * 2. **组合写法命中**——`3D里番`/`HentaiAnime`/`成年コミック`/`ERO-RPG` 这类靠子串与切词覆盖；
 * 3. **有歧义的词不命中**——2026-09-30 实测点名的 5 个；
 * 4. **题材类词不命中**——`BL`/`耽美` 等是设定而非成人向判定；
 * 5. **实测真实假阳性不命中**——2026-09-30 扫目录时被子串/裸词规则误伤的正常标签，
 *    正是「整名匹配」与「切词匹配」两条规则的由来。
 *
 * 数据依据：2026-09-30 的标签目录实测与人工复核。
 */
class ExplicitAdultTagTest {

    @Test
    fun `整名收录词全部命中`() {
        val exact = listOf(
            // 年龄分级
            "R18", "R-18", "R18G", "R-18G", "18禁", "18X", "18x", "18+",
            // 性内容题材
            "NTR", "寝取", "调教", "凌辱", "淫乱",
            // 性暗示题材
            "卖肉", "肉番",
            // 直白成人向
            "成人", "成人向", "成人漫画", "成人动画",
            // 直白色情
            "色情", "涩情",
            // 作品/商品类型
            "本子", "無修", "无修", "eroge",
        )
        exact.forEach { assertTrue("未命中：$it", isExplicitAdultTag(it)) }
    }

    @Test
    fun `切词收录词命中`() {
        // `ero` 必须以整词出现：ERO-RPG/ERO-GAL/ero 命中。
        listOf("ero", "ERO-RPG", "ERO-GAL", "ERO-ADV", "Eroge", "EROGE").forEach {
            assertTrue("未命中：$it", isExplicitAdultTag(it))
        }
    }

    @Test
    fun `实测目录里的组合写法全部命中`() {
        listOf(
            // 动画
            "里番", "3D里番", "泡面里番", "2D里番", "同人里番", "步兵里番",
            "里番未评分", "2018里番", "20世纪里番", "旧里番", "2.5D里番",
            "無修正", "无修正里", "3DHentai", "HentaiAnime", "工口小学生", "エロ",
            // 书籍
            "成年コミック", "无修正本",
            // 游戏
            "拔作", "拔作(笑)", "披着拔作外衣的剧情作", "抜きゲー", "エロゲー",
        ).forEach { assertTrue("未命中组合写法：$it", isExplicitAdultTag(it)) }
    }

    @Test
    fun `大小写与首尾空白不影响判定`() {
        assertTrue(isExplicitAdultTag("  r18  "))
        assertTrue(isExplicitAdultTag("ntr"))
        assertTrue(isExplicitAdultTag("HENTAI"))
        assertTrue(isExplicitAdultTag("r-18g"))
        assertTrue(isExplicitAdultTag("Eroge"))
    }

    @Test
    fun `有歧义的词不命中`() {
        // 调查报告点名「需结合作品判断」的 5 个：中文语境里都常用于正常向作品。
        listOf("猎奇", "熟女", "魅魔", "鬼畜", "里").forEach {
            assertFalse("歧义词被误判：$it", isExplicitAdultTag(it))
        }
    }

    @Test
    fun `题材类词不命中`() {
        // BL/耽美/性转/触手/女装/痴汉 是题材与角色设定，不是成人向判定。
        listOf("BL", "耽美", "耽美系", "性转", "性转换", "触手", "女装", "痴汉").forEach {
            assertFalse("题材词被误判：$it", isExplicitAdultTag(it))
        }
    }

    @Test
    fun `实测真实假阳性不命中`() {
        // 2026-09-30 扫五个类型目录时，下面这些正常标签被子串或裸词规则误伤，
        // 正是「整名匹配」与「切词匹配」两条规则的由来。
        listOf(
            "LapinTrack",  // 含 ntr
            "调教咖啡厅",       // 含 调教
            "成人童话",        // 含 成人
            "成年小女警",       // 含 成年（但非 成年コミック）
            "ZERO-G",       // 含 ero，但切词为 zero/g
            "Keroro",       // 含 ero，但切词为 keroro
            "OneRoom",      // 含 ero，但切词为 oneroom
            "Xenoblade",    // 含 bl
            "BLEACH",       // 含 bl
            "ufotable",     // 含 bl
            "GHIBLI",       // 含 bl
        ).forEach { assertFalse("正常标签被误判：$it", isExplicitAdultTag(it)) }
    }

    @Test
    fun `普通标签不命中`() {
        listOf("TV", "漫画改", "剧场版", "百合", "京阿尼", "校园", "纯爱", "2023", "异世界", "").forEach {
            assertFalse("正常标签被误判：$it", isExplicitAdultTag(it))
        }
    }
}
