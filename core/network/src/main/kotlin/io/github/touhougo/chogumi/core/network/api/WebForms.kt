package io.github.touhougo.chogumi.core.network.api

import io.github.touhougo.chogumi.core.foundation.BGM_SITE
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.FailureException
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.FormElement

/**
 * 网页 HTML → 文档树，**全仓唯一读整页网页的办法**：网页上的结构（表单、链接、短信会话）都从这棵树上按元素与样式类取，
 * 不用正则去匹配标签（取出的短信正文片段由 core:common 的 `bbcodeOfHtml` 还原成 BBCode）。属性值里的实体（`&amp;`）由解析器还原，取出来就是浏览器看到的值。
 * 输出不重排（`prettyPrint` 关掉）：取出的 HTML 片段（短信正文）里的空白与换行和网页上一样。
 */
internal fun webDocumentOf(html: String): Document = Jsoup.parse(html).apply { outputSettings().prettyPrint(false) }

/**
 * 网页上的一张 POST 表单：提交地址（页面上写的，空串是页面自己）、要提交的字段、要上传的文件，
 * 与它声明的编码（`enctype="multipart/form-data"` 的表单 [multipart] 为 true，浏览器照它分段提交，文件只能这样传）。
 */
internal data class WebForm(
    val action: String,
    val fields: List<Pair<String, String>>,
    val multipart: Boolean = false,
    val files: List<Pair<String, FormFile>> = emptyList(),
)

/** 表单里上传的一个文件：浏览器带上的文件名与类型，和内容。 */
internal class FormFile(val filename: String, val contentType: String, val bytes: ByteArray)

/**
 * 照浏览器提交一张表单之前用户改了的项：[values] 按 name 换掉文本框与文本域的值，[checks] 按 name 勾上或取消勾选框，
 * [files] 按 name 给文件框选一个文件。没提到的控件按页面上现在的值提交。
 */
internal data class FormEdit(
    val values: Map<String, String> = emptyMap(),
    val checks: Map<String, Boolean> = emptyMap(),
    val files: Map<String, FormFile> = emptyMap(),
)

/** 表单声明的是不是分段编码（HTML 不分大小写）。 */
private fun Element.isMultipart(): Boolean = attr("enctype").equals("multipart/form-data", ignoreCase = true)

/** 页面里的 POST 表单。表单的控件以解析器认定的归属为准（[FormElement.elements]），表单套在表格里也不丢控件。 */
private fun Document.postForms(): List<FormElement> =
    select("form").filterIsInstance<FormElement>().filter { it.attr("method").equals("post", ignoreCase = true) }

/**
 * 页面里提交到 [actionPath] 的那张 POST 表单：字段取隐藏输入框与提交按钮（有 name 的），其余输入框是给人填的，不在里面。
 * 没写 action 的表单提交到页面自己（HTML 的规定），[WebForm.action] 为空串，也算。找不到返回 null。
 */
internal fun parsePostForm(html: String, actionPath: String): WebForm? = webDocumentOf(html).postForms().firstNotNullOfOrNull { form ->
    val action = form.attr("action")
    if (action.isNotEmpty() && actionPath !in action) return@firstNotNullOfOrNull null
    val fields = form.elements().filter { it.normalName() == "input" && it.hasAttr("name") && it.inputType() in setOf("hidden", "submit") }
        .map { it.attr("name") to it.attr("value") }
    WebForm(action, fields, multipart = form.isMultipart())
}

/** 照原样提交的输入框类型（值就是页面上写的）。 */
private val VALUE_INPUT_TYPES = setOf(
    "text", "hidden", "password", "email", "url", "tel", "number", "search", "color", "date", "datetime-local", "month", "week", "time", "range",
)

/** 输入框的类型，没写是 `text`（HTML 的规定）。 */
private fun Element.inputType(): String = attr("type").lowercase().ifEmpty { "text" }

/** HTML 规定 `<textarea>` 开标签后紧跟的一个换行不属于内容。 */
private fun String.withoutLeadingNewline(): String = when {
    startsWith("\r\n") -> drop(2)
    startsWith("\n") -> drop(1)
    else -> this
}

/** 文本框类的输入框：值是用户填的（[FormEdit.values] 能换掉的就是这些与文本域）。 */
private val TEXT_INPUT_TYPES = setOf("text", "email", "url", "tel", "number", "search")

/** 控件是不是 [FormEdit] 改得了的那一种：[values] 是文本框或文本域，[checks] 是勾选框，[files] 是文件框。 */
private fun Element.editableAs(edit: FormEdit): Boolean {
    val name = attr("name")
    return when {
        normalName() == "textarea" -> name in edit.values
        normalName() != "input" -> false
        inputType() == "checkbox" -> name in edit.checks
        inputType() == "file" -> name in edit.files
        inputType() in TEXT_INPUT_TYPES -> name in edit.values
        else -> false
    }
}

/**
 * 页面里带着 [edit] 要改的每一个控件的那张 POST 表单，按浏览器在用户改完这几项之后点提交时发出的字段取：
 * 其余每一项都是页面上现在的值（文本框、隐藏字段、文本域、下拉框选中的一项、勾上的勾选框与单选框），第一个提交按钮，
 * 普通按钮、`disabled` 的控件与没选文件的文件框不提交。整张表单原样提交，别的设置不会被清空。
 *
 * 只回传读得准的表单：要改的控件有一个不在、不是那一种（要换值的却是勾选框）或被 `disabled`，或表单里有读不准的控件
 * （带 name 的 `<button>`、多选的 `<select multiple>`、不认识的输入框类型）一律返回 null，宁可不提交也不少发一项。
 */
internal fun editedPostForm(html: String, edit: FormEdit): WebForm? {
    val wanted = edit.values.keys + edit.checks.keys + edit.files.keys
    val form = webDocumentOf(html).postForms().firstOrNull { form ->
        val names = form.elements().filter { it.hasAttr("name") }.map { it.attr("name") }.toSet()
        wanted.all { it in names }
    } ?: return null
    val editable = form.elements().filter { it.hasAttr("name") && !it.hasAttr("disabled") && it.editableAs(edit) }.map { it.attr("name") }.toSet()
    if (!editable.containsAll(wanted)) return null
    var submitted = false
    val fields = mutableListOf<Pair<String, String>>()
    for (control in form.elements()) {
        val name = control.attr("name").takeIf { control.hasAttr("name") }
        val disabled = control.hasAttr("disabled")
        when (control.normalName()) {
            "input" -> {
                val type = control.inputType()
                val value = control.attr("value").takeIf { control.hasAttr("value") }
                when {
                    name == null || disabled || type in setOf("file", "button", "reset", "image") -> Unit
                    type == "checkbox" -> {
                        val on = edit.checks[name] ?: control.hasAttr("checked")
                        if (on) fields += name to (value ?: "on")
                    }
                    type == "radio" -> if (control.hasAttr("checked")) fields += name to (value ?: "on")
                    type == "submit" -> if (!submitted) fields += (name to value.orEmpty()).also { submitted = true }
                    type in VALUE_INPUT_TYPES -> fields += name to (edit.values[name] ?: value.orEmpty())
                    else -> return null
                }
            }
            "textarea" -> if (name != null && !disabled) fields += name to (edit.values[name] ?: control.wholeText().withoutLeadingNewline())
            "select" -> {
                if (control.hasAttr("multiple")) return null
                if (name == null || disabled) continue
                val option = control.selectFirst("option[selected]") ?: control.selectFirst("option") ?: continue
                fields += name to (if (option.hasAttr("value")) option.attr("value") else option.text())
            }
            "button" -> if (name != null && !disabled && control.attr("type").lowercase() in setOf("", "submit")) return null
        }
    }
    return WebForm(form.attr("action"), fields, multipart = form.isMultipart(), files = edit.files.toList())
}

/** 相对地址按本页地址补全；空串是本页自己。 */
internal fun Url.resolve(target: String): String = when {
    target.isEmpty() -> toString()
    target.startsWith("http://") || target.startsWith("https://") -> target
    target.startsWith("/") -> "${protocol.name}://$host$target"
    else -> "${protocol.name}://$host${encodedPath.substringBeforeLast('/')}/$target"
}

private val BGM_HOST = Url(BGM_SITE).host

/**
 * 带网页登录 Cookie 的请求只发往 bgm.tv 本站（https、主机与 [BGM_SITE] 相同），回 [url] 本身。
 * 地址常常是页面上写的（操作链接、表单 action），页面里也有用户写的内容；指向别的站的一律拒掉，Cookie 不出本站，按坏响应处理。
 */
internal fun requireBgmSite(url: String): String {
    val parsed = Url(url)
    if (parsed.protocol != URLProtocol.HTTPS || parsed.host != BGM_HOST) throw FailureException(Failure.BadResponse)
    return url
}
