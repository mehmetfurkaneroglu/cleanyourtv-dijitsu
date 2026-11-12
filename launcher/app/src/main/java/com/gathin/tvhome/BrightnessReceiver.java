package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Applies the day or night backlight (BrightnessSchedule) at each change and after a boot, then re-arms the alarm. */
public final class BrightnessReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!BrightnessSchedule.ACTION_APPLY.equals(action) && !Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            BrightnessSchedule.reschedule(context);
            return;
        }
        PendingResult result = goAsync();
        BrightnessSchedule.apply(context, () -> {
            BrightnessSchedule.reschedule(context);
            result.finish();
        });
    }
}
