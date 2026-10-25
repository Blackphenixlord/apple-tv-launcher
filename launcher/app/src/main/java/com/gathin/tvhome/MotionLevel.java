package com.gathin.tvhome;

import java.util.Locale;

/**
 * Launcher settings, Motion: how much of the tvOS motion runs. Maps onto the two older switches every
 * view already reads (config animations and reduced_motion), so a theme that sets those keeps working.
 *
 * - FULL: springs with a slight overshoot, the lean and shine, the launch zoom, the picker's blur and pop.
 * - REDUCED: the stiff, short motion meant for weak GPUs; no overshoot, no blur, no idle shine.
 * - OFF: nothing moves; focus is shown by the glass alone.
 */
enum MotionLevel {
    FULL, REDUCED, OFF;

    /** SharedPreferences key in Config.SETTINGS_NAME, and the theme's config.json key. */
    static final String KEY = "motion";

    boolean animations() {
        return this != OFF;
    }

    boolean reduced() {
        return this == REDUCED;
    }

    /** The order the settings row cycles through. */
    MotionLevel next() {
        return values()[(ordinal() + 1) % values().length];
    }

    String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** From the two older switches: animations off wins, then reduced_motion. */
    static MotionLevel of(boolean animations, boolean reducedMotion) {
        return !animations ? OFF : reducedMotion ? REDUCED : FULL;
    }

    /** "full", "reduced" or "off" in any case; anything else (or null) gives fallback. */
    static MotionLevel parse(String value, MotionLevel fallback) {
        if (value == null) return fallback;
        switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "full": return FULL;
            case "reduced": return REDUCED;
            case "off": case "none": return OFF;
            default: return fallback;
        }
    }
}
