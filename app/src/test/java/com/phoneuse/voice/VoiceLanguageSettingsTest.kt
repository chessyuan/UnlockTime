package com.phoneuse.voice

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.phoneuse.app.SettingsStore
import com.phoneuse.app.VoiceLanguage
import com.phoneuse.security.SecretBox
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceLanguageSettingsTest {
    private val prefs = MemoryPrefs()
    private val settings = SettingsStore(prefs, object : SecretBox {
        override fun seal(plain: String): String = error("Voice settings do not use secrets")
        override fun open(sealed: String): String? = error("Voice settings do not use secrets")
    })
    private val voiceKey = stringPreferencesKey("voice_language")

    @Test fun newAndUnrecognizedPreferencesDefaultToEnglish() = runTest {
        assertEquals(VoiceLanguage.ENGLISH, settings.voiceLanguage.first())
        prefs.edit { it[voiceKey] = "UNKNOWN" }
        assertEquals(VoiceLanguage.ENGLISH, settings.voiceLanguage.first())
    }

    @Test fun existingKoreanPreferenceSurvivesTheNewDefault() = runTest {
        prefs.edit { it[voiceKey] = "KOREAN" }
        assertEquals(VoiceLanguage.KOREAN, settings.voiceLanguage.first())
        assertEquals("KOREAN", prefs.data.first()[voiceKey])
    }

    @Test fun chineseSelectionPersistsAsItsOwnStableValue() = runTest {
        settings.setVoiceLanguage(VoiceLanguage.CHINESE)
        assertEquals("CHINESE", prefs.data.first()[voiceKey])
        assertEquals(VoiceLanguage.CHINESE, settings.voiceLanguage.first())
        assertEquals("zh-CN", settings.voiceLanguage.first().tag)
    }

    private class MemoryPrefs : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }
}
