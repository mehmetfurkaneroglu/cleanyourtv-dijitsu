package com.gathin.tvhome;

import android.app.AppOpsManager;
import android.app.usage.NetworkStats;
import android.app.usage.NetworkStatsManager;
import android.content.Context;
import android.net.ConnectivityManager;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.util.SparseLongArray;

import java.util.Calendar;
import java.util.function.Consumer;

/**
 * The More menu's Internet row: the Wi-Fi and Ethernet bytes Android itself counts (NetworkStatsManager), for
 * today, this month, last month and everything Android still keeps, each in total and per app. Nothing is measured
 * here and nothing goes on the network; it reads the counters behind Settings' data usage screen. Needs usage
 * access, granted once with {@code appops set com.gathin.tvhome GET_USAGE_STATS allow}; without it {@link #read}
 * answers null.
 */
final class DataUsage {
    private static final int[] TYPES = {ConnectivityManager.TYPE_WIFI, ConnectivityManager.TYPE_ETHERNET};

    /** One stretch of time: its total, and its bytes by uid (received plus sent). */
    static final class Period {
        long total;
        final SparseLongArray apps = new SparseLongArray();
    }

    final Period today = new Period();
    final Period month = new Period();
    final Period lastMonth = new Period();
    final Period all = new Period();

    private DataUsage() {
    }

    /** Reads off the main thread and answers on it; null when usage access is missing or the TV has no counters. */
    static void read(Context context, Consumer<DataUsage> done) {
        Context app = context.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            DataUsage usage;
            try {
                usage = load(app);
            } catch (SecurityException | IllegalStateException | RemoteException e) {
                usage = null;
            }
            DataUsage result = usage;
            main.post(() -> done.accept(result));
        }, "data-usage").start();
    }

    private static DataUsage load(Context context) throws RemoteException {
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
        fill(nsm, u.today, dayStart, now);
        fill(nsm, u.month, monthStart, now);
        fill(nsm, u.lastMonth, lastStart, monthStart);
        fill(nsm, u.all, 0, now);
        return u;
    }

    private static void fill(NetworkStatsManager nsm, Period p, long start, long end) throws RemoteException {
        for (int type : TYPES) {
            try {
                NetworkStats.Bucket b = nsm.querySummaryForDevice(type, null, start, end);
                if (b != null) p.total += b.getRxBytes() + b.getTxBytes();
            } catch (RuntimeException e) {
                // No counters for this network type (no Ethernet port).
            }
            try (NetworkStats stats = nsm.querySummary(type, null, start, end)) {
                NetworkStats.Bucket b = new NetworkStats.Bucket();
                while (stats.hasNextBucket() && stats.getNextBucket(b)) {
                    p.apps.put(b.getUid(), p.apps.get(b.getUid()) + b.getRxBytes() + b.getTxBytes());
                }
            } catch (RuntimeException e) {
                // As above: the other network type still counts.
            }
        }
    }
}
