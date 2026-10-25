package com.gathin.tvhome;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

/**
 * Settings menu, Sleep timer (idea from the yoozayang/cleanMatthewsTV and kr1swang/cleanyourtv forks): the TV goes
 * to sleep after 30, 60, 90 or 120 minutes, whatever app is playing. An inexact alarm (exact ones need a permission
 * TVs don't offer) fires the receiver below, which uses the same lock-screen action as the Sleep row.
 */
public final class SleepTimer extends BroadcastReceiver {

    static final int[] MINUTES = {0, 30, 60, 90, 120};
    private static final String PREFS = "sleep_timer", ENDS_AT = "ends_at";

    /** Minutes left, rounded up; 0 when no timer is set (or it has already fired). */
    static int minutesLeft(Context ctx) {
        long end = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(ENDS_AT, 0);
        return minutesLeft(end, SystemClock.elapsedRealtime());
    }

    /** Plain Java, unit tested. */
    static int minutesLeft(long endsAt, long now) {
        if (endsAt <= now) return 0;
        return (int) ((endsAt - now + 59_999) / 60_000);
    }

    /** 0 cancels. */
    static void set(Context ctx, int minutes) {
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 0, new Intent(ctx, SleepTimer.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        if (am != null) am.cancel(pi);
        long end = minutes <= 0 ? 0 : SystemClock.elapsedRealtime() + minutes * 60_000L;
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(ENDS_AT, end).apply();
        if (end > 0 && am != null) am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, end, pi);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(ENDS_AT, 0).apply();
        SleepService.sleep();
    }
}
