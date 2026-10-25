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
import android.graphics.drawable.GradientDrawable;
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
import android.widget.Toast;

import java.util.List;

/**
 * The full-screen tvOS "Who's watching?" picker: round avatars on a dark, blurred Home.
 *
 * Full motion: the picker fades in while Home behind it blurs (RenderEffect, Android 12+; the radii are
 * made before the animation starts, so it allocates nothing per frame), and the avatars pop in one after
 * another with a slight overshoot. Reduced: a short fade, no blur, no pop. Off: it just appears.
 *
 * Picking the current profile closes it; picking anyone else hands over to Google TV's own switcher
 * (ProfileSwitch), since only the system can switch Android users.
 */
final class ProfilePicker {

    private static final long FADE_MS = 260;
    private static final long POP_MS = 420;
    private static final long POP_STAGGER_MS = 70;
    private static final float BLUR_MAX_PX = 28f;
    private static final int BLUR_STEPS = 8;

    private final Activity activity;
    private final View blurTarget;
    private final MotionLevel motion;
    private final float density;
    private final String switcherConfig;
    private Dialog dialog;
    private RenderEffect[] blurSteps;
    private ValueAnimator blurAnimator;

    ProfilePicker(Activity activity, View blurTarget, MotionLevel motion, String switcherConfig) {
        this.activity = activity;
        this.blurTarget = blurTarget;
        this.motion = motion;
        this.switcherConfig = switcherConfig;
        density = activity.getResources().getDisplayMetrics().density;
    }

    boolean isShowing() {
        return dialog != null && dialog.isShowing();
    }

    void show(List<FamilyProfiles.Profile> profiles) {
        if (isShowing() || profiles.isEmpty()) return;
        FrameLayout content = new FrameLayout(activity);
        content.setBackgroundColor(0xB8000000);

        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout.LayoutParams columnLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        content.addView(column, columnLp);

        TextView title = new TextView(activity);
        title.setText(R.string.whos_watching);
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 40);
        title.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        title.setGravity(Gravity.CENTER);
        column.addView(title);

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        int vpad = dp(48);
        row.setPadding(dp(24), vpad, dp(24), dp(12));
        column.addView(row);
        column.setClipChildren(false);

        TextView hint = new TextView(activity);
        hint.setText(R.string.profile_picker_hint);
        hint.setTextColor(0x99FFFFFF);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(28), 0, 0);
        column.addView(hint);

        View[] items = new View[profiles.size()];
        int focus = 0;
        for (int i = 0; i < profiles.size(); i++) {
            items[i] = avatarItem(profiles.get(i), profiles.get(i).current);
            if (profiles.get(i).current) focus = i;
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
            if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                dismiss();
                return true;
            }
            return false;
        });
        dialog.setOnDismissListener(d -> clearBlur());
        dialog.show();
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

    private View avatarItem(FamilyProfiles.Profile p, boolean current) {
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
        int size = dp(148);
        avatar.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        avatar.setGravity(Gravity.CENTER);
        avatar.setText(p.initial());
        avatar.setTextColor(Color.WHITE);
        avatar.setTextSize(TypedValue.COMPLEX_UNIT_SP, 60);
        avatar.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        avatar.setBackground(avatarBackground(p.color, density, 4));
        avatar.setDuplicateParentStateEnabled(true);
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
        sub.setText(current ? activity.getString(R.string.profile_watching_now) : "");
        sub.setTextColor(0x99FFFFFF);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        sub.setGravity(Gravity.CENTER);
        item.addView(sub);

        item.setOnFocusChangeListener((v, focused) -> {
            name.setAlpha(focused ? 1f : 0.7f);
            if (!motion.animations()) return;
            float s = focused ? 1.12f : 1f;
            avatar.animate().scaleX(s).scaleY(s).translationZ(focused ? dp(16) : 0)
                    .setDuration(motion.reduced() ? 100 : 260)
                    .setInterpolator(motion.reduced() ? new DecelerateInterpolator()
                            : new OvershootInterpolator(1.4f))
                    .withLayer().start();
        });
        item.setOnClickListener(v -> pick(p, current));
        return item;
    }

    private void pick(FamilyProfiles.Profile p, boolean current) {
        if (current) {
            dismiss();
            return;
        }
        if (dialog != null) dialog.dismiss();
        ProfileSwitch.Result r = ProfileSwitch.open(activity, switcherConfig);
        if (r == ProfileSwitch.Result.SWITCHER) {
            Toast.makeText(activity, activity.getString(R.string.profile_choose_name, p.name), Toast.LENGTH_SHORT).show();
        } else if (r == ProfileSwitch.Result.GOOGLE_HOME) {
            Toast.makeText(activity, activity.getString(R.string.profile_switch_hint, p.name), Toast.LENGTH_LONG).show();
        } else if (r == ProfileSwitch.Result.NOTHING) {
            Toast.makeText(activity, R.string.profile_switch_unavailable, Toast.LENGTH_LONG).show();
        }
    }

    /** A filled circle in the profile's colour; focused, it gains a white ring, as tvOS draws focus on an avatar. */
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
