package io.github.touhougo.chogumi.feature.calendar

import io.github.touhougo.chogumi.core.model.AiringMinute
import io.github.touhougo.chogumi.core.model.AiringNow

/** 测试里的「今天」（周四）：固定下来，截图与断言不随跑测试的那一天变。 */
internal const val FIXTURE_TODAY = 4

/** 测试里的「现在」：周四 20:30（放送日里的分钟数），「现在」线只画在周四那页。 */
internal val FIXTURE_NOW = AiringNow(FIXTURE_TODAY, AiringMinute(20 * 60 + 30))
