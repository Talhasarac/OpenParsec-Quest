package com.example.parsecdemo;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.view.InputDevice;

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

    /**
     * Best-effort identification for Touch controllers exposed to a 2D
     * Android activity. The name varies across Horizon versions, so also
     * accept Oculus/Meta's USB vendor id. A paired Xbox/PlayStation controller
     * must not be mistaken for a Touch controller because its face buttons
     * should continue to pass through as normal gamepad input.
     */
    public static boolean isTouchController(InputDevice device) {
        if (device == null) return false;
        String name = safeLower(device.getName());
        String descriptor = safeLower(device.getDescriptor());
        return device.getVendorId() == 0x2833
                || name.contains("oculus touch")
                || name.contains("quest touch")
                || name.contains("meta touch")
                || name.contains("touch controller")
                || descriptor.contains("oculus touch")
                || descriptor.contains("quest touch")
                || descriptor.contains("meta touch");
    }

    private static String safeLower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
