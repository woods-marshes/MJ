package io.github.woods_marshes.mj.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

// 顶层委托：进程内单例，文件名 "settings"
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * 应用设置仓库：音效、免责声明、解码器偏好的持久化（Preferences DataStore）。
 *
 * DataStore 的读写都是挂起操作，而无障碍服务需要在主线程同步读取
 * "是否偏好软解"，所以这里额外维护一个内存快照 [settings]
 * （由常驻收集器保持最新），服务与播放线程直接读快照即可。
 */
object SettingsRepository {

    data class Settings(
        val soundEnabled: Boolean = true,
        val disclaimerAgreed: Boolean = false,
        val preferSwDecoder: Boolean = false,
    )

    private val KEY_SOUND_ENABLED = booleanPreferencesKey("sound_enabled")
    private val KEY_DISCLAIMER_AGREED = booleanPreferencesKey("disclaimer_agreed")
    private val KEY_PREFER_SW = booleanPreferencesKey("prefer_sw_decoder")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    @Volatile
    private var started = false

    /** 应用启动 / 服务连接时调用一次（幂等）：启动常驻收集器，保持内存快照最新。 */
    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        scope.launch {
            app.dataStore.data
                .catch { emit(emptyPreferences()) }
                .collect { preferences ->
                    _settings.value = Settings(
                        soundEnabled = preferences[KEY_SOUND_ENABLED] ?: true,
                        disclaimerAgreed = preferences[KEY_DISCLAIMER_AGREED] ?: false,
                        preferSwDecoder = preferences[KEY_PREFER_SW] ?: false,
                    )
                }
        }
    }

    fun setSoundEnabled(context: Context, value: Boolean) {
        scope.launch { context.dataStore.edit { it[KEY_SOUND_ENABLED] = value } }
    }

    fun setDisclaimerAgreed(context: Context, value: Boolean) {
        scope.launch { context.dataStore.edit { it[KEY_DISCLAIMER_AGREED] = value } }
    }

    fun setPreferSwDecoder(context: Context, value: Boolean) {
        scope.launch { context.dataStore.edit { it[KEY_PREFER_SW] = value } }
    }
}
