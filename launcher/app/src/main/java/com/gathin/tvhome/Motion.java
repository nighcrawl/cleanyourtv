package com.gathin.tvhome;

import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

/** Reduced motion (config.xml reduced_motion): the one spring and the one time limit every animation uses then. */
final class Motion {

    /** The longest any reduced-motion animation runs, in milliseconds. */
    static final int REDUCED_MS = 90;
    /** Critically damped (no bounce) and stiff enough that any move, even across the screen, settles in 5 frames. */
    static final float REDUCED_STIFFNESS = 15000f;
    /** The spring stops once it is within this many pixels of its target. */
    static final float REDUCED_MIN_CHANGE = 2f;

    private Motion() {
    }

    /** The zoom of a focused tile or card: none without animations, a touch less without advanced_effects. */
    static float focusScale(boolean animations, boolean advancedEffects) {
        return !animations ? 1f : advancedEffects ? 1.12f : 1.10f;
    }

    /**
     * The focus zoom shared by the app tiles and the Continue watching cards: scale, lift and elevation, with
     * the timing and bounce each mode allows. The glass highlight and the name are the caller's.
     */
    static void zoom(View v, boolean focused, boolean animations, boolean reducedMotion, boolean advancedEffects,
                     boolean overshoot) {
        float density = v.getResources().getDisplayMetrics().density;
        float s = focused ? focusScale(animations, advancedEffects) : 1f;
        float lift = focused && advancedEffects ? -4f * density : 0f;
        float elevation = focused && advancedEffects ? 8f * density : 0f;
        v.animate().cancel();
        if (!animations) {
            v.setScaleX(1f);
            v.setScaleY(1f);
            v.setTranslationY(0f);
            v.setTranslationZ(0f);
        } else if (reducedMotion) {
            // A short zoom with no hardware layer: building a layer for so short an animation costs more
            // frames than it saves.
            v.animate().scaleX(s).scaleY(s).translationY(lift).translationZ(elevation)
                    .setDuration(focused ? REDUCED_MS : 60)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        } else {
            v.animate().scaleX(s).scaleY(s).translationY(lift).translationZ(elevation)
                    .setDuration(advancedEffects ? (focused ? 220 : 170) : (focused ? 260 : 180))
                    .setInterpolator(focused && !overshoot ? new DecelerateInterpolator(1.5f)
                            : new OvershootInterpolator(focused ? (advancedEffects ? 0.7f : 1.6f) : 0f))
                    .withLayer().start();
        }
    }

    static void reduce(SpringAnimation spring) {
        spring.setSpring(new SpringForce().setStiffness(REDUCED_STIFFNESS).setDampingRatio(SpringForce.DAMPING_RATIO_NO_BOUNCY));
        spring.setMinimumVisibleChange(REDUCED_MIN_CHANGE);
    }
}
