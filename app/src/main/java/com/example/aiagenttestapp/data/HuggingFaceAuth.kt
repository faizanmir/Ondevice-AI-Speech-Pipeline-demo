package com.example.aiagenttestapp.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The signed-in HuggingFace account. */
data class HfAccount(
    val username: String,
    val fullName: String?,
)

/**
 * Stores a HuggingFace access token and proves it works.
 *
 * A token is a bearer credential for someone's whole HuggingFace account, so it is encrypted with a
 * key held in the Android Keystore rather than dropped into SharedPreferences as plain text.
 * App-private storage already keeps other apps out on a healthy device; the Keystore is what keeps
 * the token unreadable if the file is ever lifted off a rooted phone or out of a backup. The key
 * never leaves the secure hardware, and this app only ever hands it bytes to encrypt.
 *
 * Deliberately *not* OAuth. A pasted personal access token is what HuggingFace's own tooling
 * (huggingface-cli, the Python client) uses, it needs no registered redirect URI or client secret
 * shipped in the APK, and it lets the user scope the token to read-only.
 */
class HuggingFaceAuth(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("hf_auth", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val client = OkHttpClient()

    private val _token = MutableStateFlow(loadToken())
    val token: StateFlow<String?> = _token.asStateFlow()

    private val _account = MutableStateFlow(loadAccount())
    val account: StateFlow<HfAccount?> = _account.asStateFlow()

    val isSignedIn: Boolean get() = _token.value != null

    /**
     * Validates [rawToken] against HuggingFace and, only if it is real, stores it.
     *
     * Verifying before saving matters: a typo'd token stored blindly turns every later download
     * into a mystifying 401, which the user would reasonably read as "this model is broken" rather
     * than "your token is wrong".
     */
    suspend fun signIn(rawToken: String): Result<HfAccount> = withContext(Dispatchers.IO) {
        val trimmed = rawToken.trim()
        if (trimmed.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Paste a token first"))
        }

        try {
            val request = Request.Builder()
                .url(WHOAMI_URL)
                .header("Authorization", "Bearer $trimmed")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.code == 401) {
                    return@withContext Result.failure(
                        IllegalArgumentException("HuggingFace rejected that token. Check you " +
                            "copied all of it, and that it has read access."),
                    )
                }
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IllegalStateException("HuggingFace returned HTTP ${response.code}"),
                    )
                }

                val body = response.body?.string().orEmpty()
                val obj = json.parseToJsonElement(body).jsonObject
                val username = obj["name"]?.jsonPrimitive?.content
                    ?: return@withContext Result.failure(
                        IllegalStateException("HuggingFace did not return an account name"),
                    )

                val account = HfAccount(
                    username = username,
                    fullName = obj["fullname"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
                )

                storeToken(trimmed)
                storeAccount(account)
                _token.value = trimmed
                _account.value = account

                Result.success(account)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun signOut() {
        prefs.edit().remove(KEY_TOKEN).remove(KEY_USERNAME).remove(KEY_FULLNAME).apply()
        _token.value = null
        _account.value = null
    }

    /** The header to attach to a HuggingFace request, or null when signed out. */
    fun authHeader(): Pair<String, String>? =
        _token.value?.let { "Authorization" to "Bearer $it" }

    // --- Keystore-backed storage -----------------------------------------------------------------

    private fun storeToken(token: String) {
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())

            val ciphertext = cipher.doFinal(token.toByteArray(Charsets.UTF_8))

            // The GCM IV must be kept with the ciphertext -- it is not secret, but decryption is
            // impossible without it.
            val encoded = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(ciphertext, Base64.NO_WRAP)

            prefs.edit().putString(KEY_TOKEN, encoded).apply()
        } catch (e: Exception) {
            Log.e(TAG, "could not encrypt the token", e)
        }
    }

    private fun loadToken(): String? {
        val stored = prefs.getString(KEY_TOKEN, null) ?: return null

        return try {
            val (ivPart, dataPart) = stored.split(":", limit = 2)
            val iv = Base64.decode(ivPart, Base64.NO_WRAP)
            val ciphertext = Base64.decode(dataPart, Base64.NO_WRAP)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_LENGTH_BITS, iv))

            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            // The Keystore key is gone (app data cleared, device restored to new hardware, or a
            // backup restored elsewhere). The stored bytes are now undecryptable, so drop them --
            // the user simply signs in again. Failing to start would be a far worse outcome.
            Log.w(TAG, "stored token could not be decrypted, discarding it", e)
            prefs.edit().remove(KEY_TOKEN).apply()
            null
        }
    }

    private fun storeAccount(account: HfAccount) {
        prefs.edit()
            .putString(KEY_USERNAME, account.username)
            .putString(KEY_FULLNAME, account.fullName)
            .apply()
    }

    private fun loadAccount(): HfAccount? {
        val username = prefs.getString(KEY_USERNAME, null) ?: return null
        return HfAccount(username, prefs.getString(KEY_FULLNAME, null))
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let {
            return it.secretKey
        }

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE,
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                // Not tied to a screen lock: model downloads have to survive the app being
                // backgrounded, and requiring authentication per decrypt would break them.
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val TAG = "HuggingFaceAuth"
        const val WHOAMI_URL = "https://huggingface.co/api/whoami-v2"

        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "huggingface_token_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_LENGTH_BITS = 128

        const val KEY_TOKEN = "token"
        const val KEY_USERNAME = "username"
        const val KEY_FULLNAME = "fullname"
    }
}
