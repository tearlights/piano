package com.gpiano.app.omr

import android.content.Context
import android.content.pm.ApplicationInfo
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class OmrSettings(
    val endpoint: String,
    val token: String,
)

class OmrSettingsStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun load(): OmrSettings? {
        val endpoint = preferences.getString(KEY_ENDPOINT, null)?.takeIf(String::isNotBlank) ?: return null
        val encrypted = preferences.getString(KEY_TOKEN, null) ?: return null
        val iv = preferences.getString(KEY_TOKEN_IV, null) ?: return null
        return runCatching {
            OmrSettings(endpoint, decrypt(Base64.decode(encrypted, Base64.NO_WRAP), Base64.decode(iv, Base64.NO_WRAP)))
        }.getOrNull()
    }

    fun save(endpoint: String, token: String): OmrSettings {
        val normalized = validateEndpoint(endpoint)
        require(token.isNotBlank()) { "访问令牌不能为空" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(token.trim().toByteArray(Charsets.UTF_8))
        preferences.edit()
            .putString(KEY_ENDPOINT, normalized)
            .putString(KEY_TOKEN, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString(KEY_TOKEN_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
        return OmrSettings(normalized, token.trim())
    }

    fun validateEndpoint(value: String): String {
        val normalized = value.trim().trimEnd('/')
        val uri = runCatching { URI(normalized) }.getOrElse { error("OMR 服务地址无效") }
        require(uri.userInfo == null && uri.query == null && uri.fragment == null) { "OMR 服务地址不能包含凭据、查询或片段" }
        require(uri.host != null && uri.path.orEmpty().let { it.isEmpty() || it == "/" }) { "请输入服务根地址" }
        val debuggable = appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val loopback = uri.host.equals("127.0.0.1", true) || uri.host.equals("localhost", true) || uri.host == "::1"
        require(uri.scheme.equals("https", true) || (debuggable && loopback && uri.scheme.equals("http", true))) {
            "正式连接必须使用 HTTPS；Debug 仅允许 localhost 明文地址"
        }
        return normalized
    }

    private fun decrypt(encrypted: ByteArray, iv: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(encrypted).toString(Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val PREFERENCES = "omr-settings"
        const val KEY_ENDPOINT = "endpoint"
        const val KEY_TOKEN = "token"
        const val KEY_TOKEN_IV = "tokenIv"
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "gpiano.omr.token.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

