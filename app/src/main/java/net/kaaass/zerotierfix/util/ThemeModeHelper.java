package net.kaaass.zerotierfix.util;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.preference.PreferenceManager;

/**
 * 应用主题模式工具。
 */
public final class ThemeModeHelper {
    public static final String MODE_SYSTEM = "system";
    public static final String MODE_LIGHT = "light";
    public static final String MODE_DARK = "dark";

    private ThemeModeHelper() {
    }

    /**
     * 按设置应用主题模式。
     */
    public static void applyThemeMode(Context context) {
        SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(context);
        String mode = pref.getString(Constants.PREF_UI_THEME_MODE, MODE_SYSTEM);
        AppCompatDelegate.setDefaultNightMode(toNightMode(mode));
    }

    /**
     * 将字符串模式转换为 AppCompat 主题常量。
     */
    private static int toNightMode(String mode) {
        if (MODE_LIGHT.equals(mode)) {
            return AppCompatDelegate.MODE_NIGHT_NO;
        }
        if (MODE_DARK.equals(mode)) {
            return AppCompatDelegate.MODE_NIGHT_YES;
        }
        return AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
    }
}
