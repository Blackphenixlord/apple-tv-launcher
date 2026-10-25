package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The family's Google TV profiles, for the "Who's watching?" picker and the avatar in the top bar.
 *
 * An ordinary app cannot list Android users (that needs MANAGE_USERS), so the names come from config:
 * family_profiles in config.xml or a theme's config.json, one entry per profile:
 * {@code "0=Josh|#FF5E3A"}: the Android user id the profile lives in (pm list users), its name and an
 * avatar colour. The id and the colour are optional. The current profile is the entry whose id matches
 * this process's user; with no match, the name Android reports for the user (where it allows that) is
 * added in front. Plain Java, so it is unit tested.
 */
final class FamilyProfiles {
    private FamilyProfiles() { }

    /** tvOS-like avatar colours, used in order for entries that name none. */
    static final int[] PALETTE = {0xFFFF5E3A, 0xFF34C759, 0xFF0A84FF, 0xFFFFB000, 0xFFBF5AF2, 0xFFFF375F};

    static final int UNKNOWN_USER = -1;

    static final class Profile {
        final int userId;
        final String name;
        final int color;

        Profile(int userId, String name, int color) {
            this.userId = userId;
            this.name = name;
            this.color = color;
        }

        /** The letter on the avatar. */
        String initial() {
            return FamilyProfiles.initial(name);
        }
    }

    /** First letter (a whole code point, so emoji and non-Latin names work), upper-cased; "?" for none. */
    static String initial(String name) {
        if (name == null) return "?";
        String t = name.trim();
        if (t.isEmpty()) return "?";
        int cp = t.codePointAt(0);
        return new String(Character.toChars(cp)).toUpperCase(Locale.ROOT);
    }

    /** Entries that do not parse (no name) are left out on their own. */
    static List<Profile> parse(List<String> entries) {
        if (entries == null || entries.isEmpty()) return Collections.emptyList();
        List<Profile> out = new ArrayList<>();
        for (String raw : entries) {
            if (raw == null) continue;
            String s = raw.trim();
            int user = UNKNOWN_USER;
            int eq = s.indexOf('=');
            if (eq > 0) {
                try {
                    user = Integer.parseInt(s.substring(0, eq).trim());
                    s = s.substring(eq + 1).trim();
                } catch (NumberFormatException ignored) {
                    // "=" belongs to the name
                }
            }
            int color = PALETTE[out.size() % PALETTE.length];
            int bar = s.lastIndexOf('|');
            if (bar >= 0) {
                Integer parsed = parseColor(s.substring(bar + 1).trim());
                if (parsed != null) color = parsed;
                s = s.substring(0, bar).trim();
            }
            if (s.isEmpty()) continue;
            out.add(new Profile(user, s, color));
        }
        return out;
    }

    /** #RRGGBB or #AARRGGBB; null for anything else. */
    static Integer parseColor(String s) {
        if (s == null || !s.startsWith("#") || (s.length() != 7 && s.length() != 9)) return null;
        try {
            long v = Long.parseLong(s.substring(1), 16);
            return (int) (s.length() == 7 ? (0xFF000000L | v) : v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The configured profile for this Android user, or null. */
    static Profile forUser(List<Profile> profiles, int userId) {
        for (Profile p : profiles) if (p.userId == userId) return p;
        return null;
    }

    /**
     * Who the picker shows, current profile first: the configured one for userId, else one named
     * systemName (what Android calls this user, may be null), else a generic one. Then everyone else.
     */
    static List<Profile> forPicker(List<Profile> profiles, int userId, String systemName, String fallbackName) {
        List<Profile> out = new ArrayList<>();
        Profile current = forUser(profiles, userId);
        if (current == null) {
            String name = systemName != null && !systemName.trim().isEmpty() ? systemName.trim() : fallbackName;
            // An unnumbered entry with the same name is this profile, so it is not listed twice.
            for (Profile p : profiles) {
                if (p.userId == UNKNOWN_USER && p.name.equalsIgnoreCase(name)) current = new Profile(userId, p.name, p.color);
            }
            if (current == null) current = new Profile(userId, name, PALETTE[profiles.size() % PALETTE.length]);
        }
        out.add(current);
        for (Profile p : profiles) {
            if (p.userId == current.userId || p.name.equalsIgnoreCase(current.name)) continue;
            out.add(p);
        }
        return out;
    }
}
