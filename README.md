<div align="center">

<img src="docs/images/readme/banner.webp" width="100%" alt="Chogumi">

# Chogumi · 番组计划

**好看、流畅的 [Bangumi](https://bgm.tv) 原生客户端**  
*Jetpack Compose + Material 3 Expressive 设计*

<p align="center">
  <a href="https://github.com/TouhouGO/Chogumi/releases/latest"><img src="https://img.shields.io/badge/%E4%B8%8B%E8%BD%BD-APK-e8749a?style=for-the-badge&logo=android&logoColor=white" alt="下载 APK"></a>
  <a href="https://github.com/TouhouGO/Chogumi"><img src="https://img.shields.io/github/stars/TouhouGO/Chogumi?style=for-the-badge&logo=github&color=5b8def&label=Stars" alt="GitHub Stars"></a>
  <img src="https://img.shields.io/badge/Android-7.0%2B-3ddc84?style=for-the-badge" alt="Android 7.0 起">
  <a href="LICENSE"><img src="https://img.shields.io/badge/%E5%BC%80%E6%BA%90-GPL--3.0-orange?style=for-the-badge" alt="GPL-3.0"></a>
</p>

</div>

<br>

### 关于「点格子」进度的重要说明

> [!IMPORTANT]
> **Chogumi 的单集进度系统完全以「看到此处」为核心逻辑，安装前请务必知悉：**
>
> 1. **默认向前顺推**：在剧集进度中**点下某一集格子，默认会将该集以及此前所有的单集全部标记为「看过」**（一次跨度较大时，会先高亮预览要变的格子，再点一次同一格才生效，防止误触）。
> 2. **不提供单集细分状态**：这里**没有保留网页原版对单个剧集分别标记「想看」「抛弃」等细分状态的操作**（网页上已标过的只做展示，客户端内无法单独修改）。

> [!CAUTION]
> **适用建议**：**如果你的日常使用习惯高度依赖对单个剧集标记不同状态，请不要使用此应用**，以免打乱你的标记习惯。

---

### 核心体验与设计特点

- **打通全站路由，多维自由探索**：关键词搜索、分类浏览、排行榜和标签页不再割裂。**年代区间 × 题材分类 × 制作公司 × 用户标签 × 排序规则**（排名/热度/收藏/发售）全部可以任意叠加、交叉筛选；拼出的任何组合（如「2010年代 × 霸权社 × 战斗」）均可**一键固定到首页探索页**，变成专属于你的自定义海报墙或书架。
- **长按封面，拇指一滑即标**：看到想看的条目无需点进详情页找按钮。在任何地方长按作品封面，5 个收藏状态与管理圆钮顺着拇指弧度展开，**滑动即选、松手即标**，屏幕上方带实时胶囊预览与误触撤销，单手刷榜单体验极顺。
- **系列番剧横向一条线排开**：第一季、第二季、前传、剧场版……同系列的正传续作直接在横向一行里排通，按顺序一目了然，无需在多层关联列表里反复跳转翻找。
- **专注模式：纯粹追番，不看打分与社区节奏**：一键开启「专注模式」，底栏直接精简收起整个社区（隐藏超展开、动态、讨论、吐槽区与通知小红点），不被打扰。还可以**独立隐藏他人评分、全站排名及封面上的分数角标**（自己的打分短评始终保留），拒绝被大众分数和党争带节奏。
- **小组屏蔽与绝交过滤**：超展开里总有不想看到的话题？客户端支持直接**屏蔽特定小组**，动态与超展开自动清爽过滤；同时深度支持 Bangumi 官方绝交名单，自动屏蔽被绝交用户的帖子与楼层，还你清净的讨论区。
- **作品讨论走势与个人生涯图谱**：作品页直观整理了开播前后的讨论热度与流向；个人主页可以用你的真实收藏封面拼成一整张充满成就感的个人全景生涯图谱。
- **系统通知，不用一直开着 App**：可以把回复、@、好友请求、短信变成系统通知，按你设的间隔在后台检查；追的番开播时、或每天定一个时间提醒今天要播什么，通知上直接标「看过了」。全部默认关闭，在设置里按需打开。
- **应用内代理**：支持 HTTP 与 SOCKS5，填主机和端口就能用，不依赖系统或别的 App；连不上时请求直接失败，不会悄悄改走别的线路。

<br>

<p align="center">
  <img src="docs/images/readme/overview.webp" alt="首页、探索、长按轮盘、作品页、作品统计、生涯卡" width="100%" />
</p>

<details>
<summary><b>点击展开查看更多界面截图</b></summary>

| 模块样式 | 按年代浏览 | 整理模块 | 搜索 | 章节 |
|:---:|:---:|:---:|:---:|:---:|
| <img src="docs/images/readme/explore-styles.webp" alt="模块样式"> | <img src="docs/images/readme/explore-decades.webp" alt="按年代浏览"> | <img src="docs/images/readme/arrange.webp" alt="整理模块"> | <img src="docs/images/readme/search.webp" alt="搜索"> | <img src="docs/images/readme/episodes.webp" alt="章节"> |
| **单集讨论** | **作品社区** | **角色** | **进度** | **帖子** |
| <img src="docs/images/readme/episode.webp" alt="单集讨论"> | <img src="docs/images/readme/subject-community.webp" alt="作品社区"> | <img src="docs/images/readme/character.webp" alt="角色"> | <img src="docs/images/readme/progress.webp" alt="进度"> | <img src="docs/images/readme/topic.webp" alt="帖子"> |

</details>

---

### 获取 Chogumi

可随时在 [GitHub Releases](https://github.com/TouhouGO/Chogumi/releases/latest) 获取编译完备的稳定版 APK 文件（支持 Android 7.0 及以上版本）。

<details>
<summary><b>关于账户安全与网页会话的说明</b></summary>

<br>

Chogumi 的所有图表、模块排布与系列脉络均在本地组织呈现。绝大部分操作走官方 API，少数操作因接口限制走网页端会话，需要用账号密码登录：移除作品收藏、取消收藏角色或人物、解除好友、绝交、修改个人资料（头像/昵称/签名/自我介绍）、删除动态、加入或退出小组，以及短信。发帖和回复需要通过人机验证。

</details>

---

### 关于名字

Bangumi 番组计划在 2008 年起步时，开发代号叫做 **Chobits**；2009 年才正式定名为「Bangumi 番组计划」（[Bangumi 编年史](https://bgm.tv/about)）。**Chogumi** 这个名字是对前世今生的致敬。

---

<p align="center">
  <a href="PRIVACY.md">隐私说明</a> ｜ <a href="https://github.com/TouhouGO/Chogumi/issues/new/choose">反馈问题</a> ｜ <a href="CONTRIBUTING.md">参与开发</a> ｜ <a href="docs/third-party-notices.md">第三方来源说明</a>
</p>

<p align="center">
  <sub>数据来自 <a href="https://bgm.tv">Bangumi 番组计划</a> 与 <a href="https://github.com/bangumi-data/bangumi-data">bangumi-data</a>；探索页的灵感来自 <a href="https://github.com/axiel7/AniHyou-android">AniHyou</a>，在此致谢；以 <a href="LICENSE">GPL-3.0</a> 开源</sub>
</p>
