package io.github.touhougo.chogumi.core.res

import io.github.touhougo.chogumi.core.foundation.testing.RepoFiles
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class StringsLocaleContractTest {

    @Test
    fun `默认资源为简体中文且对齐Bangumi用词`() {
        val strings = defaultStrings()
        assertEquals("近期热门", strings["trending"])
        assertEquals("排行榜", strings["ranking"])
        assertEquals("动画", strings["anime"])
        assertEquals("书籍", strings["book"])
        assertEquals("游戏", strings["game"])
        assertEquals("音乐", strings["music"])
        assertEquals("三次元", strings["real"])
        assertEquals("%1\$d 人在看", strings["calendar_watchers"])
    }

    @Test
    fun `两份资源key集合一致且占位符一致`() {
        val zh = defaultStrings()
        val en = englishStrings()
        assertEquals(zh.keys.sorted(), en.keys.sorted())

        zh.keys.forEach { key ->
            assertEquals("$key 占位符不一致", placeholders(zh.getValue(key)), placeholders(en.getValue(key)))
        }
    }

    /** 复数串同样两份齐备；每一档（中文只有 other，英文有 one / other）的占位符都和中文那档一致。 */
    @Test
    fun `两份复数资源key集合一致且每档占位符一致`() {
        val zh = defaultPlurals()
        val en = englishPlurals()
        assertTrue("没有解析到任何复数串，守卫失效", zh.isNotEmpty())
        assertEquals(zh.keys.sorted(), en.keys.sorted())

        zh.forEach { (key, zhItems) ->
            val expected = placeholders(zhItems.getValue("other"))
            for ((language, items) in listOf("中文" to zhItems, "英文" to en.getValue(key))) {
                items.forEach { (quantity, text) ->
                    assertEquals("$key 的 $language $quantity 档占位符不一致", expected, placeholders(text))
                }
            }
            assertTrue("$key 的英文缺 other 档", "other" in en.getValue(key))
        }
    }

    /**
     * 英文里「数字 + 名词」的文案按数量变形（1 comment / 2 comments）：数量是个变量的串必须写成 `<plurals>`。
     * 放行清单里的数量有固定下限或上限，不会是 1。
     */
    @Test
    fun `英文里数字加名词的文案不写成固定的复数串`() {
        val fixedQuantity = setOf(
            // 至少两个条件才出现
            "search_hint_multi_tag",
            // 输入长度下限与标签个数上限，都是常量
            "collection_tag_too_short",
            "collection_tags_full",
            // 只有 2 天及以上才用这条
            "day_offset_days",
            // 超展开列表固定只留最近一批
            "rakuen_list_end",
            // 分数下限：「少于 N 人评分」，人数 N 在句中是门槛不是计数
            "mono_sort_rating_hint",
            // 数字后面跟的是动词「unmarks」，没有名词
            "episode_jump_mixed_text",
            // 只在要改的条数超过目录写入上限时出现（大于 15），上限是常量
            "index_reorder_too_many",
        )
        val countNoun = Regex("%\\d+\\$" + "d (?:[a-z]+ )?[a-z]+s\\b")
        val offenders = englishStrings().filter { (key, value) -> key !in fixedQuantity && countNoun.containsMatchIn(value) }.keys
        assertEquals("这些英文文案是「数字 + 复数名词」，数量为 1 时语法不对，请改成 plurals：$offenders", emptySet<String>(), offenders)
    }

    /** 作品、角色、人物、用户都不用代词指代（它 / 他 / 她 / TA）：他人主页称呼主人用昵称。「其他」「其它」「他人」不是代词。 */
    @Test
    fun `中文文案不用代词指代作品与用户`() {
        val pronoun = Regex("(?<![A-Za-z])TA(?![A-Za-z])|(?<!其)它|(?<!其)他(?!人)|她")
        val offending = defaultStrings().filterValues { pronoun.containsMatchIn(it) }
        assertEquals("这些文案用了代词：$offending", emptyMap<String, String>(), offending)
    }

    /** 中文的带限定符目录（`values-zh`）由构建从 `values/` 生成（build-logic 的 `DefaultLocaleStrings.kt`），哪个模块都不手写第二份。 */
    @Test
    fun `中文资源目录不手写`() {
        val handWritten = RepoFiles.sourceFiles("xml").mapNotNull { it.parentFile }.filter { it.name.startsWith("values-zh") }
        assertEquals(emptyList<String>(), handWritten.map { RepoFiles.relativePath(it) }.distinct())
    }

    /**
     * 按条目类型分动词的收藏状态键必须齐备，且**动画组的文案与通用词逐字相同**。
     *
     * 通用词 `collection_wish`（想看）与按类型的 `collection_wish_anime`（想看）是两份键，
     * 动画 / 三次元的显示与通用词各用各的键。若哪天有人改了一处忘了另一处，
     * 同一个"想看"会在不同界面显示成两样，这条会红。
     *
     * 书籍/音乐/游戏组**必须**与通用词不同（想读/想听/想玩）——相同就说明键写错了。
     */
    @Test
    fun `按条目类型分动词的收藏键齐备且动画组与通用词一致`() {
        val zh = defaultStrings()
        val en = englishStrings()
        val groups = mapOf(
            // 状态通用键 → (书籍键, 音乐键, 游戏键)
            "collection_wish" to Triple("collection_wish_book", "collection_wish_music", "collection_wish_game"),
            "collection_done" to Triple("collection_done_book", "collection_done_music", "collection_done_game"),
            "collection_doing" to Triple("collection_doing_book", "collection_doing_music", "collection_doing_game"),
        )
        listOf(zh, en).forEach { strings ->
            groups.forEach { (generic, typeSpecific) ->
                val (book, music, game) = typeSpecific
                listOf(book, music, game).forEach {
                    assertTrue("缺少键 $it", strings.containsKey(it))
                }
                assertTrue("缺少键 ${generic}_anime", strings.containsKey("${generic}_anime"))
                // 书籍/音乐/游戏三组动词互不相同（想读/想听/想玩）
                val trio = listOf(strings.getValue(book), strings.getValue(music), strings.getValue(game))
                assertEquals("$generic 的三个分类型键文案重复", 3, trio.toSet().size)
            }
        }
        // 中文下动画组与通用词同义（都是"想看/看过/在看"）：两组独立键，文案必须一致，
        // 否则同一个状态在筛选菜单与徽章上会显示成两样。
        assertEquals(zh.getValue("collection_wish"), zh.getValue("collection_wish_anime"))
        assertEquals(zh.getValue("collection_done"), zh.getValue("collection_done_anime"))
        assertEquals(zh.getValue("collection_doing"), zh.getValue("collection_doing_anime"))
    }

    /**
     * 默认 locale 是**简体中文**，因此除专有名词外不应出现纯 ASCII 文案。
     *
     * 上面几条测试只钉住少量具名键的取值，**其他**键被写成英文（如把 collection_doing
     * 写成 "Doing"）它们都看不出来；这一条覆盖默认资源里的每一个键。
     *
     * 注意覆盖边界：只认"纯 ASCII 且无中文"。像 `Doing · 48` 这种中英混排
     * 仍会漏过，那类需要靠 UI 层的断言，不在这里解决。
     */
    @Test
    fun `默认中文资源不应出现未翻译的纯英文文案`() {
        // 第三方库与素材的项目名是专有名称，两种语言都保持原名。
        val thirdPartyNames = mapOf(
            "about_third_party_coil" to "Coil",
            "about_third_party_koin" to "Koin",
            "about_third_party_material_symbols" to "Material Symbols",
            "about_third_party_material_kolor" to "Material Kolor",
            "about_third_party_colormath" to "Colormath",
            "about_third_party_jsoup" to "jsoup",
            "about_third_party_slf4j" to "SLF4J",
            "about_third_party_desugar" to "desugar_jdk_libs",
        )
        // 放行清单只列天然是 ASCII 的词：品牌名、行业通用标记、通用称呼。只有占位符、数字与标点的串（「%1$d / %2$d」「+%1$d」）
        // 没有可翻的字，按规则放行（[isFormatOnly]），不进清单
        val allowed = setOf(
            "app_name",
            // OP / ED / MAD / SP 是动画行业与 Bangumi 全站通用的章节类型标记，不译
            "episode_type_sp",
            "episode_type_op",
            "episode_type_ed",
            "episode_type_mad",
            // 动态里的集号「EP 12」与 Bangumi 网页同写法
            "timeline_episode_single",
            // 声优标记「CV 名字」：日本动画通行写法，不译
            "detail_character_cv",
            // 表情分组名：next.bgm.tv 表情面板的官方分组标题（素材集名 + 作者），不译
            "smiley_family_tv",
            "smiley_family_classic",
            // 界面大小的读数「411 dp」：dp 是安卓的长度单位名，系统开发者选项里的「最小宽度」也这样写
            "settings_display_scale_width",
            // 刷新率的读数「120 Hz」：Hz 是国际单位符号，系统设置里的「屏幕刷新率」也这样写
            "settings_refresh_rate_hz",
        ) + thirdPartyNames.keys
        val strings = defaultStrings()
        assertEquals("放行清单里有已经不存在的键（删了字符串就把它从清单删掉）", emptySet<String>(), allowed - strings.keys)
        assertEquals("第三方项目名应保持原名", thirdPartyNames, strings.filterKeys { it in thirdPartyNames })
        assertEquals("英文第三方项目名应保持原名", thirdPartyNames, englishStrings().filterKeys { it in thirdPartyNames })
        val offenders = strings
            .filterKeys { it !in allowed }
            .filterValues { value -> value.isNotBlank() && value.all { it.code < 128 } && !isFormatOnly(value) }
        assertTrue(
            "以下键在默认（中文）资源里是纯英文，疑似漏翻译：$offenders",
            offenders.isEmpty(),
        )
    }

    /**
     * 没有**死键**：每个字符串都必须真的被引用。
     *
     * @string 是编译期常量，Kotlin 里写错键名编不过，所以死键**不会**是笔误，
     * 只会是"界面/结构改过之后留下的旧副本"——删掉一个分组，它的标题键就再没人引用了。
     * 这类残留会骗人：下一个读代码的人会以为它还在用。
     *
     * 覆盖范围：整个仓库的 Kotlin、Java 与 XML（`RepoFiles.sourceFiles`，**不含** `.reference/` 这类隐藏目录
     * 与各 `build/` 目录）。应用本体没有 `getIdentifier` 这类动态查表（只有 baselineprofile 的 `StartupJourney` 按名字查被测应用的字符串，
     * 它查的键同时被界面引用），因此"源码里搜不到即死键"是可靠的；若将来引入动态查找，这条要先改。
     */
    @Test
    fun `资源没有死键即每个字符串都真的被引用`() {
        val keys = defaultStrings().keys.map { "string/$it" } + defaultPlurals().keys.map { "plurals/$it" }
        val used = mutableSetOf<String>()
        val pattern = Regex("""R\.(string|plurals)\.(\w+)|@(string|plurals)/(\w+)""")
        var scanned = 0

        RepoFiles.sourceFiles("kt", "java", "xml")
            // 资源文件自身不算引用
            .filterNot { it.parentFile?.name?.startsWith("values") == true }
            .forEach { file ->
                scanned++
                pattern.findAll(file.readText()).forEach { m ->
                    used += if (m.groupValues[1].isNotEmpty()) "${m.groupValues[1]}/${m.groupValues[2]}" else "${m.groupValues[3]}/${m.groupValues[4]}"
                }
            }

        assertTrue("未扫描到任何源文件，守卫失效", scanned > 50)
        val dead = keys - used
        assertTrue(
            "以下资源键已无人引用，属于旧结构残留，应删除：${dead.sorted()}",
            dead.isEmpty(),
        )
    }

    /** 只有占位符、数字与标点，没有可翻的字母：「%1$d / %2$d」「+%1$d」「%1$s %2$d%%」。 */
    private fun isFormatOnly(value: String): Boolean = value.replace(Regex("%(\\d+\\$)?[sd]"), "").none { it.isLetter() }

    private fun defaultStrings() = parseStrings(File(resDir(), "values/strings.xml"))
    private fun englishStrings() = parseStrings(File(resDir(), "values-en/strings.xml"))

    private fun defaultPlurals() = parsePlurals(File(resDir(), "values/strings.xml"))
    private fun englishPlurals() = parsePlurals(File(resDir(), "values-en/strings.xml"))

    private fun resDir(): File = RepoFiles.file("core/res/src/main/res")

    /** 参数编号和类型都要一致；翻译可调整顺序，但不能把两个参数误写成同一个。 */
    private fun placeholders(text: String): List<Pair<Int, String>> =
        Regex("%(\\d+)\\$[-#+ 0,(]*\\d*(?:\\.\\d+)?([a-zA-Z])").findAll(text)
            .map { it.groupValues[1].toInt() to it.groupValues[2] }
            .sortedWith(compareBy<Pair<Int, String>> { it.first }.thenBy { it.second })
            .toList()

    /** 复数串：名字 → (档位 → 文案)。 */
    private fun parsePlurals(file: File): Map<String, Map<String, String>> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("plurals")
        return buildMap {
            for (i in 0 until nodes.length) {
                val plural = nodes.item(i) as Element
                val items = plural.getElementsByTagName("item")
                put(
                    plural.getAttribute("name"),
                    (0 until items.length).associate { j ->
                        val item = items.item(j) as Element
                        item.getAttribute("quantity") to item.textContent
                    },
                )
            }
        }
    }

    private fun parseStrings(file: File): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        return buildMap {
            for (i in 0 until nodes.length) {
                val e = nodes.item(i) as Element
                put(e.getAttribute("name"), e.textContent)
            }
        }
    }
}
