package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Family mode at boot: Google TV starts its own home (launcherx stays the default Home so profiles work),
 * then this brings this launcher up in front of it. Each Google TV profile is its own Android user, and
 * each user gets BOOT_COMPLETED the first time it starts.
 *
 * Android 10+ lets a background app start an activity only in listed cases; one of them is an app whose
 * accessibility service is bound, which is why this works only with HomeKeyService enabled for the user.
 * HomeKeyService also covers Google's home when it connects, so either path is enough on its own.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        SecureSettings.ensureHomeHelper(context);
        if (!FamilyHome.isFamilyMode(context) || !FamilyHome.setupComplete(context)) return;
        FamilyHome.openOwnHome(context);
    }
}
