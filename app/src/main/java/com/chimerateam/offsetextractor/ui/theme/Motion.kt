package com.chimerateam.offsetextractor.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf

/**
 * Duration tokens.
 *
 * Three steps only: a state change that must feel instant, a default
 * transition, and one that is allowed to be noticed.
 */
object MotionDuration {
    /** 120 ms: press feedback, chip and switch state. */
    const val SHORT = 120

    /** 240 ms: navigation, sheets, expand and collapse. */
    const val MEDIUM = 240

    /** 400 ms: a phase change in the workflow stepper. */
    const val LONG = 400
}

/** Easing tokens, all cubic so they read the same at any duration. */
object MotionEasing {
    /** Emphasised: leaves fast, lands softly. Used for enter transitions. */
    val Emphasized: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** Standard: symmetric in and out. */
    val Standard: Easing = FastOutSlowInEasing

    /** Decelerate: for something entering the screen. */
    val Decelerate: Easing = LinearOutSlowInEasing

    /** Accelerate: for something leaving the screen. */
    val Accelerate: Easing = FastOutLinearInEasing
}

/** Named spring specs, so no call site invents its own bounce. */
object MotionSpring {
    /** Layout and size changes: no overshoot. */
    val Layout = spring<Float>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow
    )

    /** Confirmations: a slight overshoot, never a wobble. */
    val Confirmation = spring<Float>(
        dampingRatio = 0.65f,
        stiffness = Spring.StiffnessMedium
    )

    /** Press feedback: quick in, quick out. */
    val Press = spring<Float>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessHigh
    )
}

/**
 * Composition-local switch for reduced motion.
 *
 * True when the system animator scale is zero or the user turned motion off in
 * Settings; every animation reads it through [motionTween].
 */
val LocalReduceMotion = compositionLocalOf { false }

/**
 * A tween that collapses to a snap when motion is reduced.
 *
 * @param durationMillis token from [MotionDuration].
 * @param easing token from [MotionEasing].
 * @param reduceMotion current setting.
 * @return a tween, or a zero-duration tween when motion is reduced.
 */
fun <T> motionTween(
    durationMillis: Int,
    easing: Easing = MotionEasing.Standard,
    reduceMotion: Boolean = false
): FiniteAnimationSpec<T> = tween(
    durationMillis = if (reduceMotion) 0 else durationMillis,
    easing = easing
)

/**
 * A spring that collapses to a snap when motion is reduced.
 *
 * @param spec named spring from [MotionSpring].
 * @param reduceMotion current setting.
 * @return the spring, or a zero-duration tween when motion is reduced.
 */
fun motionSpring(
    spec: FiniteAnimationSpec<Float>,
    reduceMotion: Boolean = false
): FiniteAnimationSpec<Float> = if (reduceMotion) tween(durationMillis = 0) else spec

/**
 * The spacing scale: one 4 dp grid, no arbitrary values in screens.
 */
@Immutable
object Spacing {
    /** 2 dp, for a hairline gap between a label and its value. */
    val Hairline = androidx.compose.ui.unit.Dp(2f)

    /** 4 dp. */
    val Xs = androidx.compose.ui.unit.Dp(4f)

    /** 8 dp. */
    val Sm = androidx.compose.ui.unit.Dp(8f)

    /** 12 dp. */
    val Md = androidx.compose.ui.unit.Dp(12f)

    /** 16 dp, the default screen gutter. */
    val Lg = androidx.compose.ui.unit.Dp(16f)

    /** 24 dp, between sections. */
    val Xl = androidx.compose.ui.unit.Dp(24f)

    /** 32 dp, around an empty state. */
    val Xxl = androidx.compose.ui.unit.Dp(32f)
}
