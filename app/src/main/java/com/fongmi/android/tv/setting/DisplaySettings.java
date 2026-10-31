package com.fongmi.android.tv.setting;

import java.util.Locale;

public final class DisplaySettings {

    public static final String LANGUAGE_ENGLISH = "en";
    public static final String LANGUAGE_SIMPLIFIED = "zh-CN";
    public static final String LANGUAGE_TRADITIONAL = "zh-TW";
    private static final String[] LANGUAGES = {LANGUAGE_ENGLISH, LANGUAGE_SIMPLIFIED, LANGUAGE_TRADITIONAL};

    public static final int UI_SCALE_FOLLOW_SYSTEM = 0;
    public static final int UI_SCALE_STANDARD = 1;
    public static final int UI_SCALE_MILD_COMPACT = 2;
    public static final int UI_SCALE_COMPACT = 3;
    public static final int UI_SCALE_MORE_COMPACT = 4;
    public static final int UI_SCALE_SMALLER = 5;
    private static final int[] UI_SCALES = {UI_SCALE_FOLLOW_SYSTEM, UI_SCALE_STANDARD, UI_SCALE_MILD_COMPACT, UI_SCALE_COMPACT, UI_SCALE_MORE_COMPACT, UI_SCALE_SMALLER};

    private DisplaySettings() {
    }

    public static String normalizeLanguage(String value) {
        if (value == null || value.trim().isEmpty()) return LANGUAGE_ENGLISH;
        String tag = value.trim().replace('_', '-');
        Locale locale = Locale.forLanguageTag(tag);
        if ("en".equalsIgnoreCase(locale.getLanguage())) return LANGUAGE_ENGLISH;
        if (!"zh".equalsIgnoreCase(locale.getLanguage())) return LANGUAGE_ENGLISH;
        String script = locale.getScript();
        String country = locale.getCountry();
        if ("Hant".equalsIgnoreCase(script) || "TW".equalsIgnoreCase(country) || "HK".equalsIgnoreCase(country) || "MO".equalsIgnoreCase(country)) return LANGUAGE_TRADITIONAL;
        return LANGUAGE_SIMPLIFIED;
    }

    public static int languageIndex(String language) {
        String normalized = normalizeLanguage(language);
        for (int i = 0; i < LANGUAGES.length; i++) if (LANGUAGES[i].equals(normalized)) return i;
        return 0;
    }

    public static String languageAt(int index) {
        return LANGUAGES[index >= 0 && index < LANGUAGES.length ? index : 0];
    }

    public static int normalizeUiScale(int scale) {
        for (int option : UI_SCALES) if (option == scale) return scale;
        return UI_SCALE_FOLLOW_SYSTEM;
    }

    public static int uiScaleIndex(int scale) {
        int normalized = normalizeUiScale(scale);
        for (int i = 0; i < UI_SCALES.length; i++) if (UI_SCALES[i] == normalized) return i;
        return 0;
    }

    public static int uiScaleAt(int index) {
        return UI_SCALES[index >= 0 && index < UI_SCALES.length ? index : 0];
    }

    public static float uiScaleFactor(int scale) {
        return switch (normalizeUiScale(scale)) {
            case UI_SCALE_STANDARD -> 0.8f;
            case UI_SCALE_MILD_COMPACT -> 0.75f;
            case UI_SCALE_COMPACT -> 0.7f;
            case UI_SCALE_MORE_COMPACT -> 0.65f;
            case UI_SCALE_SMALLER -> 0.6f;
            default -> 1.0f;
        };
    }
}
