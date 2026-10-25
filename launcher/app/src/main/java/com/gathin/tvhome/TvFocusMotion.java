package com.gathin.tvhome;

import android.animation.Animator;
import android.animation.Keyframe;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

/**
 * The tvOS focus engine's motion for one icon, with no highlight box: the icon itself is the focus.
 *
 * - Focus: scales to {@link #SCALE} in 280 ms on cubic-bezier(0.25, 1, 0.5, 1), rises a few dp and lifts onto
 *   a soft shadow (translationZ), so it floats above its neighbours.
 * - Unfocus: back to 1x, flat, in 200 ms on a decelerating curve.
 * - Both run at once on the two icons, since each tile animates its own focus change.
 * - Parallax: the icon leans up to {@link #TILT_DEG} towards the D-pad direction and settles level, while a
 *   radial specular highlight starts at the edge it leaned to and drifts to rest near the top.
 * - Scaling never touches layout bounds, so neighbours stay put. A press at a row's end nudges the icon
 *   ({@link #bump}): the "resistance" tvOS shows when there is nowhere to go.
 *
 * Every animation starts from the view's current values and cancels the one before it, so a fast run of
 * key presses retargets smoothly instead of stacking or restarting. The scale runs on a hardware layer.
 *
 * Full motion ({@link #springs}): the scale and the lift are springs instead of timed curves, so the icon
 * overshoots a touch and settles naturally, and a press mid-move carries its velocity into the next target.
 * Once it has settled, the highlight keeps drifting slowly over the focused icon ({@link #shimmer}), the
 * tvOS "light moving over the glass". The springs live on the view (one set per tile, made once), and the
 * drift is one animator for the whole launcher, so nothing is allocated per frame.
 */
final class TvFocusMotion {
    private TvFocusMotion() { }

    /** tvOS scales a focused icon about 15-20%. */
    static final float SCALE = 1.18f;
    private static final float LIFT_DP = 6f;
    private static final float ELEVATION_DP = 18f;
    private static final float TILT_DEG = 10f;
    private static final long FOCUS_MS = 280;
    private static final long UNFOCUS_MS = 200;
    private static final long TILT_MS = 620;
    private static final float SHINE_ALPHA = 0.85f;

    private static final Interpolator FOCUS_EASE = new PathInterpolator(0.25f, 1f, 0.5f, 1f);
    private static final Interpolator UNFOCUS_EASE = new DecelerateInterpolator(1.5f);
    private static final Interpolator SETTLE_EASE = new DecelerateInterpolator(1.8f);

    /** Full motion: springs for scale and lift, and the idle shine drift. Set by MainActivity from Settings, Motion. */
    static boolean springs;

    /** Focus: a little overshoot (about 8% of the growth) and a natural settle in ~350 ms. */
    private static final float FOCUS_STIFFNESS = 380f;
    private static final float FOCUS_DAMPING = 0.62f;
    /** Losing focus: settles without bouncing below its resting size. */
    private static final float UNFOCUS_STIFFNESS = 520f;
    private static final float UNFOCUS_DAMPING = 1f;

    /** The last D-pad direction, set by MainActivity.dispatchKeyEvent; 0 when focus did not move by key. */
    static int lastDirection;

    static void noteKey(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) return;
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_DOWN:
                lastDirection = event.getKeyCode();
                break;
            default:
        }
    }

    static void apply(View card, View banner, View glare, boolean focused, float density) {
        apply(card, banner, glare, focused, density, 0f);
    }

    /**
     * How far an icon at the end of the dock moves inward as it grows: just enough that its outer edge stays
     * EDGE_CLEARANCE_DP inside the dock's glass band (24dp from the icons, DockDecoration). With today's tile sizes
     * that is little or nothing; moving further would push it into its neighbour.
     */
    static float edgeShift(View card) {
        float density = card.getResources().getDisplayMetrics().density;
        float grow = card.getWidth() * (SCALE - 1f) / 2f;
        return Math.max(0f, grow - (24f - EDGE_CLEARANCE_DP) * density);
    }

    private static final float EDGE_CLEARANCE_DP = 12f;

    /** shiftX: a sideways move while focused, for the icons at the ends of the dock (see {@link #edgeShift}). */
    static void apply(View card, View banner, View glare, boolean focused, float density, float shiftX) {
        card.setCameraDistance(12000f * density);
        float s = focused ? SCALE : 1f;
        long ms = focused ? FOCUS_MS : UNFOCUS_MS;
        Interpolator ease = focused ? FOCUS_EASE : UNFOCUS_EASE;
        float restX = focused ? shiftX : 0f;
        Object nudge = card.getTag(R.id.motion_tx);
        if (nudge instanceof Animator) ((Animator) nudge).cancel();
        card.setTag(R.id.motion_scale_y, restX);   // where a bump at the row's end springs back to
        if (springs) {
            card.animate().translationX(restX)
                    .translationZ(focused ? ELEVATION_DP * density : 0f)
                    .setDuration(ms).setInterpolator(ease).setStartDelay(0).start();
            springTo(card, s, focused ? -LIFT_DP * density : 0f, focused);
        } else {
            card.animate().scaleX(s).scaleY(s).translationX(restX)
                    .translationY(focused ? -LIFT_DP * density : 0f)
                    .translationZ(focused ? ELEVATION_DP * density : 0f)
                    .setDuration(ms).setInterpolator(ease).setStartDelay(0)
                    .withLayer().start();
        }

        // Lean direction: the icon tips towards the way focus moved; the artwork and the light follow that edge.
        float rx = 0f, ry = 0f, dx = 0f, dy = 0f;
        if (focused) {
            switch (lastDirection) {
                case KeyEvent.KEYCODE_DPAD_RIGHT: ry = -TILT_DEG; dx = 1f; break;
                case KeyEvent.KEYCODE_DPAD_LEFT: ry = TILT_DEG; dx = -1f; break;
                case KeyEvent.KEYCODE_DPAD_DOWN: rx = TILT_DEG; dy = 1f; break;
                case KeyEvent.KEYCODE_DPAD_UP: rx = -TILT_DEG; dy = -1f; break;
                default:
            }
        }
        tilt(card, rx, ry, focused);

        // The artwork drifts a touch inside its frame while lifted: the tvOS parallax at rest.
        banner.animate().cancel();
        // No sideways start for the artwork: at that instant it has not grown yet, so any shift showed the
        // tile's dark background along one edge. It only grows into its frame.
        banner.animate().scaleX(focused ? 1.04f : 1f).scaleY(focused ? 1.04f : 1f).translationX(0f)
                .setDuration(focused ? TILT_MS : UNFOCUS_MS).setInterpolator(focused ? SETTLE_EASE : UNFOCUS_EASE)
                .start();

        shine(card, glare, focused, dx, dy);
    }

    /** The round top-bar buttons scale less than an icon: they sit 10dp apart. */
    private static final float BUTTON_SCALE = 1.15f;
    private static final float BUTTON_LIFT_DP = 3f;
    private static final float BUTTON_ELEVATION_DP = 10f;

    /**
     * The same focus motion for a round top-bar button: scale, lift onto a shadow, the lean and settle, and
     * the highlight, which is the button's foreground ({@link ShineDrawable}) since it has no child views.
     */
    static void applyButton(View button, boolean focused, float density) {
        button.setCameraDistance(12000f * density);
        // A wide capsule (the clock) grows by as many pixels as a circle does, not by the same fraction,
        // or it runs into the button next to it.
        int w = Math.max(1, button.getWidth()), h = button.getHeight();
        float grow = (BUTTON_SCALE - 1f) * Math.min(1f, (float) h / w);
        float s = focused ? 1f + grow : 1f;
        button.animate().scaleX(s).scaleY(s)
                .translationY(focused ? -BUTTON_LIFT_DP * density : 0f)
                .translationZ(focused ? BUTTON_ELEVATION_DP * density : 0f)
                .setDuration(focused ? FOCUS_MS : UNFOCUS_MS)
                .setInterpolator(focused ? FOCUS_EASE : UNFOCUS_EASE).setStartDelay(0)
                .withLayer().start();

        float rx = 0f, ry = 0f, dx = 0f, dy = 0f;
        if (focused) {
            switch (lastDirection) {
                case KeyEvent.KEYCODE_DPAD_RIGHT: ry = -TILT_DEG; dx = 1f; break;
                case KeyEvent.KEYCODE_DPAD_LEFT: ry = TILT_DEG; dx = -1f; break;
                case KeyEvent.KEYCODE_DPAD_DOWN: rx = TILT_DEG; dy = 1f; break;
                case KeyEvent.KEYCODE_DPAD_UP: rx = -TILT_DEG; dy = -1f; break;
                default:
            }
        }
        tilt(button, rx, ry, focused);

        if (!(button.getForeground() instanceof ShineDrawable)) return;
        ShineDrawable shine = (ShineDrawable) button.getForeground();
        Object prev = button.getTag(R.id.motion_scale_x);
        if (prev instanceof Animator) ((Animator) prev).cancel();
        ObjectAnimator a;
        if (focused) {

            ShineDrawable.SHINE_X.set(shine, dx * w * 0.35f);
            ShineDrawable.SHINE_Y.set(shine, dy == 0f ? -h * 0.22f : dy * h * 0.35f);
            a = ObjectAnimator.ofPropertyValuesHolder(shine,
                    PropertyValuesHolder.ofFloat(ShineDrawable.SHINE_X, 0f),
                    PropertyValuesHolder.ofFloat(ShineDrawable.SHINE_Y, -h * 0.22f),
                    PropertyValuesHolder.ofInt(ShineDrawable.ALPHA, Math.max(shine.getAlpha(), 128),
                            Math.round(255 * SHINE_ALPHA)));
            a.setDuration(TILT_MS);
            a.setInterpolator(SETTLE_EASE);
        } else {
            a = ObjectAnimator.ofPropertyValuesHolder(shine, PropertyValuesHolder.ofInt(ShineDrawable.ALPHA, 0));
            a.setDuration(150);
            a.setInterpolator(UNFOCUS_EASE);
        }
        button.setTag(R.id.motion_scale_x, a);
        a.start();
        if (springs && focused) shimmer(null, shine, w, h);
        else if (shine == shimmerDrawable) stopShimmer();
    }

    /** Lean to (rx, ry) and settle level in one interruptible keyframed animation; unfocus just levels out. */
    private static void tilt(View card, float rx, float ry, boolean focused) {
        Object prev = card.getTag(R.id.motion_rot_x);
        if (prev instanceof Animator) ((Animator) prev).cancel();
        float cx = card.getRotationX(), cy = card.getRotationY();
        ObjectAnimator a;
        if (focused && (rx != 0f || ry != 0f)) {
            a = ObjectAnimator.ofPropertyValuesHolder(card,
                    PropertyValuesHolder.ofKeyframe(View.ROTATION_X, lean(cx, rx)),
                    PropertyValuesHolder.ofKeyframe(View.ROTATION_Y, lean(cy, ry)));
            a.setDuration(TILT_MS);
        } else {
            a = ObjectAnimator.ofPropertyValuesHolder(card,
                    PropertyValuesHolder.ofFloat(View.ROTATION_X, cx, 0f),
                    PropertyValuesHolder.ofFloat(View.ROTATION_Y, cy, 0f));
            a.setDuration(UNFOCUS_MS);
            a.setInterpolator(UNFOCUS_EASE);
        }
        card.setTag(R.id.motion_rot_x, a);
        a.start();
    }

    /** From the current angle out to the lean in the first fifth, then a slow decelerating return to level. */
    private static Keyframe[] lean(float from, float to) {
        Keyframe start = Keyframe.ofFloat(0f, from);
        Keyframe out = Keyframe.ofFloat(0.2f, to);
        out.setInterpolator(FOCUS_EASE);
        Keyframe back = Keyframe.ofFloat(1f, 0f);
        back.setInterpolator(SETTLE_EASE);
        return new Keyframe[]{start, out, back};
    }

    /** The specular highlight: starts on the edge the icon leaned to and drifts to rest near the top. */
    private static void shine(View card, View glare, boolean focused, float dx, float dy) {
        glare.animate().cancel();
        Object prev = glare.getTag(R.id.motion_tx);
        if (prev instanceof Animator) ((Animator) prev).cancel();
        if (!focused) {
            if (glare == shimmerView) stopShimmer();
            glare.animate().alpha(0f).setDuration(150).setInterpolator(UNFOCUS_EASE).start();
            return;
        }
        if (!(glare instanceof ShineView)) return;
        ShineView s = (ShineView) glare;
        int w = card.getWidth(), h = card.getHeight();
        // Move the bloom's centre, never the view: a moved view would cut the light off at its edge.
        s.setShineX(dx * w * 0.35f);
        s.setShineY(dy == 0f ? -h * 0.22f : dy * h * 0.35f);
        glare.setAlpha(Math.max(glare.getAlpha(), 0.5f));
        glare.animate().alpha(SHINE_ALPHA).setDuration(TILT_MS).setInterpolator(SETTLE_EASE).start();
        ObjectAnimator a = ObjectAnimator.ofPropertyValuesHolder(s,
                PropertyValuesHolder.ofFloat(ShineView.SHINE_X, 0f),
                PropertyValuesHolder.ofFloat(ShineView.SHINE_Y, -h * 0.22f));
        a.setDuration(TILT_MS);
        a.setInterpolator(SETTLE_EASE);
        glare.setTag(R.id.motion_tx, a);
        a.start();
        if (springs) shimmer(s, null, w, h);
    }

    /** Snap to the resting state of a (re)bound tile without animating. */
    static void set(View card, View banner, View glare, boolean focused, float density) {
        set(card, banner, glare, focused, density, 0f);
    }

    static void set(View card, View banner, View glare, boolean focused, float density, float shiftX) {
        cancelSprings(card);
        if (glare == shimmerView) stopShimmer();
        card.animate().cancel();
        banner.animate().cancel();
        glare.animate().cancel();
        Object prev = card.getTag(R.id.motion_rot_x);
        if (prev instanceof Animator) ((Animator) prev).cancel();
        Object nudge = card.getTag(R.id.motion_tx);
        if (nudge instanceof Animator) ((Animator) nudge).cancel();
        card.setScaleX(focused ? SCALE : 1f);
        card.setScaleY(focused ? SCALE : 1f);
        card.setTranslationY(focused ? -LIFT_DP * density : 0f);
        card.setTranslationZ(focused ? ELEVATION_DP * density : 0f);
        card.setTranslationX(focused ? shiftX : 0f);
        card.setTag(R.id.motion_scale_y, focused ? shiftX : 0f);
        card.setRotationX(0f);
        card.setRotationY(0f);
        banner.setScaleX(focused ? 1.04f : 1f);
        banner.setScaleY(focused ? 1.04f : 1f);
        banner.setTranslationX(0f);
        Object shineAnim = glare.getTag(R.id.motion_tx);
        if (shineAnim instanceof Animator) ((Animator) shineAnim).cancel();
        if (glare instanceof ShineView) {
            ((ShineView) glare).setShineX(0f);
            ((ShineView) glare).setShineY(-card.getHeight() * 0.22f);
        }
        glare.setAlpha(focused ? SHINE_ALPHA : 0f);
    }

    /** Left or Right at a row's end: a short push towards the key and a spring back, nothing moves. */
    static void bump(View card, int keyCode, float density) {
        float d = (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1f : -1f) * 7f * density;
        Object prev = card.getTag(R.id.motion_tx);
        if (prev instanceof Animator) ((Animator) prev).cancel();
        // From and back to the icon's resting place, which is not 0 for an icon shifted in from the dock's end.
        Object restTag = card.getTag(R.id.motion_scale_y);
        float rest = restTag instanceof Float ? (Float) restTag : 0f;
        Keyframe out = Keyframe.ofFloat(0.3f, rest + d);
        out.setInterpolator(FOCUS_EASE);
        Keyframe back = Keyframe.ofFloat(1f, rest);
        back.setInterpolator(SETTLE_EASE);
        ObjectAnimator a = ObjectAnimator.ofPropertyValuesHolder(card,
                PropertyValuesHolder.ofKeyframe(View.TRANSLATION_X, Keyframe.ofFloat(0f, card.getTranslationX()), out, back));
        a.setDuration(300);
        card.setTag(R.id.motion_tx, a);
        a.start();
    }

    // ---- Full motion: springs ---------------------------------------------------------------------------

    /** Scale (both axes) and lift towards their targets on the view's own springs, made on first use. */
    private static void springTo(View card, float scale, float lift, boolean focused) {
        SpringAnimation sx = spring(card, R.id.spring_scale_x, DynamicAnimation.SCALE_X);
        SpringAnimation sy = spring(card, R.id.spring_scale_y, DynamicAnimation.SCALE_Y);
        SpringAnimation ty = spring(card, R.id.spring_lift, DynamicAnimation.TRANSLATION_Y);
        float stiffness = focused ? FOCUS_STIFFNESS : UNFOCUS_STIFFNESS;
        float damping = focused ? FOCUS_DAMPING : UNFOCUS_DAMPING;
        // The scale is drawn from a hardware layer while it moves, as withLayer() does for the timed curve.
        if (card.getLayerType() != View.LAYER_TYPE_HARDWARE) card.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        sx.getSpring().setStiffness(stiffness).setDampingRatio(damping);
        sy.getSpring().setStiffness(stiffness).setDampingRatio(damping);
        ty.getSpring().setStiffness(stiffness).setDampingRatio(damping);
        sx.animateToFinalPosition(scale);
        sy.animateToFinalPosition(scale);
        ty.animateToFinalPosition(lift);
    }

    private static SpringAnimation spring(View card, int tag, DynamicAnimation.ViewProperty property) {
        Object o = card.getTag(tag);
        if (o instanceof SpringAnimation) return (SpringAnimation) o;
        SpringAnimation a = new SpringAnimation(card, property);
        a.setSpring(new SpringForce());
        if (property != DynamicAnimation.TRANSLATION_Y) a.setMinimumVisibleChange(DynamicAnimation.MIN_VISIBLE_CHANGE_SCALE);
        // Drop the layer once all three have settled.
        a.addEndListener((anim, canceled, value, velocity) -> {
            if (!running(card, R.id.spring_scale_x) && !running(card, R.id.spring_scale_y)
                    && !running(card, R.id.spring_lift)) {
                card.setLayerType(View.LAYER_TYPE_NONE, null);
            }
        });
        card.setTag(tag, a);
        return a;
    }

    private static boolean running(View card, int tag) {
        Object o = card.getTag(tag);
        return o instanceof SpringAnimation && ((SpringAnimation) o).isRunning();
    }

    private static void cancelSprings(View card) {
        cancelSpring(card, R.id.spring_scale_x);
        cancelSpring(card, R.id.spring_scale_y);
        cancelSpring(card, R.id.spring_lift);
    }

    private static void cancelSpring(View card, int tag) {
        Object o = card.getTag(tag);
        if (o instanceof SpringAnimation) ((SpringAnimation) o).cancel();
    }

    // ---- Full motion: the shine drifting over the focused icon ------------------------------------------

    private static final long SHIMMER_PERIOD_MS = 6000;
    private static ValueAnimator shimmer;
    private static ShineView shimmerView;
    private static ShineDrawable shimmerDrawable;
    private static float shimmerW, shimmerH;

    /**
     * After the lean has settled, the highlight circles slowly near the top of the focused icon or button:
     * one animator for the whole launcher, moved to whatever has focus, so nothing is made per frame.
     */
    private static void shimmer(ShineView view, ShineDrawable drawable, int w, int h) {
        if (shimmer == null) {
            shimmer = ValueAnimator.ofFloat(0f, 1f);
            shimmer.setDuration(SHIMMER_PERIOD_MS);
            shimmer.setRepeatCount(ValueAnimator.INFINITE);
            shimmer.setInterpolator(null);   // linear: the motion itself is a circle
            shimmer.addUpdateListener(a -> {
                double t = a.getAnimatedFraction() * 2 * Math.PI;
                // Starts where the lean's own highlight came to rest (0, -0.22h) and circles round from there.
                float x = (float) Math.sin(t) * shimmerW * 0.16f;
                float y = -shimmerH * 0.22f + (float) (1 - Math.cos(t)) * shimmerH * 0.06f;
                if (shimmerView != null) {
                    shimmerView.setShineX(x);
                    shimmerView.setShineY(y);
                } else if (shimmerDrawable != null) {
                    shimmerDrawable.setShine(x, y);
                }
            });
        }
        shimmer.cancel();
        shimmerView = view;
        shimmerDrawable = drawable;
        shimmerW = w;
        shimmerH = h;
        shimmer.setStartDelay(TILT_MS);   // after the lean's highlight has settled
        shimmer.start();
    }

    static void stopShimmer() {
        if (shimmer != null) shimmer.cancel();
        shimmerView = null;
        shimmerDrawable = null;
    }

    // ---- launching an app ------------------------------------------------------------------------------

    private static final float LAUNCH_SCALE = 1.34f;
    private static final long LAUNCH_MS = 180;

    /**
     * The icon swells towards the viewer as its app opens; the app's window grows out of the icon's bounds
     * (ActivityOptions.makeScaleUpAnimation, set by the caller). The tile goes back to its focused size once
     * the app covers Home, so it is right if Home comes back without a rebuild.
     */
    static void launchZoom(View card) {
        cancelSprings(card);
        if (card.getForeground() == shimmerDrawable) stopShimmer();
        card.animate().cancel();
        card.animate().scaleX(LAUNCH_SCALE).scaleY(LAUNCH_SCALE)
                .setDuration(LAUNCH_MS).setInterpolator(FOCUS_EASE).setStartDelay(0)
                .withLayer()
                .withEndAction(() -> card.postDelayed(() -> {
                    float s = card.isFocused() ? SCALE : 1f;
                    card.setScaleX(s);
                    card.setScaleY(s);
                }, 600))
                .start();
    }
}
