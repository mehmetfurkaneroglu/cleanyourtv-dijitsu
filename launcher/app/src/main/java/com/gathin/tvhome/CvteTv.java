package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.DisplayMetrics;

/**
 * Live TV on CVTE boards (Dijitsu 43DS9800) for the Program feature, through the firmware's TV API service with raw
 * binder calls, as SleepTimer does. Binder codes from /system/framework/cn.cvte.tv.api.jar:
 * ITvApiManager getTVApiSystemSleepTime = 74, getTVApiTvChannels = 91, getTVApiSystemOnOffTime = 120;
 * ITVApiTvChannelsAidl eventTVChannelsGetChannelList(EnumInputSourceCategory) = 2, eventTVChannelsTuneChannelById(id) = 4,
 * eventTVChannelsTuneChannelByLcn(major, minor) = 5; ITVApiSystemSleepTimeAidl set(seconds) = 1;
 * ITVApiSystemOnOffTimeAidl SetOnTime(EntityCycleTime) = 1, SetOnTimeWakeUpSource(source) = 7.
 * The on timer wakes the set from standby through the RTC; EntityCycleTime is a typed list of EnumWeekDay (Monday 0)
 * and the time of day in seconds, -1 for off.
 * The number on the TV's channel list is the list position (EntityChannel.LCN, after the TV's own sorting), while
 * TuneChannelByLcn takes the number from the scan, which the channel id carries in its high bits (TV2 HD: 268 on the
 * list, 294 from the scan). So a program's number is looked up in the list and tuned by id.
 */
final class CvteTv {
    private static final String API_PACKAGE = SleepTimer.PACKAGE;
    private static final String API_ACTION = "com.cvte.tv.api.TV_API_SERVICE";
    private static final String MANAGER = "com.cvte.tv.api.aidl.ITvApiManager";
    private static final String CHANNELS = "com.cvte.tv.api.aidl.ITVApiTvChannelsAidl";
    private static final String SLEEP = "com.cvte.tv.api.aidl.ITVApiSystemSleepTimeAidl";
    private static final String ON_OFF = "com.cvte.tv.api.aidl.ITVApiSystemOnOffTimeAidl";
    private static final int GET_SLEEP_API = 74;
    private static final int GET_CHANNELS_API = 91;
    private static final int GET_ON_OFF_API = 120;
    private static final int GET_CHANNEL_LIST = 2;
    private static final int TUNE_BY_ID = 4;
    private static final int TUNE_BY_LCN = 5;
    private static final int SLEEP_SET = 1;
    private static final int SET_ON_TIME = 1;
    private static final int SET_ON_TIME_SOURCE = 7;
    private static final int DTV_SOURCE = 0;
    /** The TIF player needs a few seconds to bring DTV up; the tune is sent then and once more as a fallback. */
    private static final long[] TUNE_DELAYS_MS = {5_000, 10_000};

    private interface Api {
        /** Runs on the main thread with the manager binder; call {@code done} when finished with it. */
        void use(IBinder manager, Runnable done);
    }

    private CvteTv() {
    }

    /** DTV through the TIF player, full screen, handing over to the Live TV activity. False when not startable. */
    static boolean openDtv(Context context) {
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        try {
            context.startActivity(new Intent("com.cvte.intent.ACTION_TIF_PLAYER").addCategory(Intent.CATEGORY_DEFAULT)
                    .setPackage(API_PACKAGE).putExtra("sourceId", DTV_SOURCE).putExtra("left", 0).putExtra("top", 0)
                    .putExtra("tune", true).putExtra("width", dm.widthPixels).putExtra("height", dm.heightPixels)
                    .putExtra("toPackage", "com.cvte.tv.setting")
                    .putExtra("toActivity", "com.cvte.tv.setting.TvSettingActivity")
                    .putExtra("toAction", Intent.ACTION_MAIN).putExtra("toCategory1", "android.intent.category.TV_HOME")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Opens DTV, tunes channel {@code number} and, with {@code offMinutes} above 0, starts the TV's own sleep timer so
     * the set goes to standby when the program is over. {@code done} runs on the main thread at the end.
     */
    static void playChannel(Context context, int number, int offMinutes, Runnable done) {
        openDtv(context.getApplicationContext());
        withApi(context, (manager, finished) -> {
            Handler main = new Handler(Looper.getMainLooper());
            IBinder channels = subBinder(manager, GET_CHANNELS_API);
            for (long delay : TUNE_DELAYS_MS) main.postDelayed(() -> tune(channels, number), delay);
            if (offMinutes > 0) {
                IBinder sleep = subBinder(manager, GET_SLEEP_API);
                callInt(sleep, SLEEP, SLEEP_SET, offMinutes * 60);
            }
            main.postDelayed(finished, TUNE_DELAYS_MS[TUNE_DELAYS_MS.length - 1] + 500);
        }, done);
    }

    /**
     * The TV's power-on timer: wake from standby at {@code secondOfDay} on {@code weekDay} (Monday 0 ... Sunday 6)
     * on DTV, or switch it off with a negative {@code secondOfDay}.
     */
    static void setOnTimer(Context context, int secondOfDay, int weekDay) {
        withApi(context, (manager, finished) -> {
            IBinder onOff = subBinder(manager, GET_ON_OFF_API);
            if (onOff != null) {
                Parcel data = Parcel.obtain(), reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(ON_OFF);
                    data.writeInt(1);   // EntityCycleTime present
                    if (secondOfDay >= 0) {
                        data.writeInt(1);   // typed list: one EnumWeekDay
                        data.writeInt(1);
                        data.writeInt(weekDay);
                    } else {
                        data.writeInt(0);
                    }
                    data.writeLong(secondOfDay >= 0 ? secondOfDay : -1);
                    onOff.transact(SET_ON_TIME, data, reply, 0);
                    reply.readException();
                } catch (RemoteException | RuntimeException ignored) {
                } finally {
                    data.recycle();
                    reply.recycle();
                }
                if (secondOfDay >= 0) callInt(onOff, ON_OFF, SET_ON_TIME_SOURCE, DTV_SOURCE);
            }
            finished.run();
        }, () -> { });
    }

    /** Binds the TV API service, hands the manager to {@code api}, unbinds after it and then runs {@code done}. */
    private static void withApi(Context context, Api api, Runnable done) {
        Context app = context.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        ServiceConnection connection = new ServiceConnection() {
            private boolean over;

            @Override
            public void onServiceConnected(ComponentName name, IBinder manager) {
                api.use(manager, () -> {
                    if (over) return;
                    over = true;
                    try {
                        app.unbindService(this);
                    } catch (IllegalArgumentException ignored) {
                    }
                    done.run();
                });
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
            }
        };
        boolean bound;
        try {
            bound = app.bindService(new Intent(API_ACTION).setPackage(API_PACKAGE), connection, Context.BIND_AUTO_CREATE);
        } catch (SecurityException e) {
            bound = false;
        }
        if (!bound) main.post(done);
    }

    /** Tunes the channel the TV's list shows as {@code number}; by scan number when the list cannot be read. */
    private static boolean tune(IBinder channels, int number) {
        int id = channelId(channels, number);
        return id != -1 ? callInt(channels, CHANNELS, TUNE_BY_ID, id) : tuneByLcn(channels, number);
    }

    /** The id of the channel at {@code number} on the TV's channel list, -1 when there is none. */
    private static int channelId(IBinder channels, int number) {
        if (channels == null) return -1;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CHANNELS);
            data.writeInt(1);   // EnumInputSourceCategory present
            data.writeInt(DTV_SOURCE);   // INPUTSOURCE_TV: the current tuner list
            channels.transact(GET_CHANNEL_LIST, data, reply, 0);
            reply.readException();
            for (int i = reply.readInt(); i > 0; i--) {
                if (reply.readInt() == 0) continue;   // null entry
                // EntityChannel: channelId, LCN, serviceName, serviceType and tvCategory (Serializable), locked,
                // skipped, favorite group, scrambled, major, minor, serviceId, isHD, isBISS, satId, categoryId.
                int id = reply.readInt();
                int lcn = reply.readInt();
                if (lcn == number) return id;
                reply.readString();
                for (int k = 0; k < 2; k++) if (reply.readString() != null) reply.createByteArray();
                for (int k = 0; k < 11; k++) reply.readInt();
            }
        } catch (RemoteException | RuntimeException ignored) {
        } finally {
            data.recycle();
            reply.recycle();
        }
        return -1;
    }

    private static boolean tuneByLcn(IBinder channels, int number) {
        if (channels == null) return false;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CHANNELS);
            data.writeInt(number);
            data.writeInt(0);   // minor number: none on DVB
            channels.transact(TUNE_BY_LCN, data, reply, 0);
            reply.readException();
            return reply.readInt() != 0;
        } catch (RemoteException | RuntimeException e) {
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /** A one-int call answering a boolean (the AIDL's set methods). */
    private static boolean callInt(IBinder binder, String descriptor, int code, int value) {
        if (binder == null) return false;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(descriptor);
            data.writeInt(value);
            binder.transact(code, data, reply, 0);
            reply.readException();
            return reply.readInt() != 0;
        } catch (RemoteException | RuntimeException e) {
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /** One of the manager's per-module binders (getTVApi...). */
    private static IBinder subBinder(IBinder manager, int code) {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(MANAGER);
            manager.transact(code, data, reply, 0);
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
