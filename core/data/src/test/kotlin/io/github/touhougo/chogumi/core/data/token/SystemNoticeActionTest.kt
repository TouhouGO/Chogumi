package io.github.touhougo.chogumi.core.data.token

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.touhougo.chogumi.core.data.repository.TestResources
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

class SystemNoticeActionTest {
    @get:Rule val resources = TestResources()
    @Test fun `动作凭据冷恢复后有效_改id伪造或同号重登均失效`() = runBlocking {
        val file = File(resources.directory("notice-action"), "tokens.preferences_pb")
        val data = PreferenceDataStoreFactory.create(scope = resources.scope) { file }
        val tokens = TokenStore(data)
        val session = tokens.completeSession(tokens.beginSession("fixture-token"), 7, "fixture_user")
        val proof = tokens.noticeAction(session, 101)
        val restored = TokenStore(data)
        assertEquals(session, restored.noticeSession(101, proof))
        assertNull(restored.noticeSession(102, proof))
        assertNull(restored.noticeSession(101, "forged"))
        restored.completeSession(restored.beginSession("other-token"), 7, "fixture_user")
        assertNull(restored.noticeSession(101, proof))
    }
}
