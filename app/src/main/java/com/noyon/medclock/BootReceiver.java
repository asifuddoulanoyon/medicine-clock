package com.noyon.medclock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Books the next alarm again after the phone restarts, the app updates or the clock changes. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        Scheduler.scheduleNext(c);
    }
}
