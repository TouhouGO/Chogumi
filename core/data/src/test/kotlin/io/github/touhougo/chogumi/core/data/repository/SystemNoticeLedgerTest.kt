package io.github.touhougo.chogumi.core.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 系统通知的记账：社区提醒的基线按账号记、关掉时全清；放送提醒只记同一账号同一放送日发过的。 */
class SystemNoticeLedgerTest {
    @get:org.junit.Rule
    val resources = TestResources()

    private fun newLedger(): SystemNoticeLedger {
        val dir = resources.directory("ledger-test")
        return SystemNoticeLedger(PreferenceDataStoreFactory.create(scope = resources.scope) { File(dir, "p.preferences_pb") })
    }

    private val today = LocalDate.of(2026, 10, 15)

    @Test
    fun `基线按账号记_短信没读出来时不动上一轮的条数`() = runTest {
        val ledger = newLedger()
        ledger.recordRound(accountId = 1, baseline = 30, messages = 2)
        ledger.recordRound(accountId = 1, baseline = 31, messages = null)
        ledger.recordRound(accountId = 2, baseline = 5, messages = 0)

        assertEquals(31, ledger.noticeBaseline(1))
        assertEquals(2, ledger.notifiedMessages(1))
        assertEquals(5, ledger.noticeBaseline(2))
    }

    @Test
    fun `关掉社区提醒清掉每个账号的基线_放送提醒的记录留着`() = runTest {
        val ledger = newLedger()
        ledger.recordRound(accountId = 1, baseline = 30, messages = 2)
        ledger.recordRound(accountId = 2, baseline = 5, messages = 1)
        ledger.markAiringPosted(accountId = 1, date = today, subjectId = 7)

        ledger.forgetCommunity()

        assertNull(ledger.noticeBaseline(1))
        assertNull(ledger.notifiedMessages(1))
        assertNull(ledger.noticeBaseline(2))
        assertEquals(setOf(7), ledger.airingPosted(1, today))
    }

    @Test
    fun `放送提醒只认同一账号同一放送日_换一天从空开始`() = runTest {
        val ledger = newLedger()
        ledger.markAiringPosted(accountId = 1, date = today, subjectId = 7)
        ledger.markAiringPosted(accountId = 1, date = today, subjectId = SystemNoticeLedger.DIGEST)

        assertEquals(setOf(7, SystemNoticeLedger.DIGEST), ledger.airingPosted(1, today))
        assertEquals(emptySet(), ledger.airingPosted(2, today))
        assertEquals(emptySet(), ledger.airingPosted(1, today.plusDays(1)))

        ledger.markAiringPosted(accountId = 1, date = today.plusDays(1), subjectId = 9)

        assertEquals(setOf(9), ledger.airingPosted(1, today.plusDays(1)))
        assertEquals(emptySet(), ledger.airingPosted(1, today))
    }
}
