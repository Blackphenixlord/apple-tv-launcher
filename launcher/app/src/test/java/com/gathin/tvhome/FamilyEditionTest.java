package com.gathin.tvhome;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Plain JVM tests for the family edition's logic: motion levels, the double Home press, profiles, the switcher match. */
public class FamilyEditionTest {

    // ---- MotionLevel ----

    @Test
    public void motionDefaultsToFullFromTheStockSwitches() {
        assertEquals(MotionLevel.FULL, MotionLevel.of(true, false));
        assertEquals(MotionLevel.REDUCED, MotionLevel.of(true, true));
        // Animations off wins over reduced motion.
        assertEquals(MotionLevel.OFF, MotionLevel.of(false, true));
        assertEquals(MotionLevel.OFF, MotionLevel.of(false, false));
    }

    @Test
    public void motionMapsBackOntoTheSwitches() {
        for (MotionLevel m : MotionLevel.values()) {
            assertEquals(m, MotionLevel.of(m.animations(), m.reduced()));
        }
        assertFalse(MotionLevel.OFF.animations());
        assertTrue(MotionLevel.REDUCED.reduced());
        assertFalse(MotionLevel.FULL.reduced());
    }

    @Test
    public void motionParsesSavedAndThemeValues() {
        assertEquals(MotionLevel.FULL, MotionLevel.parse("full", MotionLevel.OFF));
        assertEquals(MotionLevel.REDUCED, MotionLevel.parse(" Reduced ", MotionLevel.FULL));
        assertEquals(MotionLevel.OFF, MotionLevel.parse("OFF", MotionLevel.FULL));
        assertEquals(MotionLevel.OFF, MotionLevel.parse("none", MotionLevel.FULL));
        assertEquals(MotionLevel.REDUCED, MotionLevel.parse("fast", MotionLevel.REDUCED));
        assertEquals(MotionLevel.FULL, MotionLevel.parse(null, MotionLevel.FULL));
        for (MotionLevel m : MotionLevel.values()) assertEquals(m, MotionLevel.parse(m.key(), null));
    }

    @Test
    public void motionRowCyclesFullReducedOff() {
        assertEquals(MotionLevel.REDUCED, MotionLevel.FULL.next());
        assertEquals(MotionLevel.OFF, MotionLevel.REDUCED.next());
        assertEquals(MotionLevel.FULL, MotionLevel.OFF.next());
    }

    // ---- HomePresses ----

    @Test
    public void singlePressesAreNotDoubles() {
        HomePresses p = new HomePresses();
        assertFalse(p.isDoublePress(1_000));
        assertFalse(p.isDoublePress(1_000 + HomePresses.DOUBLE_PRESS_MS + 1));
        assertFalse(p.isDoublePress(10_000));
    }

    @Test
    public void secondQuickPressIsADouble() {
        HomePresses p = new HomePresses();
        assertFalse(p.isDoublePress(5_000));
        assertTrue(p.isDoublePress(5_000 + HomePresses.DOUBLE_PRESS_MS));
    }

    @Test
    public void thirdQuickPressStartsANewPair() {
        HomePresses p = new HomePresses();
        assertFalse(p.isDoublePress(0));
        assertTrue(p.isDoublePress(200));
        assertFalse(p.isDoublePress(400));
        assertTrue(p.isDoublePress(600));
    }

    @Test
    public void resetForgetsTheFirstPress() {
        HomePresses p = new HomePresses();
        assertFalse(p.isDoublePress(0));
        p.reset();
        assertFalse(p.isDoublePress(100));
    }

    @Test
    public void clockGoingBackwardsIsNotADouble() {
        HomePresses p = new HomePresses();
        assertFalse(p.isDoublePress(1_000));
        assertFalse(p.isDoublePress(900));
    }

    // ---- FamilyProfiles ----

    @Test
    public void parsesIdNameAndColour() {
        List<FamilyProfiles.Profile> ps = FamilyProfiles.parse(Arrays.asList("0=Josh|#FF5E3A", "10 = Mom | #34c759", "Alex"));
        assertEquals(3, ps.size());
        assertEquals(0, ps.get(0).userId);
        assertEquals("Josh", ps.get(0).name);
        assertEquals(0xFFFF5E3A, ps.get(0).color);
        assertEquals(10, ps.get(1).userId);
        assertEquals("Mom", ps.get(1).name);
        assertEquals(0xFF34C759, ps.get(1).color);
        assertEquals(FamilyProfiles.UNKNOWN_USER, ps.get(2).userId);
        // No colour given: the palette, in order.
        assertEquals(FamilyProfiles.PALETTE[2], ps.get(2).color);
    }

    @Test
    public void badEntriesAreLeftOutOnTheirOwn() {
        List<FamilyProfiles.Profile> ps = FamilyProfiles.parse(Arrays.asList("", "  ", "11=", "|#FFFFFF", null, "Sam|notacolour"));
        assertEquals(1, ps.size());
        assertEquals("Sam", ps.get(0).name);
        assertEquals(FamilyProfiles.PALETTE[0], ps.get(0).color);
        assertTrue(FamilyProfiles.parse(null).isEmpty());
    }

    @Test
    public void equalsSignWithoutANumberBelongsToTheName() {
        List<FamilyProfiles.Profile> ps = FamilyProfiles.parse(Collections.singletonList("Mr=Smith"));
        assertEquals("Mr=Smith", ps.get(0).name);
        assertEquals(FamilyProfiles.UNKNOWN_USER, ps.get(0).userId);
    }

    @Test
    public void initials() {
        assertEquals("J", FamilyProfiles.initial("josh"));
        assertEquals("É", FamilyProfiles.initial(" élodie"));
        assertEquals("?", FamilyProfiles.initial(""));
        assertEquals("?", FamilyProfiles.initial(null));
        // A whole code point, not half a surrogate pair.
        assertEquals("🐱", FamilyProfiles.initial("🐱 cat"));
    }

    @Test
    public void coloursParse() {
        assertEquals(Integer.valueOf(0xFF112233), FamilyProfiles.parseColor("#112233"));
        assertEquals(Integer.valueOf(0x80112233), FamilyProfiles.parseColor("#80112233"));
        assertNull(FamilyProfiles.parseColor("112233"));
        assertNull(FamilyProfiles.parseColor("#12345"));
        assertNull(FamilyProfiles.parseColor("#GG2233"));
    }

    @Test
    public void pickerPutsTheCurrentProfileFirst() {
        List<FamilyProfiles.Profile> ps = FamilyProfiles.parse(Arrays.asList("0=Josh", "10=Mom", "11=Alex"));
        List<FamilyProfiles.Profile> picker = FamilyProfiles.forPicker(ps, 10, "Ignored", "You");
        assertEquals(3, picker.size());
        assertEquals("Mom", picker.get(0).name);
        assertEquals("Josh", picker.get(1).name);
        assertEquals("Alex", picker.get(2).name);
    }

    @Test
    public void pickerUsesAndroidsNameWhenTheProfileIsNotConfigured() {
        List<FamilyProfiles.Profile> ps = FamilyProfiles.parse(Arrays.asList("0=Josh", "10=Mom"));
        List<FamilyProfiles.Profile> picker = FamilyProfiles.forPicker(ps, 12, "Alex", "You");
        assertEquals(3, picker.size());
        assertEquals("Alex", picker.get(0).name);
        assertEquals(12, picker.get(0).userId);
        // Nothing from Android either: the generic name.
        assertEquals("You", FamilyProfiles.forPicker(Collections.emptyList(), 0, null, "You").get(0).name);
        assertEquals("You", FamilyProfiles.forPicker(Collections.emptyList(), 0, "  ", "You").get(0).name);
    }

    @Test
    public void unnumberedEntryMatchingAndroidsNameIsNotListedTwice() {
        List<FamilyProfiles.Profile> ps = FamilyProfiles.parse(Arrays.asList("Josh|#123456", "Mom"));
        List<FamilyProfiles.Profile> picker = FamilyProfiles.forPicker(ps, 0, "josh", "You");
        assertEquals(2, picker.size());
        assertEquals("Josh", picker.get(0).name);
        assertEquals(0xFF123456, picker.get(0).color);
        assertEquals("Mom", picker.get(1).name);
    }

    // ---- ProfileSwitch's match on launcherx activity names ----

    @Test
    public void switcherNamesScoreAboveTheThreshold() {
        assertTrue(ProfileSwitch.score("com.google.android.apps.tv.launcherx.profile.ProfileSwitcherActivity") >= ProfileSwitch.MIN_SCORE);
        assertTrue(ProfileSwitch.score("x.profiles.chooser.ProfileChooserActivity") >= ProfileSwitch.MIN_SCORE);
        assertTrue(ProfileSwitch.score("x.ProfileSelectionActivity") >= ProfileSwitch.MIN_SCORE);
        assertTrue(ProfileSwitch.score("x.ProfileSwitcherActivity") > ProfileSwitch.score("x.ProfileChooserActivity"));
    }

    @Test
    public void otherProfileScreensDoNot() {
        assertTrue(ProfileSwitch.score("x.ProfileActivity") < ProfileSwitch.MIN_SCORE);
        assertTrue(ProfileSwitch.score("x.EditProfileActivity") < ProfileSwitch.MIN_SCORE);
        assertTrue(ProfileSwitch.score("x.AddProfileChooserActivity") < ProfileSwitch.MIN_SCORE);
        assertEquals(0, ProfileSwitch.score("x.HomeActivity"));
        assertEquals(0, ProfileSwitch.score(null));
    }
}
