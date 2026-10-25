package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.Collections;
import java.util.Map;

/**
 * Sends the remote's Home button to this launcher. Two setups:
 *
 * - This launcher is the default Home (the normal install): on remotes that emit KEYCODE_ALL_APPS for Home
 *   (Xiaomi sticks and boxes), that key opens it; Android TV 9 routes that key only to the system-image
 *   launcher, so once the stock launcher is disabled the button does nothing. Plus the Settings button and
 *   app_keys from config. Every other key passes through untouched.
 * - Family mode (see FamilyHome): Google TV's launcherx stays enabled and stays the default Home, so its
 *   profiles keep working. Then KEYCODE_HOME opens this launcher, a double press opens Google's home, and
 *   when Google's home comes up on its own (boot, a profile switch, an app sending Home) this launcher is
 *   brought back in front. Every other key, Settings and the app buttons included, stays with Google TV.
 *
 * Enable over adb (per Android user; launcher/install-family.ps1 does it for every profile):
 *   settings put secure enabled_accessibility_services com.gathin.tvhome/.HomeKeyService
 *   settings put secure accessibility_enabled 1
 */
public class HomeKeyService extends AccessibilityService {

    /** Remote buttons that should open Settings (config.xml, settings_key_scancodes and settings_key_keycodes). */
    private int[] settingsScancodes = new int[0];
    private int[] settingsKeycodes = new int[0];
    private boolean settingsPanel;
    /** app_keys, the same map InputKeyService reads, for setups that run only this service. */
    private Map<Integer, String> appKeys = Collections.emptyMap();
    private final HomePresses presses = new HomePresses();
    /** Family mode for the Home press in progress, decided on its key-down. */
    private boolean familyPress;
    /** Times Google's home was covered recently, so a launcher that keeps coming back cannot start a loop. */
    private final long[] recentReturns = new long[3];
    private int returnIndex;

    /** Held this long, Home opens Google's home as well (a second fallback to the double press). */
    private static final long LONG_PRESS_MS = 800;
    /** In the first minutes after boot a service with no window information assumes Google's home is showing. */
    private static final long BOOT_WINDOW_MS = 3 * 60_000L;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        settingsScancodes = getResources().getIntArray(R.array.settings_key_scancodes);
        // Keycodes and the panel choice can also come from a theme; theme.sh restarts this service.
        Config config = Config.load(this);
        settingsKeycodes = config.settingsKeycodes;
        settingsPanel = config.settingsKeyPanel;
        appKeys = RemoteKeys.parseAppKeys(config.appKeys);
        // Connected at boot, after a switch to this profile, or after an update. Cover Google's home only
        // when it is what is showing; an app in front is left alone.
        if (FamilyHome.isFamilyMode(this) && FamilyHome.setupComplete(this) && googleHomeInFront()) {
            FamilyHome.openOwnHome(this);
        }
    }

    private boolean googleHomeInFront() {
        AccessibilityNodeInfo root = null;
        try {
            root = getRootInActiveWindow();
        } catch (RuntimeException ignored) {
            // no window information on this build
        }
        if (root == null) return SystemClock.elapsedRealtime() < BOOT_WINDOW_MS;
        CharSequence pkg = root.getPackageName();
        return pkg == null || FamilyHome.GOOGLE_HOME_PACKAGE.contentEquals(pkg)
                || FamilyHome.SETUP_WIZARD_PACKAGE.contentEquals(pkg) && FamilyHome.setupComplete(this);
    }

    private boolean isSettingsKey(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_SETTINGS) return true;
        for (int code : settingsScancodes) if (event.getScanCode() == code) return true;
        for (int code : settingsKeycodes) if (event.getKeyCode() == code) return true;
        return false;
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_HOME) return onHomeKey(event);
        boolean settingsKey = isSettingsKey(event);
        String app = appKeys.get(event.getKeyCode());
        boolean allApps = event.getKeyCode() == KeyEvent.KEYCODE_ALL_APPS;
        if (!settingsKey && app == null && !allApps) return false;
        // Family mode: Google TV's launcher is still there and answers these buttons itself.
        if (FamilyHome.isFamilyMode(this)) return false;
        // The stock launcher handled the remote's Settings button; with it disabled the button does nothing.
        if (settingsKey) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                Intent open = settingsPanel ? new Intent(this, QuickSettingsActivity.class)
                        : new Intent(Settings.ACTION_SETTINGS);
                startActivity(open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
            return true;
        }
        if (app != null) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) RemoteKeys.launch(this, app);
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
            Intent home = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .setPackage(getPackageName())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(home);
        }
        return true;
    }

    /**
     * KEYCODE_HOME. Outside family mode Android already sends it to this launcher (the default Home), so it
     * passes through. In family mode the whole press is consumed and handled on its release: one press opens
     * this launcher at once, a second within HomePresses.DOUBLE_PRESS_MS (or a long hold) opens Google's home.
     */
    private boolean onHomeKey(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            // During first-run setup Home belongs to the setup wizard.
            familyPress = FamilyHome.isFamilyMode(this) && FamilyHome.setupComplete(this);
        }
        if (!familyPress) return false;
        if (event.getAction() != KeyEvent.ACTION_UP) return true;
        familyPress = false;
        if (event.isCanceled()) return true;
        boolean held = event.getEventTime() - event.getDownTime() >= LONG_PRESS_MS;
        boolean dbl = presses.isDoublePress(event.getDownTime());
        if ((dbl || held) && FamilyHome.openGoogleHome(this)) {
            presses.reset();
            return true;
        }
        FamilyHome.openOwnHome(this);
        return true;
    }

    /**
     * Family mode: Google's home came to the front without being asked for (boot, a profile switch, an app
     * or the Assistant going Home). Bring this launcher back over it. launcherx's other screens, such as its
     * profile switcher, are left alone, and so is Google's home after a double press or a profile hand-over.
     */
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName();
        if (pkg == null || !FamilyHome.GOOGLE_HOME_PACKAGE.contentEquals(pkg)) return;
        if (FamilyHome.googleHomeRequested) return;
        if (!FamilyHome.isGoogleHomeWindow(this, pkg, event.getClassName())) return;
        if (!FamilyHome.isFamilyMode(this) || !FamilyHome.setupComplete(this)) return;
        long now = SystemClock.uptimeMillis();
        // Three returns within 10 s: something keeps putting Google's home back. Leave it there.
        if (recentReturns[returnIndex] != 0 && now - recentReturns[returnIndex] < 10_000L) return;
        recentReturns[returnIndex] = now;
        returnIndex = (returnIndex + 1) % recentReturns.length;
        FamilyHome.openOwnHome(this);
    }

    @Override
    public void onInterrupt() {
    }
}
