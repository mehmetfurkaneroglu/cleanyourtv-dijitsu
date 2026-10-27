package com.gathin.tvhome;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Weekly programs: on the chosen days at HH:MM, open Live TV on a channel number (CvteTv) and, with a duration, put
 * the TV to sleep when it is over. Kept in the launcher's own preferences, one line per program
 * ("days;hour;minute;channel;minutes", days a bit mask with Monday as bit 0), and run by one exact alarm for the next
 * due program, set again after each run, a reboot or a clock change.
 *
 * The firmware turns wake-up alarms into plain ones, so in standby the alarm waits for the TV to come back. The next
 * program therefore also goes into the TV's own power-on timer, which wakes the set through the RTC; once it is up,
 * the alarm (or BOOT_COMPLETED) finds the program due within {@link #CATCH_UP_MINUTES} and plays it.
 */
final class ProgramSchedule {
    static final String ACTION_RUN = "com.gathin.tvhome.PROGRAM_RUN";
    private static final String PREFS = "programs";
    private static final String KEY = "list";
    /** The start (epoch minute) of the last program played, so a catch-up never plays one twice. */
    private static final String LAST_RUN = "last_run_minute";
    /** The TV's power-on timer was set from here, so clearing it later touches no timer set in the TV's menu. */
    private static final String OWNS_ON_TIMER = "owns_on_timer";
    /** How late a program may still start: a cold boot from standby takes about a minute. */
    static final int CATCH_UP_MINUTES = 5;

    static final class Program {
        int days;      // bit 0 Monday ... bit 6 Sunday
        int hour = 19;
        int minute;
        int channel = 1;
        /** Minutes until the TV goes to standby once the program started; 0 leaves it on. */
        int duration;

        boolean on(DayOfWeek day) {
            return (days & (1 << (day.getValue() - 1))) != 0;
        }

        /** "Pzt, Çar · 19:00 · Kanal 1 · 60 dk" in the TV's language. */
        /** The list row's text; {@code channelName} (null when unknown) goes in brackets after the channel number. */
        String describe(Context context, String channelName) {
            StringBuilder names = new StringBuilder();
            if (days == 0x7F) {
                names.append(context.getString(R.string.program_every_day));
            } else {
                for (DayOfWeek d : DayOfWeek.values()) {
                    if (!on(d)) continue;
                    if (names.length() > 0) names.append(", ");
                    names.append(dayName(d));
                }
            }
            String text = context.getString(R.string.program_summary, names, hour, minute, channel);
            if (channelName != null) text += " (" + channelName + ")";
            return duration > 0 ? text + " · " + context.getString(R.string.sleep_minutes, duration) : text;
        }

        private String encode() {
            return days + ";" + hour + ";" + minute + ";" + channel + ";" + duration;
        }

        private static Program decode(String line) {
            String[] f = line.split(";");
            if (f.length != 4 && f.length != 5) return null;
            try {
                Program p = new Program();
                p.days = Integer.parseInt(f[0]) & 0x7F;
                p.hour = Integer.parseInt(f[1]);
                p.minute = Integer.parseInt(f[2]);
                p.channel = Integer.parseInt(f[3]);
                p.duration = f.length == 5 ? Integer.parseInt(f[4]) : 0;
                return p.days != 0 && p.hour >= 0 && p.hour < 24 && p.minute >= 0 && p.minute < 60 && p.channel > 0
                        && p.duration >= 0 ? p : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    private ProgramSchedule() {
    }

    static String dayName(DayOfWeek day) {
        return day.getDisplayName(TextStyle.SHORT, Locale.getDefault());
    }

    static List<Program> load(Context context) {
        List<Program> list = new ArrayList<>();
        String all = prefs(context).getString(KEY, "");
        for (String line : all.split("\n")) {
            Program p = line.isEmpty() ? null : Program.decode(line);
            if (p != null) list.add(p);
        }
        return list;
    }

    static void save(Context context, List<Program> list) {
        StringBuilder all = new StringBuilder();
        for (Program p : list) all.append(p.encode()).append('\n');
        prefs(context).edit().putString(KEY, all.toString()).apply();
        reschedule(context);
    }

    /**
     * The program to play now: started at most {@link #CATCH_UP_MINUTES} ago and not played yet (the latest one when
     * several are due). Marks it played. Null when nothing is due.
     */
    static Program takeDue(Context context) {
        LocalDateTime now = LocalDateTime.now().withSecond(0).withNano(0);
        long last = prefs(context).getLong(LAST_RUN, 0);
        Program due = null;
        long dueMinute = 0;
        for (Program p : load(context)) {
            for (int back = 0; back <= CATCH_UP_MINUTES; back++) {
                LocalDateTime t = now.minusMinutes(back);
                if (!p.on(t.getDayOfWeek()) || t.getHour() != p.hour || t.getMinute() != p.minute) continue;
                long minute = epochMinute(t);
                if (minute > last && minute > dueMinute) {
                    due = p;
                    dueMinute = minute;
                }
            }
        }
        if (due != null) prefs(context).edit().putLong(LAST_RUN, dueMinute).apply();
        return due;
    }

    /** A program starts within the catch-up window around now, so whatever the TV shows is the program's to pick. */
    static boolean inWindow(Context context) {
        LocalDateTime now = LocalDateTime.now().withSecond(0).withNano(0);
        for (Program p : load(context)) {
            for (int m = -CATCH_UP_MINUTES; m <= 1; m++) {
                LocalDateTime t = now.plusMinutes(m);
                if (p.on(t.getDayOfWeek()) && t.getHour() == p.hour && t.getMinute() == p.minute) return true;
            }
        }
        return false;
    }

    /** Sets the one alarm and the TV's power-on timer for the next due program, or clears both without one. */
    static void reschedule(Context context) {
        AlarmManager am = context.getSystemService(AlarmManager.class);
        if (am == null) return;
        PendingIntent pi = PendingIntent.getBroadcast(context, 0,
                new Intent(ACTION_RUN).setClass(context, ProgramReceiver.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        LocalDateTime next = nextRun(load(context), LocalDateTime.now());
        am.cancel(pi);
        SharedPreferences prefs = prefs(context);
        if (next == null) {
            if (prefs.getBoolean(OWNS_ON_TIMER, false)) {
                CvteTv.setOnTimer(context, -1, 0);
                prefs.edit().putBoolean(OWNS_ON_TIMER, false).apply();
            }
            return;
        }
        long at = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        // Android 12+ can deny exact alarms; a few minutes late beats not at all. This TV runs Android 11.
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        }
        CvteTv.setOnTimer(context, next.getHour() * 3600 + next.getMinute() * 60, next.getDayOfWeek().getValue() - 1);
        prefs.edit().putBoolean(OWNS_ON_TIMER, true).apply();
    }

    /** The next run after {@code now} (the next whole minute on), or null without programs. */
    static LocalDateTime nextRun(List<Program> list, LocalDateTime now) {
        LocalDateTime from = now.withSecond(0).withNano(0).plusMinutes(1);
        LocalDateTime best = null;
        for (Program p : list) {
            for (int i = 0; i <= 7; i++) {
                LocalDateTime t = from.toLocalDate().plusDays(i).atTime(p.hour, p.minute);
                if (t.isBefore(from) || !p.on(t.getDayOfWeek())) continue;
                if (best == null || t.isBefore(best)) best = t;
                break;
            }
        }
        return best;
    }

    private static long epochMinute(LocalDateTime t) {
        return t.atZone(ZoneId.systemDefault()).toEpochSecond() / 60;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
