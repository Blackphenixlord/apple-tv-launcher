package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The family's Google TV profiles, for the "Who's watching?" picker and the avatar in the top bar.
 *
 * An ordinary app can neither list Android users (MANAGE_USERS) nor see Google TV's accounts, so the names
 * come from config: family_profiles in family.json (install-family.ps1), config.xml or a theme's
 * config.json, one entry per profile: {@code "Alex|#EC407A"}, or {@code "10=Mom|#34C759"} where a
 * profile is its own Android user (the id from pm list users). The id and the colour are optional.
 *
 * Which one is current: on Google TV builds where profiles are Android users, the entry whose id matches
 * this process's user. Where they are Google accounts inside one user (the TCL G08: all four accounts in
 * user 0), the profile last picked in Google TV's own chooser, which HomeKeyService notes; until then no
 * profile is marked. Plain Java, so it is unit tested.
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
        /** The profile watching now, as far as the launcher knows. */
        final boolean current;

        Profile(int userId, String name, int color) {
            this(userId, name, color, false);
        }

        Profile(int userId, String name, int color, boolean current) {
            this.userId = userId;
            this.name = name;
            this.color = color;
            this.current = current;
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
     * Who the picker shows, in the configured order, with the current one marked: the entry configured for
     * userId, else the entry named currentName (the last pick in Google TV's chooser, or Android's name for
     * the user; may be null). With nothing configured at all, one profile named currentName (else
     * fallbackName) for this user. With profiles configured but none known to be current, none is marked.
     */
    static List<Profile> forPicker(List<Profile> profiles, int userId, String currentName, String fallbackName) {
        String name = currentName != null && !currentName.trim().isEmpty() ? currentName.trim() : null;
        if (profiles.isEmpty()) {
            return Collections.singletonList(new Profile(userId, name != null ? name : fallbackName, PALETTE[0], true));
        }
        Profile match = forUser(profiles, userId);
        if (match == null && name != null) {
            for (Profile p : profiles) if (p.name.equalsIgnoreCase(name)) match = p;
        }
        List<Profile> out = new ArrayList<>();
        for (Profile p : profiles) out.add(new Profile(p.userId, p.name, p.color, p == match));
        return out;
    }

    /** The marked profile, or null. */
    static Profile current(List<Profile> profiles) {
        for (Profile p : profiles) if (p.current) return p;
        return null;
    }

    /**
     * The profile a click in Google TV's chooser was on, from the texts the click carried (the avatar's name,
     * often with the account's address): the longest profile name found as a whole word, or null.
     */
    static Profile matchText(List<Profile> profiles, List<CharSequence> texts) {
        Profile best = null;
        for (CharSequence t : texts) {
            if (t == null) continue;
            String lower = t.toString().toLowerCase(Locale.ROOT);
            for (Profile p : profiles) {
                String n = p.name.toLowerCase(Locale.ROOT);
                int at = lower.indexOf(n);
                if (at < 0) continue;
                boolean startOk = at == 0 || !Character.isLetterOrDigit(lower.charAt(at - 1));
                int end = at + n.length();
                boolean endOk = end == lower.length() || !Character.isLetterOrDigit(lower.charAt(end));
                if (startOk && endOk && (best == null || n.length() > best.name.length())) best = p;
            }
        }
        return best;
    }
}
