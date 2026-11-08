# 参与贡献

Chogumi 以 GPL-3.0 开源，欢迎 issue 与 PR。先读这几页，它们就是评审标准：

- [AGENTS.md](AGENTS.md)：改代码前先读哪份文档
- [代码规范](docs/code-standards.md)：单一真源、无死代码、各类界面「只许一种实现」；违反红线的 PR 不会合入
- [流程](docs/dev/workflow.md)：合并前跑什么检查、PR 正文写什么、仪器测试怎么跑
- [开发须知](docs/dev/guide.md)：服务端的脾气、框架级的坑、本地环境
- [教训](LESSONS.md)：做错过的事和挡住它的规则

## 报告问题

- Bug：用 issue 模板，写清复现步骤、App 版本（设置 › 关于）和设备，能附截图更好。bgm.tv 网站本身的问题请到 Bangumi 的[番组开发小组](https://bgm.tv/group/dev)反馈。
- 功能建议：用 issue 模板，说清想要什么、在什么场景下用。
- 安全问题：见 [SECURITY.md](SECURITY.md)，不要开公开 issue。

## 提交 PR

1. 从最新的 `main` 开分支，一个 PR 只做一件事。
2. 推送前本地跑 `tools/ci/check.sh changed origin/main`，门禁与测试都要绿；改到有仪器测试的界面时在模拟器上跑对应模块的 `connectedDebugAndroidTest`。
3. 修 bug 附回归测试，并在 PR 正文贴出它在改动前的代码上失败的那一行。
4. 改界面附 448dp、380dp（字号 1.1）、800dp 三种宽度、浅深两色的截图。
5. PR 标题是一句中文：做了什么、关键点；正文按模板写为什么、改了什么、怎么验证、没做什么。
6. 提交、注释与文档用中文，标识符用英文。

外部贡献者的 PR 在 GitHub Actions 全绿后合入，署名保留。

## 本地构建

见 [开发须知](docs/dev/guide.md)「从首次克隆开始构建」与「启用网页登录」。网页登录需要自己在 bgm.tv 开发者平台注册应用；凭据文件 `oauth-app.properties` 已 gitignore，不要提交。
