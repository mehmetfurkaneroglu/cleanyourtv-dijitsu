package com.gathin.tvhome;

import android.app.AppOpsManager;
import android.app.usage.NetworkStats;
import android.app.usage.NetworkStatsManager;
import android.content.Context;
import android.net.ConnectivityManager;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseLongArray;

import java.util.Calendar;
import java.util.function.Consumer;

/**
 * The More menu's Internet row: the Wi-Fi and Ethernet bytes Android itself counts (NetworkStatsManager), for
 * today, this month and last month, and this month per app. Nothing is measured here and nothing goes on the
 * network; it reads the counters behind Settings' data usage screen. Needs usage access, granted once with
 * {@code appops set com.gathin.tvhome GET_USAGE_STATS allow}; without it {@link #read} answers null.
 */
final class DataUsage {
    private static final int[] TYPES = {ConnectivityManager.TYPE_WIFI, ConnectivityManager.TYPE_ETHERNET};

    long today;
    long month;
    long lastMonth;
    /** This month's bytes by uid, received plus sent. */
    final SparseLongArray apps = new SparseLongArray();

    private DataUsage() {
    }

    /** Reads off the main thread and answers on it; null when usage access is missing or the TV has no counters. */
    static void read(Context context, Consumer<DataUsage> done) {
        Context app = context.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            DataUsage usage = null;
            try {
                usage = load(app);
            } catch (SecurityException | IllegalStateException | android.os.RemoteException e) {
                usage = null;
            }
            DataUsage result = usage;
            main.post(() -> done.accept(result));
        }, "data-usage").start();
    }

    private static DataUsage load(Context context) throws android.os.RemoteException {
        AppOpsManager ops = context.getSystemService(AppOpsManager.class);
        if (ops == null || ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(),
                context.getPackageName()) != AppOpsManager.MODE_ALLOWED) return null;
        NetworkStatsManager nsm = context.getSystemService(NetworkStatsManager.class);
        if (nsm == null) return null;
        long now = System.currentTimeMillis() + 60_000;
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        long dayStart = c.getTimeInMillis();
        c.set(Calendar.DAY_OF_MONTH, 1);
        long monthStart = c.getTimeInMillis();
        c.add(Calendar.MONTH, -1);
        long lastStart = c.getTimeInMillis();
        DataUsage u = new DataUsage();
        for (int type : TYPES) {
            u.today += device(nsm, type, dayStart, now);
            u.month += device(nsm, type, monthStart, now);
            u.lastMonth += device(nsm, type, lastStart, monthStart);
            try (NetworkStats stats = nsm.querySummary(type, null, monthStart, now)) {
                NetworkStats.Bucket b = new NetworkStats.Bucket();
                while (stats.hasNextBucket() && stats.getNextBucket(b)) {
                    long bytes = b.getRxBytes() + b.getTxBytes();
                    u.apps.put(b.getUid(), u.apps.get(b.getUid()) + bytes);
                }
            } catch (RuntimeException e) {
                // No per-app counters for this network type (no Ethernet port): the totals still stand.
            }
        }
        return u;
    }

    private static long device(NetworkStatsManager nsm, int type, long start, long end)
            throws android.os.RemoteException {
        try {
            NetworkStats.Bucket b = nsm.querySummaryForDevice(type, null, start, end);
            return b == null ? 0 : b.getRxBytes() + b.getTxBytes();
        } catch (RuntimeException e) {
            return 0;
        }
    }
}
