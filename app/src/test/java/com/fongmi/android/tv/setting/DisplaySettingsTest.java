package com.fongmi.android.tv.setting;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class DisplaySettingsTest {

    @Test
    public void normalizesSupportedLanguageTags() {
        assertEquals("en", DisplaySettings.normalizeLanguage(null));
        assertEquals("en", DisplaySettings.normalizeLanguage("en-US"));
        assertEquals("zh-CN", DisplaySettings.normalizeLanguage("zh"));
        assertEquals("zh-CN", DisplaySettings.normalizeLanguage("zh_Hans_CN"));
        assertEquals("zh-TW", DisplaySettings.normalizeLanguage("zh-Hant"));
        assertEquals("zh-TW", DisplaySettings.normalizeLanguage("zh-HK"));
        assertEquals("en", DisplaySettings.normalizeLanguage("fr"));
    }

    @Test
    public void mapsLanguageIndexesSafely() {
        assertEquals(0, DisplaySettings.languageIndex("en"));
        assertEquals(1, DisplaySettings.languageIndex("zh-CN"));
        assertEquals(2, DisplaySettings.languageIndex("zh-TW"));
        assertEquals("en", DisplaySettings.languageAt(-1));
        assertEquals("en", DisplaySettings.languageAt(99));
    }

    @Test
    public void normalizesAndMapsUiScale() {
        assertEquals(0, DisplaySettings.normalizeUiScale(-1));
        assertEquals(0, DisplaySettings.normalizeUiScale(99));
        for (int index = 0; index < 6; index++) {
            int scale = DisplaySettings.uiScaleAt(index);
            assertEquals(index, DisplaySettings.uiScaleIndex(scale));
        }
        assertEquals(1.0f, DisplaySettings.uiScaleFactor(0), 0.0f);
        assertEquals(0.8f, DisplaySettings.uiScaleFactor(1), 0.0f);
        assertEquals(0.6f, DisplaySettings.uiScaleFactor(5), 0.0f);
    }
}
