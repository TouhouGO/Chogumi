package io.github.touhougo.chogumi.core.data.web

import io.github.touhougo.chogumi.core.data.repository.testTokenStore
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.FailureException
import io.github.touhougo.chogumi.core.foundation.RejectReason
import io.github.touhougo.chogumi.core.network.api.WebSessionApi
import io.github.touhougo.chogumi.core.network.model.web.WebSession
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.security.ProviderException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Keystore 的各种失败在解密时都落到「解不开」：调用方当作没有网页会话，而不是让异常冲出读取 Cookie 的协程。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeystoreCookieCipherTest {
    @get:org.junit.Rule
    val resources = io.github.touhougo.chogumi.core.data.repository.TestResources()


    private val softwareKey: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun `同一把密钥加密的 Cookie 解得开`() {
        val cipher = KeystoreCookieCipher { softwareKey }

        assertEquals("chii_auth=abc", cipher.open(checkNotNull(cipher.seal("chii_auth=abc"))))
    }

    @Test
    fun `密钥失效抛 ProviderException 时 当作解不开`() {
        val sealed = checkNotNull(KeystoreCookieCipher { softwareKey }.seal("chii_auth=abc"))

        assertNull(KeystoreCookieCipher { throw ProviderException("Keystore operation failed") }.open(sealed))
    }

    @Test
    fun `读 Keystore 抛 IOException 时 当作解不开`() {
        val sealed = checkNotNull(KeystoreCookieCipher { softwareKey }.seal("chii_auth=abc"))

        assertNull(KeystoreCookieCipher { throw IOException("keystore load failed") }.open(sealed))
    }

    @Test
    fun `加密时 Keystore 抛 ProviderException 返回 null 而不是抛出`() {
        assertNull(KeystoreCookieCipher { throw ProviderException("Keystore operation failed") }.seal("chii_auth=abc"))
    }

    @Test
    fun `存着的 Cookie 因密钥失效解不开时 要账号密码登录并丢掉坏的密文`() = runTest {
        val site = TestWebSite(userId = 7)
        val tokenStore = testTokenStore(resources, userId = 7)
        val sealer = KeystoreCookieCipher { softwareKey }
        val broken = KeystoreCookieCipher { throw ProviderException("Keystore operation failed") }
        val cipher = object : CookieCipher {
            override fun seal(plain: String): String? = sealer.seal(plain)
            override fun open(sealed: String) = broken.open(sealed)
        }
        val repository = WebSessionRepository(tokenStore, cipher, WebSessionApi(site.client))
        repository.save(tokenStore.session(), WebSession("chii_auth=abc"))

        val e = assertFailsWith<FailureException> { repository.perform { formhash -> "https://bgm.tv/act?gh=$formhash" } }

        assertEquals(Failure.Rejected(RejectReason.WEB_LOGIN_REQUIRED), e.failure)
        assertNull(tokenStore.webSessionFor(tokenStore.session()))
        assertEquals(emptyList(), site.opened)
    }
}
