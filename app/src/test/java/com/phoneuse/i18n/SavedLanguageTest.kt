package com.phoneuse.i18n

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class SavedLanguageTest {
    private val wasKorean = Lang.korean
    @After fun restore() { Lang.korean = wasKorean }

    @Test fun unsetAndUnknownPreferencesUseEnglish() {
        Lang.korean = true
        Lang.applyPreference(null)
        assertEquals("Settings", tr("Settings", "설정"))
        Lang.applyPreference("unrecognized")
        assertEquals("en", Lang.tag)
    }

    @Test fun legacyChinesePreferenceLocalizesNavigationSettingsAndTasks() {
        Lang.applyPreference("zh")
        assertEquals("zh-CN", Lang.tag)
        assertEquals("设置", tr("Settings", "설정"))
        assertEquals("自动解锁", tr("Automatic unlock", "자동 잠금 해제"))
        assertEquals("保存任务", tr("Save routine", "루틴 저장"))
        assertEquals("还需 3 项", tr("3 needed", "3개 필요"))
    }

    @Test fun switchingBackToEnglishDoesNotLeaveChineseOrKoreanText() {
        Lang.applyPreference("zh-CN")
        Lang.applyPreference("en")
        assertEquals("Save routine", tr("Save routine", "루틴 저장"))
        assertEquals("en", Lang.tag)
    }

    @Test fun unknownTextHasAnExplicitEnglishFallback() {
        Lang.applyPreference("zh-CN")
        assertEquals("Provider-specific response", tr("Provider-specific response", "응답"))
    }

    @Test fun nativeTaskWaitsAndRepeatDaysKeepTheirMeaningInChinese() {
        Lang.applyPreference("zh-CN")
        assertEquals("等待 1.25 秒后打开", tr("Wait 1.25 s, then open", "1.25초 후 열기"))
        assertEquals("每周一", tr("Mondays", "월요일"))
        assertEquals("1 小时 30 分钟", tr("1 h 30 min", "1시간 30분"))
        assertEquals("每 30 分钟 一次", tr("Once every 30 min", "30분에 한 번"))
    }
}
