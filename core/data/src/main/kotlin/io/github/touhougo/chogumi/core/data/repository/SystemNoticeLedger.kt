package io.github.touhougo.chogumi.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import java.time.LocalDate
import kotlinx.coroutines.flow.first

/**
 * 系统通知的记账：发过什么，下一轮从哪接着。只在本机，放在偏好 DataStore 里（键名真源 [PreferenceKeys]）；丢了只是下一轮从头记。
 * - 社区提醒按账号记基线（发过与跳过的最大提醒 id）与上一轮的未读短信条数，由 `noticeRoundOf` / `messageRoundOf` 读写；
 * - 放送提醒记今天（放送日）已经发过的作品，开播时与汇总都只发一次。
 *
 * 读写失败照常抛出：调用方（后台检查、闹钟接收器）经 `outcomeOf` 收住，这一轮不发。
 */
class SystemNoticeLedger(private val dataStore: DataStore<Preferences>) {

    suspend fun noticeBaseline(accountId: Int): Int? = dataStore.data.first()[PreferenceKeys.noticeBaselineKey(accountId)]

    suspend fun notifiedMessages(accountId: Int): Int? = dataStore.data.first()[PreferenceKeys.notifiedMessagesKey(accountId)]

    /** 一轮检查做完：记下新的基线与短信条数（短信这一轮没读出来时 [messages] 为 null，不动）。 */
    suspend fun recordRound(accountId: Int, baseline: Int, messages: Int?) {
        dataStore.edit {
            it[PreferenceKeys.noticeBaselineKey(accountId)] = baseline
            if (messages != null) it[PreferenceKeys.notifiedMessagesKey(accountId)] = messages
        }
    }

    /** 关掉社区提醒、换了账号：所有账号的基线都清掉，再打开时从那一刻的未读开始（之前的不推）。 */
    suspend fun forgetCommunity() {
        dataStore.edit { prefs ->
            prefs.asMap().keys.filter { it.name.startsWith(PreferenceKeys.NOTICE_LEDGER_PREFIX) }.forEach { prefs.remove(it) }
        }
    }

    /** 账号 [accountId] 在放送日 [date] 已经发过的放送提醒（作品 id；汇总是 [DIGEST]）。 */
    suspend fun airingPosted(accountId: Int, date: LocalDate): Set<Int> =
        postedOf(dataStore.data.first()[PreferenceKeys.AIRING_POSTED], accountId, date)

    /** 记下发过的一条；换了一天或账号，之前的不留。 */
    suspend fun markAiringPosted(accountId: Int, date: LocalDate, subjectId: Int) {
        dataStore.edit {
            val posted = postedOf(it[PreferenceKeys.AIRING_POSTED], accountId, date) + subjectId
            it[PreferenceKeys.AIRING_POSTED] = "$accountId|$date|${posted.joinToString(",")}"
        }
    }

    private fun postedOf(stored: String?, accountId: Int, date: LocalDate): Set<Int> {
        val parts = stored?.split('|') ?: return emptySet()
        if (parts.size != 3 || parts[0] != accountId.toString() || parts[1] != date.toString()) return emptySet()
        return parts[2].split(',').mapNotNullTo(HashSet()) { it.toIntOrNull() }
    }

    companion object {
        /** 当天的汇总在 [airingPosted] 里记成的作品 id（真实作品 id 从 1 起）。 */
        const val DIGEST = 0
    }
}
