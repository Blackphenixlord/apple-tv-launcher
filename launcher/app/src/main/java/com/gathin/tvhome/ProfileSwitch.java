package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.util.Log;

import java.util.Locale;

/**
 * Hands "Who's watching?" over to Google TV's own profile switcher. Switching Android users takes
 * system permissions (UserManager / ActivityManager.switchUser) that an ordinary app cannot hold, so the
 * launcher never switches profiles itself: it opens the screen Google TV uses for that, in this order:
 *
 * 1. profile_switcher from family.json, a theme or config.xml: "package/.Activity" or an intent action,
 *    once the TV has shown which one works (INSTALL-FAMILY.md, "Find the profile switcher").
 * 2. An exported, enabled launcherx activity whose name says it picks or switches profiles.
 * 3. Neither: Google's home, where the profile avatar at the top right opens the switcher; the caller
 *    shows directions for that.
 */
final class ProfileSwitch {
    private ProfileSwitch() { }

    private static final String TAG = "TvHome.Profiles";

    enum Result { SWITCHER, GOOGLE_HOME, NOTHING }

    /** The score a launcherx activity needs before it is opened as the switcher. */
    static final int MIN_SCORE = 4;

    static Result open(Context ctx, String configured) {
        Intent switcher = switcherIntent(ctx, configured);
        if (switcher != null) {
            // While Google's switcher is up (and after it closes onto Google's home), family mode leaves it there.
            FamilyHome.googleHomeRequested = true;
            try {
                ctx.startActivity(switcher);
                return Result.SWITCHER;
            } catch (RuntimeException e) {
                Log.w(TAG, "profile switcher refused: " + switcher, e);
                FamilyHome.googleHomeRequested = false;
            }
        }
        return FamilyHome.openGoogleHome(ctx) ? Result.GOOGLE_HOME : Result.NOTHING;
    }

    /** The switcher to open, already checked to resolve to an exported activity; null when there is none. */
    static Intent switcherIntent(Context ctx, String configured) {
        PackageManager pm = ctx.getPackageManager();
        if (configured != null && !configured.trim().isEmpty()) {
            String c = configured.trim();
            Intent i;
            if (c.contains("/")) {
                ComponentName cn = ComponentName.unflattenFromString(c);
                i = cn == null ? null : new Intent(Intent.ACTION_MAIN).setComponent(cn);
            } else {
                i = new Intent(c);
            }
            if (i != null && usable(pm, i)) return i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Log.w(TAG, "profile_switcher " + c + " does not resolve to an exported activity");
        }
        ComponentName found = findInLauncher(pm);
        return found == null ? null
                : new Intent(Intent.ACTION_MAIN).setComponent(found).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    private static boolean usable(PackageManager pm, Intent i) {
        ResolveInfo ri = pm.resolveActivity(i, 0);
        return ri != null && ri.activityInfo != null && ri.activityInfo.exported && ri.activityInfo.enabled
                && ri.activityInfo.permission == null;
    }

    /** launcherx's best-named profile picker among the activities it lets other apps start. */
    static ComponentName findInLauncher(PackageManager pm) {
        PackageInfo info;
        try {
            info = pm.getPackageInfo(FamilyHome.GOOGLE_HOME_PACKAGE, PackageManager.GET_ACTIVITIES);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
        if (info.activities == null) return null;
        ActivityInfo best = null;
        // "profile" alone is not enough (a profile settings screen); the name has to say switch or pick.
        int bestScore = MIN_SCORE - 1;
        for (ActivityInfo ai : info.activities) {
            if (!ai.exported || !ai.enabled || ai.permission != null) continue;
            int score = score(ai.name);
            if (score > bestScore) {
                best = ai;
                bestScore = score;
            }
        }
        return best == null ? null : new ComponentName(best.packageName, best.name);
    }

    /** How strongly an activity's class name reads as a profile picker; 0 for not at all. Plain Java, unit tested. */
    static int score(String className) {
        if (className == null) return 0;
        String n = className.toLowerCase(Locale.ROOT);
        if (!n.contains("profile")) return 0;
        int s = 1;
        if (n.contains("switch")) s += 4;
        if (n.contains("chooser") || n.contains("picker") || n.contains("selector") || n.contains("select")) s += 3;
        if (n.contains("whoswatching") || n.contains("who_is_watching")) s += 5;
        // Editing or creating a profile is not switching.
        if (n.contains("edit") || n.contains("create") || n.contains("add") || n.contains("delete")
                || n.contains("setup") || n.contains("avatar")) s -= 3;
        return Math.max(0, s);
    }
}
