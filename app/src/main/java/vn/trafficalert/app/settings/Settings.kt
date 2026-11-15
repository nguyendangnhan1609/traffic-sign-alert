package vn.trafficalert.app.settings

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import vn.trafficalert.core.RepeatMode

data class AppSettings(
    val apiKey: String = "",
    val model: String = DEFAULT_MODEL,
    val cloudEnabled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.WHEN_SPEEDING,
    val repeatIntervalSec: Int = 10,
) {
    val cloudReady: Boolean get() = cloudEnabled && apiKey.isNotBlank() && model.isNotBlank()

    companion object { const val DEFAULT_MODEL = "claude-haiku-4-5" }
}

/** Lưu cài đặt; khoá API được mã hoá bằng Android Keystore. */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences?
    private var memory = AppSettings()

    /** true nếu không mở được bộ lưu trữ mã hoá (cài đặt chỉ giữ trong bộ nhớ). */
    val storageError: Boolean

    init {
        prefs = try {
            val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            EncryptedSharedPreferences.create(
                context, "settings_secure", key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (t: Throwable) {
            Log.e("SettingsStore", "Không mở được EncryptedSharedPreferences", t)
            null
        }
        storageError = prefs == null
    }

    fun load(): AppSettings {
        val p = prefs ?: return memory
        return AppSettings(
            apiKey = p.getString(KEY_API, "") ?: "",
            model = p.getString(KEY_MODEL, AppSettings.DEFAULT_MODEL) ?: AppSettings.DEFAULT_MODEL,
            cloudEnabled = p.getBoolean(KEY_CLOUD, false),
            repeatMode = runCatching { RepeatMode.valueOf(p.getString(KEY_REPEAT, null) ?: "") }
                .getOrDefault(RepeatMode.WHEN_SPEEDING),
            repeatIntervalSec = p.getInt(KEY_INTERVAL, 10).coerceIn(5, 60),
        )
    }

    fun save(s: AppSettings) {
        memory = s
        prefs?.edit()
            ?.putString(KEY_API, s.apiKey.trim())
            ?.putString(KEY_MODEL, s.model.trim())
            ?.putBoolean(KEY_CLOUD, s.cloudEnabled)
            ?.putString(KEY_REPEAT, s.repeatMode.name)
            ?.putInt(KEY_INTERVAL, s.repeatIntervalSec)
            ?.apply()
    }

    private companion object {
        const val KEY_API = "api_key"
        const val KEY_MODEL = "model"
        const val KEY_CLOUD = "cloud_enabled"
        const val KEY_REPEAT = "repeat_mode"
        const val KEY_INTERVAL = "repeat_interval_sec"
    }
}
