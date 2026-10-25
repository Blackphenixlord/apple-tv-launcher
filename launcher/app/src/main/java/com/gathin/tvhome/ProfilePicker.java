package com.gathin.tvhome;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import java.util.List;

/**
 * The full-screen tvOS "Who's watching?" picker, in Liquid Glass: round avatars, each a coloured disc under a
 * glass dome (LiquidGlassDrawable), on a dark, blurred Home. A glass lens sits behind the focused avatar and
 * springs to the next one as focus moves; the focused avatar lifts, leans and catches the light like an app icon
 * (TvFocusMotion). Picking someone swells their avatar and switches the launcher to their profile (LocalProfiles).
 *
 * Full motion: the picker fades in while Home behind it blurs (RenderEffect, Android 12+; the radii are made before
 * the animation starts, so it allocates nothing per frame), and the avatars pop in one after another. Reduced: a
 * short fade, no blur, no pop, a stiff lens. Off: it just appears.
 */
final class ProfilePicker {

    interface Listener {
        /** Someone other than the current profile was picked. */
        void onProfilePicked(FamilyProfiles.Profile profile);
    }

    private static final long FADE_MS = 260;
    private static final long POP_MS = 420;
    private static final long POP_STAGGER_MS = 70;
    private static final float BLUR_MAX_PX = 28f;
    private static final int BLUR_STEPS = 8;
    private static final float AVATAR_DP = 148f;
    private static final float LENS_DP = AVATAR_DP + 30f;

    private final Activity activity;
    private final View blurTarget;
    private final MotionLevel motion;
    private final Listener listener;
    private final float density;
    private Dialog dialog;
    private RenderEffect[] blurSteps;
    private ValueAnimator blurAnimator;
    private View lens;
    private SpringAnimation lensX, lensY;
    private boolean picking;

    ProfilePicker(Activity activity, View blurTarget, MotionLevel motion, Listener listener) {
        this.activity = activity;
        this.blurTarget = blurTarget;
        this.motion = motion;
        this.listener = listener;
        density = activity.getResources().getDisplayMetrics().density;
    }

    boolean isShowing() {
        return dialog != null && dialog.isShowing();
    }

    void show(List<FamilyProfiles.Profile> profiles) {
        if (isShowing() || profiles.isEmpty()) return;
        picking = false;
        FrameLayout content = new FrameLayout(activity);
        content.setBackgroundColor(0xB8000000);

        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.setClipChildren(false);
        content.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        TextView title = new TextView(activity);
        title.setText(R.string.whos_watching);
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 40);
        title.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        title.setGravity(Gravity.CENTER);
        column.addView(title);

        // The stage: the glass lens behind, the row of avatars over it.
        FrameLayout stage = new FrameLayout(activity);
        stage.setClipChildren(false);
        stage.setClipToPadding(false);
        column.addView(stage);

        lens = new View(activity);
        lens.setBackground(new LiquidGlassDrawable(-1f, 0x2EFFFFFF));
        lens.setAlpha(0f);
        stage.addView(lens, new FrameLayout.LayoutParams(dp(LENS_DP), dp(LENS_DP)));

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        row.setPadding(dp(24), dp(44), dp(24), dp(12));
        stage.addView(row, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView hint = new TextView(activity);
        hint.setText(R.string.profile_picker_hint);
        hint.setTextColor(0x99FFFFFF);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(28), 0, 0);
        column.addView(hint);

        lensX = new SpringAnimation(lens, DynamicAnimation.TRANSLATION_X);
        lensY = new SpringAnimation(lens, DynamicAnimation.TRANSLATION_Y);
        for (SpringAnimation s : new SpringAnimation[]{lensX, lensY}) {
            s.setSpring(new SpringForce()
                    .setStiffness(motion == MotionLevel.FULL ? 330f : 1400f)
                    .setDampingRatio(motion == MotionLevel.FULL ? 0.72f : 0.95f));
        }

        View[] items = new View[profiles.size()];
        int focus = 0;
        for (int i = 0; i < profiles.size(); i++) {
            FamilyProfiles.Profile p = profiles.get(i);
            items[i] = avatarItem(p, stage);
            if (p.current) focus = i;
            row.addView(items[i]);
        }

        dialog = new Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen);
        dialog.setContentView(content);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setWindowAnimations(0);   // the motion below is the transition
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
        }
        dialog.setOnKeyListener((d, keyCode, event) -> {
            TvFocusMotion.noteKey(event);   // the lean follows the D-pad, as on Home
            if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                dismiss();
                return true;
            }
            return false;
        });
        dialog.setOnDismissListener(d -> clearBlur());
        dialog.show();
        TvFocusMotion.lastDirection = 0;
        items[focus].requestFocus();
        enter(content, items);
    }

    private void enter(View content, View[] items) {
        if (!motion.animations()) return;
        content.setAlpha(0f);
        content.animate().alpha(1f).setDuration(motion.reduced() ? 150 : FADE_MS)
                .setInterpolator(new DecelerateInterpolator()).withLayer().start();
        if (motion.reduced()) return;
        startBlur(true);
        OvershootInterpolator pop = new OvershootInterpolator(1.6f);
        for (int i = 0; i < items.length; i++) {
            View v = items[i];
            v.setAlpha(0f);
            v.setScaleX(0.6f);
            v.setScaleY(0.6f);
            v.animate().alpha(1f).scaleX(1f).scaleY(1f)
                    .setStartDelay(90 + i * POP_STAGGER_MS).setDuration(POP_MS)
                    .setInterpolator(pop).withLayer().start();
        }
    }

    void dismiss() {
        if (!isShowing()) return;
        View content = dialog.getWindow() != null ? dialog.getWindow().getDecorView() : null;
        if (content == null || !motion.animations()) {
            dialog.dismiss();
            return;
        }
        startBlur(false);
        Dialog d = dialog;
        content.animate().alpha(0f).setDuration(motion.reduced() ? 120 : 200)
                .setInterpolator(new DecelerateInterpolator()).withLayer()
                .withEndAction(d::dismiss).start();
    }

    /** Home behind the picker blurs in (or out) in BLUR_STEPS precomputed radii. */
    private void startBlur(boolean in) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || blurTarget == null || motion != MotionLevel.FULL) return;
        if (blurSteps == null) {
            blurSteps = new RenderEffect[BLUR_STEPS];
            for (int i = 0; i < BLUR_STEPS; i++) {
                float r = BLUR_MAX_PX * (i + 1) / BLUR_STEPS;
                blurSteps[i] = RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP);
            }
        }
        if (blurAnimator != null) blurAnimator.cancel();
        float from = blurAnimator != null ? (float) blurAnimator.getAnimatedValue() : in ? 0f : 1f;
        blurAnimator = ValueAnimator.ofFloat(from, in ? 1f : 0f);
        blurAnimator.setDuration(in ? FADE_MS + 60 : 200);
        blurAnimator.setInterpolator(new PathInterpolator(0.25f, 1f, 0.5f, 1f));
        final int[] shown = {-2};
        blurAnimator.addUpdateListener(a -> {
            int step = Math.round((float) a.getAnimatedValue() * BLUR_STEPS) - 1;
            if (step == shown[0]) return;   // only touch the view when the radius really changes
            shown[0] = step;
            blurTarget.setRenderEffect(step < 0 ? null : blurSteps[step]);
        });
        if (!in) blurAnimator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) blurTarget.setRenderEffect(null);
            }
        });
        blurAnimator.start();
    }

    private void clearBlur() {
        if (blurAnimator != null) blurAnimator.cancel();
        blurAnimator = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && blurTarget != null) blurTarget.setRenderEffect(null);
    }

    private View avatarItem(FamilyProfiles.Profile p, View stage) {
        LinearLayout item = new LinearLayout(activity);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER_HORIZONTAL);
        item.setFocusable(true);
        item.setClickable(true);
        item.setClipChildren(false);
        item.setContentDescription(p.name);
        int m = dp(26);
        LinearLayout.LayoutParams itemLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        itemLp.setMargins(m, 0, m, 0);
        item.setLayoutParams(itemLp);

        TextView avatar = new TextView(activity);
        int size = dp(AVATAR_DP);
        avatar.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        avatar.setGravity(Gravity.CENTER);
        avatar.setText(p.initial());
        avatar.setTextColor(Color.WHITE);
        avatar.setTextSize(TypedValue.COMPLEX_UNIT_SP, 60);
        avatar.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        avatar.setBackground(glassAvatar(p.color, density, 3));
        avatar.setDuplicateParentStateEnabled(true);
        avatar.setClipToOutline(true);
        if (motion == MotionLevel.FULL) avatar.setForeground(new ShineDrawable());
        item.addView(avatar);

        TextView name = new TextView(activity);
        name.setText(p.name);
        name.setTextColor(Color.WHITE);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        name.setGravity(Gravity.CENTER);
        name.setPadding(0, dp(18), 0, 0);
        name.setAlpha(0.7f);
        item.addView(name);

        TextView sub = new TextView(activity);
        sub.setText(p.current ? activity.getString(R.string.profile_watching_now) : "");
        sub.setTextColor(0x99FFFFFF);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        sub.setGravity(Gravity.CENTER);
        item.addView(sub);

        item.setOnFocusChangeListener((v, focused) -> {
            name.setAlpha(focused ? 1f : 0.7f);
            if (focused) moveLens(avatar, stage);
            if (motion == MotionLevel.FULL) {
                TvFocusMotion.applyButton(avatar, focused, density);   // lift, lean and the moving shine
            } else if (motion.animations()) {
                float s = focused ? 1.1f : 1f;
                avatar.animate().scaleX(s).scaleY(s).setDuration(100)
                        .setInterpolator(new DecelerateInterpolator()).withLayer().start();
            }
        });
        item.setOnClickListener(v -> pick(p, avatar));
        return item;
    }

    /** The glass lens springs to sit centred behind the focused avatar. */
    private void moveLens(View avatar, View stage) {
        if (lens == null) return;
        View item = (View) avatar.getParent();
        View row = (View) item.getParent();
        if (avatar.getWidth() == 0 || row.getWidth() == 0) {
            avatar.post(() -> moveLens(avatar, stage));
            return;
        }
        // From layout positions, which ignore transforms: the avatars are still popping in (scaled to 0.6 from
        // their centres) when the first one takes focus, and window coordinates would put the lens off to one side.
        float half = (dp(LENS_DP) - avatar.getWidth()) / 2f;
        float x = row.getLeft() + item.getLeft() + avatar.getLeft() - half;
        float y = row.getTop() + item.getTop() + avatar.getTop() - half;
        boolean first = lens.getAlpha() == 0f;
        if (first || !motion.animations()) {
            lensX.cancel();
            lensY.cancel();
            lens.setTranslationX(x);
            lens.setTranslationY(y);
            if (motion.animations()) lens.animate().alpha(1f).setStartDelay(motion.reduced() ? 0 : 200).setDuration(220).start();
            else lens.setAlpha(1f);
        } else {
            lensX.animateToFinalPosition(x);
            lensY.animateToFinalPosition(y);
        }
    }

    private void pick(FamilyProfiles.Profile p, View avatar) {
        if (picking) return;
        if (p.current) {
            dismiss();
            return;
        }
        picking = true;
        Runnable done = () -> {
            if (dialog != null) dialog.dismiss();
            listener.onProfilePicked(p);
        };
        if (!motion.animations()) {
            done.run();
            return;
        }
        // The chosen avatar swells towards the viewer and the glass lens follows it, then Home reloads as them.
        lens.animate().scaleX(1.25f).scaleY(1.25f).alpha(0f).setDuration(240).start();
        avatar.animate().scaleX(1.3f).scaleY(1.3f).setDuration(motion.reduced() ? 120 : 240)
                .setInterpolator(new PathInterpolator(0.25f, 1f, 0.5f, 1f)).withLayer()
                .withEndAction(done).start();
    }

    /**
     * A disc in the profile's colour under a glass dome (rim lit from above, a soft bloom); focused, it gains a white
     * ring, as tvOS draws focus on an avatar.
     */
    static Drawable glassAvatar(int color, float density, int ringDp) {
        return new LayerDrawable(new Drawable[]{avatarBackground(color, density, ringDp),
                new LiquidGlassDrawable(-1f, 0x14FFFFFF)});
    }

    /** A filled circle in the profile's colour; focused, it gains a white ring. */
    static StateListDrawable avatarBackground(int color, float density, int ringDp) {
        GradientDrawable plain = new GradientDrawable();
        plain.setShape(GradientDrawable.OVAL);
        plain.setColor(color);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(color);
        ring.setStroke(Math.round(ringDp * density), Color.WHITE);
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, ring);
        s.addState(new int[]{}, plain);
        return s;
    }

    private int dp(float v) {
        return Math.round(v * density);
    }
}
