# 给 coding agent 的说明

Chogumi 是 bgm.tv（Bangumi）的第三方 Android 客户端：Kotlin、Jetpack Compose、Material 3 Expressive。
这一页只是路由表，自己不写规则：做一件事之前，先读对应的那一页。

| 要做的事 | 先读 |
|---|---|
| 改任何代码之前 | [LESSONS.md](LESSONS.md)：合进来的工作里做错过什么，挡住它的规则是哪条 |
| 写代码、审代码 | [代码规范](docs/code-standards.md)：三条红线、验收标准、各类「只许一种实现」 |
| 决定做不做、怎么做 | [流程](docs/dev/workflow.md)「谁来定」 |
| 跑检查、攒批开 PR、写 PR 正文、合并 | [流程](docs/dev/workflow.md)「合并前」 |
| 改了功能、设置或架构：同一个 PR 里改哪些文档 | [流程](docs/dev/workflow.md)「文档跟着改动走」 |
| 装包、仪器测试、截图、录屏 | [流程](docs/dev/workflow.md)「仪器测试与设备」，[开发须知](docs/dev/guide.md) §4.2 |
| 每批合入之后的复盘 | [流程](docs/dev/workflow.md)「复盘」；条目格式在 [LESSONS.md](LESSONS.md) 开头 |
| 写新门禁、改门禁 | [流程](docs/dev/workflow.md)「新门禁」，`tools/guards/sources.py` |
| 找该改哪个文件 | [开发须知](docs/dev/guide.md) §0「改什么去哪里」 |
| 服务端的行为（分页、故障、网页会话、时间线、接口的事实） | [开发须知](docs/dev/guide.md) §1 |
| 排版框架级的坑 | [开发须知](docs/dev/guide.md) §2；Compose 的坑在[代码规范](docs/code-standards.md)「Compose 的老坑」 |
| 本地环境、模拟器、正式签名与发版 | [开发须知](docs/dev/guide.md) §4 |
| 字阶、圆角 | [字阶规范](docs/design/typography.md) |
| 任何 M3 数值、组件行为、动效曲线 | M3 规范副本 `.agent-skills/m3-expressive/SKILL.md`（仓库外，获取方式见[代码规范](docs/code-standards.md)「Material Design 3」），不凭记忆 |
| 表情面板 | [表情分组](docs/design/smiley-picker-groups.md) |
| 液态玻璃（设置里的「玻璃效果」，实验） | [液态玻璃](docs/design/liquid-glass.md) |
| 随包的第三方代码与素材、关于页清单 | [第三方来源说明](docs/third-party-notices.md) |
| 外部贡献者的 issue 与 PR | [CONTRIBUTING.md](CONTRIBUTING.md)、[SECURITY.md](SECURITY.md) |
| 调查报告、原型、flaky 日志 | 不进仓库：`.devdoc/`（gitignore，维护者本机）。仓库只放代码与规则 |
