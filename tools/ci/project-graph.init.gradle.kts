// 模块依赖图（tools/ci/check.sh modules 用，交给 tools/ci/affected.py）：依赖由 Gradle 解析后的配置给出，
// 约定插件加上的依赖（feature → core:data、core:ui，各模块测试 → core:foundation 测试夹具）也在里面，不另抄一份。
//
// 每个模块一行 `chogumi-module\t<路径>\t<目录>\t<检查任务…>`，每条模块间依赖一行 `chogumi-edge\t<使用方>\t<被依赖方>`。
// 检查任务与整仓 build 同口径：单元测试 `test`，仪器测试只编译（compileDebugAndroidTestKotlin），应用另打 debug 包。
// 初始化脚本也作用于被包含的 build-logic 构建（它的根项目路径同样是 `:`），只打主构建。
gradle.projectsEvaluated {
    if (gradle.parent != null) return@projectsEvaluated
    rootProject.allprojects.forEach { project ->
        val testTasks = project.tasks.withType(org.gradle.api.tasks.testing.Test::class.java).toList()
        if (testTasks.isEmpty()) return@forEach
        val names = project.tasks.names
        val checks = buildList {
            add("test")
            if ("compileDebugAndroidTestKotlin" in names) add("compileDebugAndroidTestKotlin")
            if (project.pluginManager.hasPlugin("com.android.application")) add("assembleDebug")
        }
        println((listOf("chogumi-module", project.path, project.projectDir.path) + checks).joinToString("\t"))
        testTasks.forEach { task ->
            val directory = task.reports.junitXml.outputLocation.get().asFile.relativeTo(rootProject.projectDir)
            println(listOf("chogumi-test-xml", project.path, directory.path).joinToString("\t"))
        }
        project.configurations.forEach { configuration ->
            configuration.dependencies.withType(ProjectDependency::class.java).forEach { dependency ->
                println(listOf("chogumi-edge", project.path, dependency.path).joinToString("\t"))
            }
        }
    }
}
