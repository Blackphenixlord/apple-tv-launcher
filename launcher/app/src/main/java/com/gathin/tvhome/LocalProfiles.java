package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The launcher's own profiles ("Who's watching?"), independent of Google TV's: each person gets their own
 * dock and app order, hidden apps, background, sounds and motion. Apps keep their own logins; only what the
 * launcher shows and how it looks changes.
 *
 * Every per-person setting lives in a SharedPreferences file or a files/ file the launcher already had; with a
 * profile picked, those names get the profile's key as a suffix ("launcher_settings__alex"). The first time a
 * profile is used it starts as a copy of the settings in use before profiles existed, so nobody starts blank.
 * With no profile picked (or none configured) the original names are used, as before.
 */
final class LocalProfiles {
    private LocalProfiles() { }

    private static final String TAG = "TvHome.Profiles";
    /** Global: which profile is current, and which have been set up. Never per profile itself. */
    private static final String STATE = "profiles_state", CURRENT = "current", SEEDED = "seeded_";

    /** Per-person SharedPreferences: background, launcher settings (motion, wallpaper), sounds, top icons. */
    static final String[] PREFS = {"aerial_backdrop", Config.SETTINGS_NAME, "nav_sound", "top_buttons", "wallpaper_rotation"};
    /** Per-person files under files/: tile order (dock included) and the App Library's added and hidden apps. */
    static final String[] FILES = {"order.txt", "library_apps.txt", "library_home_removed.txt"};

    /** A file-name-safe key for a profile name: lower case, letters and digits, the rest as '_'. Unit tested. */
    static String key(String name) {
        if (name == null) return "";
        String k = name.trim().toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "_");
        return k.replaceAll("^_+|_+$", "");
    }

    /** base with the current profile's suffix, or base itself with none. Unit tested. */
    static String scoped(String base, String profileName) {
        String k = key(profileName);
        return k.isEmpty() ? base : base + "__" + k;
    }

    static String current(Context ctx) {
        return state(ctx).getString(CURRENT, null);
    }

    private static SharedPreferences state(Context ctx) {
        return ctx.getSharedPreferences(STATE, Context.MODE_PRIVATE);
    }

    /** This profile's SharedPreferences for base (one of {@link #PREFS}). */
    static SharedPreferences prefs(Context ctx, String base) {
        return ctx.getSharedPreferences(scoped(base, current(ctx)), Context.MODE_PRIVATE);
    }

    /** This profile's copy of files/name (one of {@link #FILES}). */
    static File file(Context ctx, String name) {
        return new File(ctx.getFilesDir(), scoped(name, current(ctx)));
    }

    /** Makes name the current profile; the first time, its settings start as a copy of the shared ones. */
    static void setCurrent(Context ctx, String name) {
        String k = key(name);
        if (k.isEmpty()) return;
        SharedPreferences s = state(ctx);
        if (!s.getBoolean(SEEDED + k, false)) {
            seed(ctx, name);
            s.edit().putBoolean(SEEDED + k, true).apply();
        }
        s.edit().putString(CURRENT, name).commit();
    }

    private static void seed(Context ctx, String name) {
        for (String base : PREFS) {
            Map<String, ?> all = ctx.getSharedPreferences(base, Context.MODE_PRIVATE).getAll();
            SharedPreferences.Editor e = ctx.getSharedPreferences(scoped(base, name), Context.MODE_PRIVATE).edit();
            for (Map.Entry<String, ?> en : all.entrySet()) put(e, en.getKey(), en.getValue());
            e.commit();
        }
        for (String f : FILES) {
            File from = new File(ctx.getFilesDir(), f);
            if (!from.isFile()) continue;
            try {
                Files.copy(from.toPath(), new File(ctx.getFilesDir(), scoped(f, name)).toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                Log.w(TAG, "cannot copy " + f + " for " + name, e);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void put(SharedPreferences.Editor e, String k, Object v) {
        if (v instanceof Boolean) e.putBoolean(k, (Boolean) v);
        else if (v instanceof Integer) e.putInt(k, (Integer) v);
        else if (v instanceof Long) e.putLong(k, (Long) v);
        else if (v instanceof Float) e.putFloat(k, (Float) v);
        else if (v instanceof String) e.putString(k, (String) v);
        else if (v instanceof Set) e.putStringSet(k, (Set<String>) v);
    }
}
