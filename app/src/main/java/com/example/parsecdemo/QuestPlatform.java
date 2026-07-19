package com.example.parsecdemo;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import java.util.Locale;

/**
 * Small, dependency-free Horizon OS detector.
 *
 * The app intentionally remains a conventional Android activity so Horizon
 * presents it as a movable/resizable 2D panel. It must not opt into the
 * Oculus VR intent category or initialize OpenXR.
 */
public final class QuestPlatform {
    private QuestPlatform() {}

    public static boolean isQuest(Context context) {
        String manufacturer = safeLower(Build.MANUFACTURER);
        String brand = safeLower(Build.BRAND);
        String device = safeLower(Build.DEVICE);
        String model = safeLower(Build.MODEL);

        if (manufacturer.contains("oculus")
                || manufacturer.contains("meta")
                || brand.contains("oculus")
                || device.contains("hollywood")
                || model.contains("quest")) {
            return true;
        }

        PackageManager pm = context.getPackageManager();
        return pm != null && pm.hasSystemFeature("android.hardware.vr.headtracking");
    }

    private static String safeLower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
