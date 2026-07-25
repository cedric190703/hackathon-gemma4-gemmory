package com.gemmory.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.gemmory.inference.BackendPreference
import com.gemmory.modelinstall.ModelCatalog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

data class AppSettings(
    val backendPreference: BackendPreference = BackendPreference.AUTO,
    val modelDownloadUrl: String = ModelCatalog.default.downloadUrl,
    val allowMeteredDownload: Boolean = false,
) {
    val usesDefaultDownloadUrl: Boolean
        get() = modelDownloadUrl == ModelCatalog.default.downloadUrl
}

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore("gemmory-settings")

/** Settings boundary, so the ViewModel can be unit tested without DataStore. */
interface SettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun setBackendPreference(preference: BackendPreference)
    suspend fun setModelDownloadUrl(url: String)
    suspend fun setAllowMeteredDownload(allow: Boolean)
}

class DataStoreSettingsRepository(context: Context) : SettingsRepository {

    private val dataStore = context.applicationContext.settingsDataStore

    override val settings: Flow<AppSettings> = dataStore.data
        .catch { throwable ->
            if (throwable is IOException) emit(emptyPreferences()) else throw throwable
        }
        .map { prefs ->
            AppSettings(
                backendPreference = prefs[KEY_BACKEND]
                    ?.let { name -> BackendPreference.entries.firstOrNull { it.name == name } }
                    ?: BackendPreference.AUTO,
                modelDownloadUrl = prefs[KEY_DOWNLOAD_URL]?.takeIf { it.isNotBlank() }
                    ?: ModelCatalog.default.downloadUrl,
                allowMeteredDownload = prefs[KEY_ALLOW_METERED] == true,
            )
        }

    override suspend fun setBackendPreference(preference: BackendPreference) {
        dataStore.edit { it[KEY_BACKEND] = preference.name }
    }

    override suspend fun setModelDownloadUrl(url: String) {
        dataStore.edit { prefs ->
            val trimmed = url.trim()
            if (trimmed.isBlank()) prefs.remove(KEY_DOWNLOAD_URL) else prefs[KEY_DOWNLOAD_URL] = trimmed
        }
    }

    override suspend fun setAllowMeteredDownload(allow: Boolean) {
        dataStore.edit { it[KEY_ALLOW_METERED] = allow }
    }

    private companion object {
        val KEY_BACKEND = stringPreferencesKey("backend_preference")
        val KEY_DOWNLOAD_URL = stringPreferencesKey("model_download_url")
        val KEY_ALLOW_METERED = booleanPreferencesKey("allow_metered_download")
    }
}
