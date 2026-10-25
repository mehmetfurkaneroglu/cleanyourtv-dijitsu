package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

/**
 * The TV's own sleep timer on CVTE boards (Dijitsu 43DS9800), reached through the firmware's TV API service
 * (com.cvte.tv.api.impl, action com.cvte.tv.api.TV_API_SERVICE) with raw binder calls, so the launcher needs no
 * copy of the vendor's AIDL. The firmware counts the time down itself, shows its own warning in the last minute
 * and puts the set into standby: the timer keeps running when Home is gone. Values are in seconds; 0 cancels.
 * Binder codes from /system/framework/cn.cvte.tv.api.jar: ITvApiManager.getTVApiSystemSleepTime = 74,
 * ITVApiSystemSleepTimeAidl set = 1, get = 2, remaining = 3.
 */
final class SleepTimer {
    static final String PACKAGE = "com.cvte.tv.api.impl";
    private static final String ACTION = "com.cvte.tv.api.TV_API_SERVICE";
    private static final String MANAGER = "com.cvte.tv.api.aidl.ITvApiManager";
    private static final String SLEEP = "com.cvte.tv.api.aidl.ITVApiSystemSleepTimeAidl";
    private static final int GET_SLEEP_TIME_API = 74;
    private static final int SET = 1;
    private static final int REMAINING = 3;

    interface Listener {
        /** Called on the main thread once the service answers, and again after it goes away (available false). */
        void onSleepTimerReady(boolean available);
    }

    private final Context context;
    private final Listener listener;
    private IBinder sleep;
    private boolean bound;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder manager) {
            sleep = sleepBinder(manager);
            listener.onSleepTimerReady(sleep != null);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            sleep = null;
            listener.onSleepTimerReady(false);
        }
    };

    SleepTimer(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
    }

    void bind() {
        if (bound) return;
        try {
            bound = context.bindService(new Intent(ACTION).setPackage(PACKAGE), connection, Context.BIND_AUTO_CREATE);
        } catch (SecurityException e) {
            bound = false;
        }
    }

    void unbind() {
        if (!bound) return;
        bound = false;
        sleep = null;
        try {
            context.unbindService(connection);
        } catch (IllegalArgumentException ignored) {
        }
    }

    boolean available() {
        return sleep != null;
    }

    /** Starts the TV's timer, or cancels it with 0. False when the service did not take it. */
    boolean set(int seconds) {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            if (sleep == null) return false;
            data.writeInterfaceToken(SLEEP);
            data.writeInt(Math.max(0, seconds));
            sleep.transact(SET, data, reply, 0);
            reply.readException();
            return reply.readInt() != 0;
        } catch (RemoteException | RuntimeException e) {
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /** Seconds until standby, or 0 when no timer runs (or the service is not there). */
    int remaining() {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            if (sleep == null) return 0;
            data.writeInterfaceToken(SLEEP);
            sleep.transact(REMAINING, data, reply, 0);
            reply.readException();
            return Math.max(0, reply.readInt());
        } catch (RemoteException | RuntimeException e) {
            return 0;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private static IBinder sleepBinder(IBinder manager) {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(MANAGER);
            manager.transact(GET_SLEEP_TIME_API, data, reply, 0);
            reply.readException();
            return reply.readStrongBinder();
        } catch (RemoteException | RuntimeException e) {
            return null;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }
}
