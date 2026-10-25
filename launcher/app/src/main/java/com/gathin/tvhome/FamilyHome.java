package com.gathin.tvhome;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Process;
import android.os.SystemClock;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Log;

import java.util.List;

/**
 * Family mode: this launcher in front of Google TV's home (launcherx) instead of replacing it.
 *
 * Google TV keeps profiles, the profile switcher, per-profile recommendations and Continue Watching inside
 * launcherx, so in family mode launcherx (and the setup wizard) stay enabled and launcherx stays the
 * default Home. HomeKeyService catches the remote's Home button and opens this launcher; a double press
 * opens Google's home. Family mode is on whenever HomeKeyService runs and the default Home is not this
 * launcher: nothing to configure, and a TV set up the normal way (this launcher as default Home) behaves
 * exactly as before.
 */
final class FamilyHome {
    private FamilyHome() { }

    private static final String TAG = "TvHome.Family";
    static final String GOOGLE_HOME_PACKAGE = "com.google.android.apps.tv.launcherx";
    static final String SETUP_WIZARD_PACKAGE = "com.google.android.tungsten.setupwraith";

    /**
     * Set when the user asked for Google's home (double Home press, or the profile picker handing over to
     * Google's switcher), cleared when this launcher is opened again. While set, HomeKeyService leaves
     * launcherx in front instead of bringing this launcher back. The service runs in this process, so a
     * static is shared with MainActivity.
     */
    static volatile boolean googleHomeRequested;
    /** When this launcher was last brought to the front by family mode, to ignore the echo of its own start. */
    static volatile long ownHomeStartedAt;
    /** When Google's home was last asked for. */
    private static volatile long googleHomeRequestedAt;

    static void requestGoogleHome() {
        googleHomeRequestedAt = SystemClock.uptimeMillis();
        googleHomeRequested = true;
    }

    /**
     * This launcher is in front again (MainActivity.onResume), so Google's home no longer counts as asked for.
     * Not within a second of asking: the resume of a first Home press can land after the second press of a
     * double press, and must not cancel it.
     */
    static void launcherResumed() {
        if (SystemClock.uptimeMillis() - googleHomeRequestedAt > 1000) googleHomeRequested = false;
    }

    /** True when the default Home is something other than this launcher (launcherx, or Android's chooser). */
    static boolean isFamilyMode(Context ctx) {
        String home = defaultHomePackage(ctx);
        return home != null && !home.equals(ctx.getPackageName());
    }

    /** The package Home resolves to: a launcher, "android" for the chooser, or null if nothing claims Home. */
    static String defaultHomePackage(Context ctx) {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo ri = ctx.getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        return ri == null || ri.activityInfo == null ? null : ri.activityInfo.packageName;
    }

    /** launcherx's Home activity, else whatever non-chooser launcher is the default; null when there is none. */
    static ComponentName googleHome(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        List<ResolveInfo> all = pm.queryIntentActivities(home, 0);
        for (ResolveInfo ri : all) {
            ActivityInfo ai = ri.activityInfo;
            if (ai != null && GOOGLE_HOME_PACKAGE.equals(ai.packageName)) return new ComponentName(ai.packageName, ai.name);
        }
        ResolveInfo def = pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        if (def != null && def.activityInfo != null) {
            String pkg = def.activityInfo.packageName;
            if (!pkg.equals(ctx.getPackageName()) && !"android".equals(pkg) && !SETUP_WIZARD_PACKAGE.equals(pkg)) {
                return new ComponentName(pkg, def.activityInfo.name);
            }
        }
        return null;
    }

    /** True for the Google home activity itself (not launcherx's other screens, such as the profile switcher). */
    static boolean isGoogleHomeWindow(Context ctx, CharSequence pkg, CharSequence cls) {
        if (pkg == null || cls == null || !GOOGLE_HOME_PACKAGE.contentEquals(pkg)) return false;
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(GOOGLE_HOME_PACKAGE);
        for (ResolveInfo ri : ctx.getPackageManager().queryIntentActivities(home, 0)) {
            ActivityInfo ai = ri.activityInfo;
            if (ai == null) continue;
            // The HOME filter can sit on an activity-alias; the window then reports the real activity.
            if (ai.name.contentEquals(cls) || ai.targetActivity != null && ai.targetActivity.contentEquals(cls)) return true;
        }
        return false;
    }

    /** Opens this launcher, as the Home button would on a TV where it is the default. */
    static void openOwnHome(Context ctx) {
        googleHomeRequested = false;
        ownHomeStartedAt = SystemClock.uptimeMillis();
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .setClassName(ctx, MainActivity.class.getName())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            ctx.startActivity(i);
        } catch (RuntimeException e) {
            Log.w(TAG, "cannot open this launcher", e);
        }
    }

    /** Opens Google TV's home and lets it stay in front. False when there is none to open. */
    static boolean openGoogleHome(Context ctx) {
        ComponentName home = googleHome(ctx);
        if (home == null) return false;
        requestGoogleHome();
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .setComponent(home)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            ctx.startActivity(i);
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "cannot open " + home, e);
            googleHomeRequested = false;
            return false;
        }
    }

    /**
     * First-run setup is finished. Until then the setup wizard owns the screen and family mode must not
     * cover it. user_setup_complete is readable on the TVs tried; if a build hides it, assume finished
     * once the device is provisioned.
     */
    static boolean setupComplete(Context ctx) {
        try {
            if (Settings.Global.getInt(ctx.getContentResolver(), Settings.Global.DEVICE_PROVISIONED, 1) == 0) return false;
        } catch (RuntimeException ignored) {
            // unreadable: fall through
        }
        try {
            return Settings.Secure.getInt(ctx.getContentResolver(), "user_setup_complete", 1) != 0;
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** launcherx's own account/profile chooser (Google TV, "Choose an account"). */
    static final String GOOGLE_PROFILE_CHOOSER = "com.google.android.apps.tv.launcherx.profile.chooser.ProfileChooserActivity";

    private static final String PREFS = "family", CURRENT_PROFILE = "current_profile";

    /**
     * The Google TV profile last picked in Google's chooser (HomeKeyService sees the click), or null. On TVs
     * where profiles are Google accounts inside one Android user, that is the only way an app can know it.
     */
    static String currentProfile(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(CURRENT_PROFILE, null);
    }

    static void setCurrentProfile(Context ctx, String name) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(CURRENT_PROFILE, name).apply();
    }

    /** This process's Android user id (0 for the first profile, 10, 11... for the others). */
    @SuppressWarnings("deprecation")
    static int userId() {
        // UserHandle.getIdentifier() is hidden; hashCode() returns the same number.
        return Process.myUserHandle().hashCode();
    }

    /**
     * What Android calls this user, where it lets an ordinary app ask (Android 13 and newer answer for the
     * calling user without MANAGE_USERS on most builds); null when it refuses.
     */
    @SuppressLint("MissingPermission")   // asked without the permission on purpose; a refusal is caught below
    static String systemUserName(Context ctx) {
        try {
            UserManager um = ctx.getSystemService(UserManager.class);
            String name = um == null ? null : um.getUserName();
            return name == null || name.trim().isEmpty() ? null : name.trim();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
