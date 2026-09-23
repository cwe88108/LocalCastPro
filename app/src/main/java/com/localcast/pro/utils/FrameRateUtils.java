package com.localcast.pro.utils;

import android.app.Activity;
import android.view.Display;
import android.view.WindowManager;

import androidx.preference.PreferenceManager;

/** Applies the user's casting frame-rate preference to the app window. */
public final class FrameRateUtils {

    private FrameRateUtils() {}

    /**
     * Ask Android to render this app at the selected display mode. This is a
     * request, not a guarantee: power policy and the display panel may still
     * select a lower mode. The returned value is the highest usable preset.
     */
    public static int applyCastingRefreshRate(Activity activity) {
        int requested = readRequestedFps(activity);
        Display display = activity.getWindowManager().getDefaultDisplay();
        int effective = getBestDisplayFps(display, requested);

        WindowManager.LayoutParams params = activity.getWindow().getAttributes();
        params.preferredRefreshRate = effective;
        activity.getWindow().setAttributes(params);

        if (effective < requested) {
            Logger.w("FrameRateUtils", "Display does not support " + requested
                    + "fps; requesting " + effective + "fps instead");
        } else {
            Logger.i("FrameRateUtils", "Requested display refresh rate: " + effective + "fps");
        }
        return effective;
    }

    private static int readRequestedFps(Activity activity) {
        try {
            return Integer.parseInt(PreferenceManager.getDefaultSharedPreferences(activity)
                    .getString("frame_rate", "30"));
        } catch (NumberFormatException ignored) {
            return 30;
        }
    }

    private static int getBestDisplayFps(Display display, int requested) {
        int best = 30;
        for (Display.Mode mode : display.getSupportedModes()) {
            int fps = Math.round(mode.getRefreshRate());
            if (fps <= requested && fps > best) best = fps;
        }
        return requested >= 120 && best >= 120 ? 120 : (requested >= 60 && best >= 60 ? 60 : 30);
    }
}
