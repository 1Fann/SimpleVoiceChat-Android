package cn.fan.voicechat.android;

import java.util.Locale;

public final class NativePlatform {
    private NativePlatform() {
    }

    public static boolean isAndroidArm64() {
        try {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
            return os.contains("linux") && (arch.equals("aarch64") || arch.equals("arm64"))
                    && (present("FCL_NATIVEDIR") || present("POJAV_NATIVEDIR")
                    || present("ANDROID_ROOT"));
        } catch (SecurityException ignored) {
            return false;
        }
    }

    private static boolean present(String name) {
        String value = System.getenv(name);
        return value != null && !value.trim().isEmpty();
    }
}
