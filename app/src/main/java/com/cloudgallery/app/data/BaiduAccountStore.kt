package com.cloudgallery.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import com.cloudgallery.app.data.baidu.BaiduTokens

data class BaiduAccount(val id: String, val name: String, val root: String?, val tokens: BaiduTokens)

/** Keep the existing preference name and key alias so upgrades retain encrypted Baidu sessions. */
class BaiduAccountStore(context: Context) {
    private val prefs = context.getSharedPreferences("connection", Context.MODE_PRIVATE)
    init {
        // Remove retired connection configuration without touching the Baidu record or its encryption key.
        if (prefs.contains("record") || prefs.contains("active")) {
            check(prefs.edit().remove("record").remove("active").commit())
        }
    }
    @Synchronized private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("cloudgallery-connection", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("cloudgallery-connection", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun saveBaidu(account: BaiduAccount) {
        saveJson("baidu", JSONObject().put("id", account.id).put("name", account.name)
            .put("root", account.root ?: JSONObject.NULL).put("access", account.tokens.accessToken)
            .put("refresh", account.tokens.refreshToken).put("expires", account.tokens.expiresAt))
    }
    @Synchronized fun loadBaidu(): BaiduAccount? {
        val json = loadJson("baidu") ?: return null
        return BaiduAccount(json.getString("id"), json.getString("name"),
            if (json.isNull("root")) null else json.getString("root"),
            BaiduTokens(json.getString("access"), json.getString("refresh"), json.getLong("expires")))
    }
    private fun saveJson(name: String, json: JSONObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(json.toString().toByteArray())
        check(prefs.edit().putString(name, Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)).commit())
    }
    private fun loadJson(name: String): JSONObject? {
        val record = prefs.getString(name, null) ?: return null
        val bytes = Base64.decode(record, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        }
        return JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size))))
    }
}
