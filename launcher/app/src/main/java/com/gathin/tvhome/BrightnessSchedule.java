package com.gathin.tvhome;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Day and night backlight for the More menu's Brightness row: from the day time on the day level, from the night
 * time on the night level. One exact alarm for the next change applies it and sets the following one; a boot, an
 * update or a clock change re-arms it. In standby the firmware holds the alarm until the TV is back, and the level is
 * worked out from the clock when it fires, so a late one still sets the right level. A level set by hand with Left
 * and Right lasts until the next change.
 */
final class BrightnessSchedule {
    static final String ACTION_APPLY = "com.gathin.tvhome.BRIGHTNESS_APPLY";
    private static final String ON = "brightness_auto";
    private static final String DAY_MINUTE = "brightness_day_minute";
    private static final String DAY_LEVEL = "brightness_day_level";
    private static final String NIGHT_MINUTE = "brightness_night_minute";
    private static final String NIGHT_LEVEL = "brightness_night_level";

    boolean on;
    int dayMinute;   // minute of the day, 0..1439
    int dayLevel;    // percent, 5..100
    int nightMinute;
    int nightLevel;

    static BrightnessSchedule load(Context context) {
        SharedPreferences p = prefs(context);
        BrightnessSchedule s = new BrightnessSchedule();
        s.on = p.getBoolean(ON, true);
        s.dayMinute = p.getInt(DAY_MINUTE, 8 * 60);
        s.dayLevel = p.getInt(DAY_LEVEL, 80);
        s.nightMinute = p.getInt(NIGHT_MINUTE, 21 * 60);
        s.nightLevel = p.getInt(NIGHT_LEVEL, 15);
        return s;
    }

    void save(Context context) {
        prefs(context).edit().putBoolean(ON, on).putInt(DAY_MINUTE, dayMinute).putInt(DAY_LEVEL, dayLevel)
                .putInt(NIGHT_MINUTE, nightMinute).putInt(NIGHT_LEVEL, nightLevel).apply();
    }

    /** The level for {@code minute} of the day: day between the day and night times, night otherwise. */
    int levelAt(int minute) {
        boolean day = dayMinute <= nightMinute
                ? minute >= dayMinute && minute < nightMinute
                : minute >= dayMinute || minute < nightMinute;
        return day ? dayLevel : nightLevel;
    }

    /** Sets the level for now, when the schedule is on. */
    static void apply(Context context, Runnable done) {
        BrightnessSchedule s = load(context);
        if (!s.on) {
            done.run();
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        CvteTv.backlight(context, s.levelAt(now.getHour() * 60 + now.getMinute()), level -> done.run());
    }

    /** Sets the alarm for the next change, or clears it while the schedule is off. */
    static void reschedule(Context context) {
        AlarmManager am = context.getSystemService(AlarmManager.class);
        if (am == null) return;
        PendingIntent pi = PendingIntent.getBroadcast(context, 1,
                new Intent(ACTION_APPLY).setClass(context, BrightnessReceiver.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.cancel(pi);
        BrightnessSchedule s = load(context);
        if (!s.on) return;
        LocalDateTime now = LocalDateTime.now().withSecond(0).withNano(0);
        LocalDateTime next = null;
        for (int minute : new int[]{s.dayMinute, s.nightMinute}) {
            LocalDateTime t = now.toLocalDate().atTime(minute / 60, minute % 60);
            if (!t.isAfter(now)) t = t.plusDays(1);
            if (next == null || t.isBefore(next)) next = t;
        }
        long at = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
    }
}
