package com.gathin.tvhome;

import android.Manifest;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.util.Log;

/**
 * What the launcher does with WRITE_SECURE_SETTINGS, which only adb can grant (install-family.ps1 does:
 * pm grant com.gathin.tvhome android.permission.WRITE_SECURE_SETTINGS). Without it, every method here is a no-op.
 *
 * - Self-heal (idea from the BrezhnevEugen/tv-vibehack and pasq96/cleanyourtv-fire forks): TCL firmware keeps the
 *   Home helper from starting at boot, an update or a force-stop unbinds it, and the TV can switch accessibility off.
 *   Whenever Home is shown, the helper is put back in the enabled list and bound again, keeping any other services.
 * - Ad-blocking DNS (idea from the mehmetfurkaneroglu/cleanyourtv-dijitsu fork): Android's Private DNS set to
 *   AdGuard, or back to automatic. It does not block YouTube's own ads.
 */
final class SecureSettings {
    private SecureSettings() { }

    private static final String TAG = "TvHome.Secure";
    static final String ADBLOCK_HOST = "dns.adguard-dns.com";

    static boolean canWrite(Context ctx) {
        return ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Puts HomeKeyService back in the enabled accessibility services, and turns accessibility on, when the helper is
     * not running. Removing and re-adding it makes Android bind it again now that this app is in the foreground.
     */
    static void ensureHomeHelper(Context ctx) {
        if (!canWrite(ctx) || HomeKeyService.isRunning()) return;
        ContentResolver cr = ctx.getContentResolver();
        String me = new ComponentName(ctx, HomeKeyService.class).flattenToShortString();
        try {
            String list = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            String others = without(list, ctx.getPackageName() + "/");
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, others);
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    others.isEmpty() ? me : others + ":" + me);
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            Log.i(TAG, "Home helper was not running; enabled it again");
        } catch (SecurityException e) {
            Log.w(TAG, "cannot re-enable the Home helper", e);
        }
    }

    /** The list without this app's own entries (other services kept, in order). Plain Java, unit tested. */
    static String without(String list, String ownPrefix) {
        if (list == null || list.isEmpty() || "null".equals(list)) return "";
        StringBuilder out = new StringBuilder();
        for (String part : list.split(":")) {
            String p = part.trim();
            if (p.isEmpty() || p.startsWith(ownPrefix)) continue;
            if (out.length() > 0) out.append(':');
            out.append(p);
        }
        return out.toString();
    }

    static boolean adBlockDnsOn(Context ctx) {
        ContentResolver cr = ctx.getContentResolver();
        return "hostname".equals(Settings.Global.getString(cr, "private_dns_mode"))
                && ADBLOCK_HOST.equals(Settings.Global.getString(cr, "private_dns_specifier"));
    }

    /** True when it was changed. Off goes back to Android's default, automatic (opportunistic) Private DNS. */
    static boolean setAdBlockDns(Context ctx, boolean on) {
        if (!canWrite(ctx)) return false;
        ContentResolver cr = ctx.getContentResolver();
        try {
            if (on) Settings.Global.putString(cr, "private_dns_specifier", ADBLOCK_HOST);
            Settings.Global.putString(cr, "private_dns_mode", on ? "hostname" : "opportunistic");
            return true;
        } catch (SecurityException e) {
            Log.w(TAG, "cannot change Private DNS", e);
            return false;
        }
    }
}
