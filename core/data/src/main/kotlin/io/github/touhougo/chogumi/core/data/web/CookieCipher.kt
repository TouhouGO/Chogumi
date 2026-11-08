package io.github.touhougo.chogumi.core.data.web

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 网页 Cookie 落盘前加密、读出后解密。 */
interface CookieCipher {
    /** 加不了（Keystore 坏了）返回 null：调用方不存网页会话，与 [open] 解不开是同一个状态。 */
    fun seal(plain: String): String?

    /** 解不开（密钥没了、密文坏了）返回 null：调用方当作没有网页会话。 */
    fun open(sealed: String): String?
}

/**
 * 用 Android Keystore 里的 AES-GCM 密钥加密：Cookie 是整个账号的网页会话，比令牌更需要防私有目录被拷走。
 * 密钥失效（换锁屏、系统异常）时只是解不开，丢的只有网页会话，OAuth 登录照常（guide §1.3）。
 * 密文 = Base64(IV ‖ 密文)。
 *
 * Keystore 坏掉的方式不止 `GeneralSecurityException`：密钥失效时部分设备抛 `ProviderException`（运行时异常），
 * `KeyStore.load` 可能抛 `IOException`。这些在 [seal] 与 [open] 里都由 [keystoreOrNull] 接住：加不了就不存、解不开就丢掉
 * 存着的密文，登录本身不受影响。[key] 只在测试里换掉，用来模拟这些失败。
 */
class KeystoreCookieCipher(private val key: () -> SecretKey = ::keystoreKey) : CookieCipher {

    override fun seal(plain: String): String? = keystoreOrNull {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        Base64.encodeToString(cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    override fun open(sealed: String): String? = keystoreOrNull {
        val bytes = Base64.decode(sealed, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
        String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
    }
}

/** Keystore 与密文能以哪几类异常失败，只在这里列一份。 */
private inline fun <T> keystoreOrNull(block: () -> T): T? = try {
    block()
} catch (e: GeneralSecurityException) {
    null
} catch (e: IllegalArgumentException) {
    null
} catch (e: ProviderException) {
    null
} catch (e: IOException) {
    null
}

private fun keystoreKey(): SecretKey {
    val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
    (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
    generator.init(
        KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_BITS)
            .build(),
    )
    return generator.generateKey()
}

private const val KEYSTORE = "AndroidKeyStore"
private const val KEY_ALIAS = "chogumi_web_cookie"
private const val TRANSFORMATION = "AES/GCM/NoPadding"
private const val KEY_BITS = 256
private const val IV_BYTES = 12
private const val TAG_BITS = 128
