package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Plays a due program (ProgramSchedule) and sets the alarm for the next one. A boot, an update of the launcher or a
 * clock change re-arms the alarm too, and a boot also plays a program the TV's power-on timer woke the set for.
 * The Live TV start counts as a background activity start, which Android 10+ allows here because HomeKeyService is
 * an accessibility service bound by the system.
 */
public final class ProgramReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        boolean play = ProgramSchedule.ACTION_RUN.equals(action) || Intent.ACTION_BOOT_COMPLETED.equals(action);
        ProgramSchedule.Program due = play ? ProgramSchedule.takeDue(context) : null;
        if (due == null) {
            ProgramSchedule.reschedule(context);
            return;
        }
        PendingResult result = goAsync();
        CvteTv.playChannel(context, due.channel, due.channelId, due.channelName, due.duration, () -> {
            ProgramSchedule.reschedule(context);
            result.finish();
        });
    }
}
