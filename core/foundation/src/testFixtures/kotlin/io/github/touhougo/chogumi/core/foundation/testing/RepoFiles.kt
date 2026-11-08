package io.github.touhougo.chogumi.core.foundation.testing

import java.io.File

/**
 * 仓库里的文件：源码契约测试读主源码、构建脚本、资源与文档，以及扫描「某种写法只许出现在一处」的唯一入口。
 *
 * 仓库根由构建以系统属性 `chogumi.repoRoot` 传入（build-logic 的 `configureUnitTests`），
 * 测试不各自从工作目录或 class 输出目录往上找，也不先试模块相对路径。
 */
object RepoFiles {

    val root: File by lazy {
        File(checkNotNull(System.getProperty("chogumi.repoRoot")) { "缺少系统属性 chogumi.repoRoot：单元测试要经 Gradle 运行" })
    }

    /** 相对仓库根的文件。 */
    fun file(path: String): File = File(root, path)

    /** 相对仓库根的文件内容。 */
    fun read(path: String): String = file(path).readText()

    /** 仓库内的相对路径（`/` 分隔），报违规位置用。 */
    fun relativePath(file: File): String = file.relativeTo(root).invariantSeparatorsPath

    /**
     * [dirs]（相对仓库根；不传为整个仓库）下各模块 main 源集里的 Kotlin 文件。
     * 走法同 [sourceFiles]。
     */
    fun mainKotlinFiles(vararg dirs: String): List<File> =
        (if (dirs.isEmpty()) listOf(root) else dirs.map(::file)).flatMap { dir ->
            walk(dir).filter { it.extension == "kt" && "/src/main/" in it.invariantSeparatorsPath }
        }

    /**
     * 整个仓库里扩展名在 [extensions] 中的文件：所有模块的各个源集、资源与构建脚本。
     * 不进构建产物、node_modules 依赖，也不进隐藏目录：`.git`、`.gradle`，以及放在仓库目录里、不属于本仓库的副本（`.reference` 参考项目、
     * 其他工具的 worktree 等），否则「只许一处」「没人引用」的守卫会把它们也算进来。
     */
    fun sourceFiles(vararg extensions: String): List<File> = walk(root).filter { it.extension in extensions }

    private fun walk(dir: File): List<File> =
        dir.walkTopDown()
            .onEnter { it == dir || (it.name != "build" && it.name != "node_modules" && !it.name.startsWith(".")) }
            .filter { it.isFile }
            .toList()

    /**
     * 主源码里除 [allowedFile] 外用到 [identifier] 这个名字的代码行，格式「路径:行号: 内容」。
     * 按整词找、不看注释：带空格的调用、函数引用（`::x`）、导入行（含 `as` 别名）都算；注释里提到它不算。
     */
    fun offenders(identifier: String, allowedFile: String): List<String> {
        val word = Regex("""(?<![\w$])${Regex.escape(identifier)}(?![\w$])""")
        return mainKotlinFiles()
            .filter { relativePath(it) != allowedFile }
            .flatMap { file ->
                codeLines(file).withIndex()
                    .filter { (_, line) -> word.containsMatchIn(line) }
                    .map { (index, line) -> "${relativePath(file)}:${index + 1}: ${line.trim()}" }
            }
    }

    /**
     * Kotlin 文件去掉注释后的各行：行注释与（可嵌套的）块注释换成空格，字符串与字符字面量原样留着，行数与行号不变。
     * 源码契约按它找代码里的写法，注释里引用的名字或文案不算数。字符串模板 `${…}` 里再嵌的字符串不细分。
     */
    fun codeLines(file: File): List<String> {
        val text = file.readText()
        val out = StringBuilder(text.length)
        var i = 0
        var blockDepth = 0
        var string: String? = null
        while (i < text.length) {
            val c = text[i]
            when {
                blockDepth > 0 -> when {
                    text.startsWith("/*", i) -> { blockDepth++; out.append("  "); i += 2 }
                    text.startsWith("*/", i) -> { blockDepth--; out.append("  "); i += 2 }
                    else -> { out.append(if (c == '\n') '\n' else ' '); i++ }
                }
                string != null -> when {
                    string != "\"\"\"" && c == '\\' && i + 1 < text.length -> { out.append(c).append(text[i + 1]); i += 2 }
                    text.startsWith(string, i) -> { out.append(string); i += string.length; string = null }
                    else -> { out.append(c); i++ }
                }
                text.startsWith("//", i) -> while (i < text.length && text[i] != '\n') { out.append(' '); i++ }
                text.startsWith("/*", i) -> { blockDepth = 1; out.append("  "); i += 2 }
                text.startsWith("\"\"\"", i) -> { string = "\"\"\""; out.append(string); i += 3 }
                c == '"' || c == '\'' -> { string = c.toString(); out.append(c); i++ }
                else -> { out.append(c); i++ }
            }
        }
        return out.lines()
    }
}
