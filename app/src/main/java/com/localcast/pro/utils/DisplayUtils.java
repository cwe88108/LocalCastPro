package com.localcast.pro.utils;

import android.content.Context;
import android.graphics.Point;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;

/**
 * 获取设备真实物理屏幕尺寸（4K 电视不能只用 DisplayMetrics，否则会是 1080p UI 模式）。
 */
public final class DisplayUtils {

    private DisplayUtils() {}

    public static Point getPhysicalScreenSize(Context context) {
        Point size = new Point();
        Context app = context.getApplicationContext();
        WindowManager wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) {
            DisplayMetrics dm = app.getResources().getDisplayMetrics();
            size.x = dm.widthPixels;
            size.y = dm.heightPixels;
            return size;
        }

        Display display = wm.getDefaultDisplay();
        if (display == null) {
            DisplayMetrics dm = app.getResources().getDisplayMetrics();
            size.x = dm.widthPixels;
            size.y = dm.heightPixels;
            return size;
        }

        // API 23+：优先使用 Display.Mode 物理分辨率（4K 电视常见 3840x2160）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Display.Mode mode = display.getMode();
            if (mode != null && mode.getPhysicalWidth() > 0 && mode.getPhysicalHeight() > 0) {
                size.x = mode.getPhysicalWidth();
                size.y = mode.getPhysicalHeight();
                return size;
            }
        }

        display.getRealSize(size);
        if (size.x <= 0 || size.y <= 0) {
            DisplayMetrics dm = app.getResources().getDisplayMetrics();
            size.x = dm.widthPixels;
            size.y = dm.heightPixels;
        }
        return size;
    }
}
