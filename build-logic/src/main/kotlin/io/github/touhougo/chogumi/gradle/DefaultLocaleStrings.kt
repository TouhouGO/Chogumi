package io.github.touhougo.chogumi.gradle

import com.android.build.api.variant.AndroidComponentsExtension
import java.io.File
import java.io.StringReader
import java.io.StringWriter
import java.util.Properties
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.register
import org.w3c.dom.Element

/** 默认资源（`values/`）语言的唯一声明：AGP 生成应用语言清单读的是它，这里生成中文目录读的也是它。 */
private const val RESOURCES_PROPERTIES = "app/src/main/res/resources.properties"
private const val UNQUALIFIED_RES_LOCALE = "unqualifiedResLocale"

/**
 * 每个 Android 模块：把变体里手写的 `values/strings.xml` 原样再发布一份 `values-<默认语言>/strings.xml`，生成到 build/。
 *
 * 字符串只手写两份：`values/`（简体中文，默认）与 `values-en/`。Android 7 起按系统语言列表里**第一个有资源的语言**选资源，
 * 默认资源不算任何语言的资源：只有 `values-en` 时，系统语言「中文、English」的用户看到的是英文。中文有了带限定符的目录，
 * 列表里排前面的中文就被选中；其余语言仍没有目录，落回默认的中文（界面文字与日期写法一致，见 core:ui `Format.kt`）。
 *
 * 默认语言只声明在 [RESOURCES_PROPERTIES]，目录名由它推出。一个变体的几个源集（main、debug…）各有一份 `values/strings.xml`
 * 时按 AGP 合并源集的规则并成一份：同名资源以优先级高的源集为准。生成的目录与 AGP 自己的应用语言清单一样进资源合并，
 * 所以单测的资源、AAR 与 APK 里都有它。
 */
internal fun Project.configureDefaultLocaleStrings() {
    val defaultLocale = providers.fileContents(rootProject.layout.projectDirectory.file(RESOURCES_PROPERTIES))
        .asText.orElse("").map(::unqualifiedResLocaleOf)
    // 应用与库各注册自己的子类型，按类型找不到共同的父接口，按名字取
    (extensions.getByName("androidComponents") as AndroidComponentsExtension<*, *, *>).onVariants { variant ->
        val res = variant.sources.res ?: return@onVariants
        val task = tasks.register<MirrorDefaultLocaleStrings>(
            "mirror${variant.name.replaceFirstChar(Char::uppercase)}DefaultLocaleStrings",
        ) {
            // static 的外层列表优先级高的在前；合并时后读的赢，所以倒过来
            sources.from(res.static.map { layers -> layers.reversed().flatten().map { it.file("values/strings.xml") } })
            locale.set(defaultLocale)
        }
        res.addGeneratedSourceDirectory(task, MirrorDefaultLocaleStrings::outputDir)
    }
}

/** `unqualifiedResLocale` 的值；目录名 `values-<它>` 只支持纯语言码，别的写法直接报错，不猜。 */
private fun unqualifiedResLocaleOf(propertiesText: String): String {
    val locale = Properties().apply { load(StringReader(propertiesText)) }.getProperty(UNQUALIFIED_RES_LOCALE).orEmpty()
    if (!Regex("[a-z]{2,3}").matches(locale)) {
        throw GradleException("$RESOURCES_PROPERTIES 里的 $UNQUALIFIED_RES_LOCALE 必须是两三个小写字母的语言码（如 zh），现在是「$locale」")
    }
    return locale
}

/** 见 [configureDefaultLocaleStrings]。 */
@CacheableTask
abstract class MirrorDefaultLocaleStrings : DefaultTask() {

    /** 变体各静态资源目录下的 `values/strings.xml`，优先级低的在前；不存在的略过。 */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    /** 默认资源的语言码：生成的目录是 `values-<它>`。 */
    @get:Input
    abstract val locale: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun mirror() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        val files = sources.files.filter { it.isFile }
        if (files.isEmpty()) return
        File(out, "values-${locale.get()}/strings.xml").apply { parentFile.mkdirs() }.writeText(mergedResources(files))
    }
}

/**
 * 把几份资源文件按名字并成一份：同名（同类型）的资源以后面的文件为准。取值与属性原样带走，
 * 根元素上的命名空间声明也带走；注释与排版不带。
 */
private fun mergedResources(files: List<File>): String {
    val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
    val merged = builder.newDocument()
    val root = merged.createElement("resources").also(merged::appendChild)
    val byName = LinkedHashMap<String, Element>()
    files.forEach { file ->
        val source = builder.parse(file).documentElement
        val attributes = source.attributes
        for (i in 0 until attributes.length) {
            val attribute = attributes.item(i)
            if (attribute.nodeName.startsWith("xmlns")) root.setAttribute(attribute.nodeName, attribute.nodeValue)
        }
        val children = source.childNodes
        for (i in 0 until children.length) {
            val element = children.item(i) as? Element ?: continue
            val type = element.getAttribute("type").ifEmpty { element.tagName }
            byName["$type/${element.getAttribute("name")}"] = merged.importNode(element, true) as Element
        }
    }
    byName.values.forEach {
        root.appendChild(merged.createTextNode("\n    "))
        root.appendChild(it)
    }
    root.appendChild(merged.createTextNode("\n"))
    val writer = StringWriter()
    TransformerFactory.newInstance().newTransformer()
        .apply { setOutputProperty(OutputKeys.ENCODING, "UTF-8") }
        .transform(DOMSource(merged), StreamResult(writer))
    return writer.toString()
}
