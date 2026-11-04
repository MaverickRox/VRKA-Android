/*
 * The selection pill's motion — the travel spring, the held lift, the
 * acceleration squash and the rim hand-off, with their constants — is ported
 * from liquid_glass_easy's LiquidGlassAnimatedNavBar and LiquidGlassLensMotion
 * (pub.dev/packages/liquid_glass_easy, 4.3.4), by way of BitChord's
 * GlassSelectionPill. Copyright (c) 2025 Ahmed Gamil, MIT License.
 *
 * Only the motion is taken. There is no lens here: the pill is drawn with plain
 * canvas calls, and samples nothing behind it.
 */
package com.mvrk.vrka

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sign

/** How much taller than the bar the pill stands while it is lifted. */
internal val NAV_PILL_GROW_HEIGHT = 9.dp

/**
 * The selection pill's physics.
 *
 * A tap lifts the pill at once — it inflates past the bar on two under-damped
 * springs, one per axis, so it overshoots its raised size and rebounds — and it
 * stays up for the whole journey. The travel is its own spring. Only once the
 * pill has landed is the lift released, so it deflates where it stands. A drag
 * on the bar is the same lift held open by a finger.
 *
 * Along the way the drawn position is differentiated twice: the pill stretches
 * wide and flat as it launches, squashes narrow and tall as it brakes, and is
 * undeformed at constant speed.
 *
 * The pager also moves the pill. A swipe of the page carries it 1:1 with no lift,
 * deforming from the same acceleration, so the two never disagree. While a tap or
 * a released drag is on its way the pill leads and pager samples are ignored,
 * until the pager has stopped or a finger has taken the page.
 *
 * Positions are in tab steps: `0` is the first tab, `1` the second. Every value
 * here is read in the draw phase, so a frame of motion is a redraw and nothing
 * more, and the frame loop only runs while something is moving.
 */
@Stable
internal class NavPillMotion(
    private val scope: CoroutineScope,
    initialIndex: Int,
) {
    /** Where the pill is drawn, in tab steps. */
    var position by mutableFloatStateOf(initialIndex.toFloat())
        private set

    /** The width lift, `0` at rest and `1` at the raised size; overshoots both. */
    var liftX by mutableFloatStateOf(0f)
        private set

    /** The height lift; see [liftX]. */
    var liftY by mutableFloatStateOf(0f)
        private set

    /** Acceleration squash: width scales by `1 + this`, height by `1 - this`. */
    var deviation by mutableFloatStateOf(0f)
        private set

    /** How much of the lifted pill's rim, sheen and shadow is drawn, `0`..`1`. */
    var rim by mutableFloatStateOf(0f)
        private set

    /** The tab the pill is headed for. */
    var index: Int = initialIndex
        private set

    /** One tab step in dp, which the squash model samples the pill's travel in. */
    var stepDp: Float = 0f

    /** Android's reduced-motion switch: every move lands at once, with no lift. */
    var reduceMotion: Boolean = false

    private var travelPos = initialIndex.toDouble()
    private var travelVel = 0.0
    private var travelTarget = travelPos
    private var travelFrom = travelPos
    private var travelActive = false

    private var lifted = false
    private var liftXPos = 0.0
    private var liftXVel = 0.0
    private var liftYPos = 0.0
    private var liftYVel = 0.0
    private var liftPos = 0.0
    private var liftVel = 0.0

    private var dragging = false
    private var dragTarget = 0.0
    private var dragFollow = 0.0

    /** A tap or released drag is ahead of the pager, which is still catching up. */
    private var leadingPager = false
    private var followSampled = false
    private var lastFollowAt = Double.NEGATIVE_INFINITY

    private var travelSign = 0.0
    private var travelSignEased = 0.0
    private var handover = 1.0
    private var squash = 0.0
    private val squashModel = NavPillSquashModel()

    private var running = false

    /** A tab was chosen: travel there, lifted. Re-choosing the current tab is a no-op. */
    fun animateTo(next: Int) {
        if (next == index) return
        leadingPager = true
        if (reduceMotion) {
            snapTo(next)
            return
        }
        index = next
        travelActive = true
        lifted = true
        // Retarget from wherever the pill is; the spring keeps its velocity.
        travelFrom = travelPos
        travelTarget = next.toDouble()
        travelSign = signOfTravel(travelTarget - travelFrom)
        start()
    }

    /** A finger has picked the pill up where it stands. */
    fun startDrag() {
        dragging = true
        travelActive = false
        lifted = !reduceMotion
        // The hand takes the deformation back off the travel.
        travelSign = 0.0
        dragFollow = travelPos
        dragTarget = travelPos
        travelVel = 0.0
        start()
    }

    /** The finger is over [position] tab steps. */
    fun dragTo(position: Float) {
        if (dragging) dragTarget = position.toDouble()
    }

    /**
     * The finger let go and the pill is to settle on [next]. It stays lifted
     * through the snap and comes down on landing, exactly as a tap's does.
     */
    fun release(next: Int) {
        if (!dragging) return
        val from = dragFollow
        dragging = false
        leadingPager = next != index
        index = next
        if (reduceMotion) {
            snapTo(next)
            return
        }
        travelActive = true
        travelPos = from
        travelVel = 0.0
        travelFrom = from
        travelTarget = next.toDouble()
        travelSign = signOfTravel(travelTarget - travelFrom)
        start()
    }

    /** The pager reports where it is, and whether it is still on its way there. */
    fun followPager(pagerPosition: Float, pagerScrolling: Boolean) {
        if (dragging) return
        if (leadingPager) {
            if (pagerScrolling) return
            leadingPager = false
        }
        val target = pagerPosition.toDouble()
        if (travelActive) {
            // At rest somewhere other than where the pill is headed: go there instead.
            val settledOn = pagerPosition.roundToInt()
            if (!pagerScrolling && settledOn != index) {
                index = settledOn
                travelFrom = travelPos
                travelTarget = settledOn.toDouble()
                travelSign = signOfTravel(travelTarget - travelFrom)
            }
            return
        }
        if (target == travelPos) return
        index = pagerPosition.roundToInt()
        travelPos = target
        travelTarget = target
        travelFrom = target
        // Published here rather than on the next tick, so the pill and the page
        // arrive in the same frame.
        position = pagerPosition
        if (reduceMotion) return
        followSampled = true
        start()
    }

    /** A finger took the page: whatever the pill was doing, the page leads now. */
    fun pagerGrabbed() {
        if (dragging) return
        leadingPager = false
        travelActive = false
        lifted = false
        travelVel = 0.0
        travelSign = 0.0
        start()
    }

    /** The size the pill is drawn at this frame, between [rest] and [lifted]. */
    fun liveSize(rest: Size, lifted: Size): Size {
        val w = rest.width + (lifted.width - rest.width) * liftX
        val h = rest.height + (lifted.height - rest.height) * liftY
        return Size(w * (1 + deviation), h * (1 - deviation))
    }

    private fun snapTo(target: Int) {
        index = target
        travelPos = target.toDouble()
        travelTarget = travelPos
        travelFrom = travelPos
        travelVel = 0.0
        travelActive = false
        dragging = false
        lifted = false
        liftXPos = 0.0; liftXVel = 0.0
        liftYPos = 0.0; liftYVel = 0.0
        liftPos = 0.0; liftVel = 0.0
        handover = 1.0
        squash = 0.0
        squashModel.stop()
        travelSign = 0.0
        travelSignEased = 0.0
        publish()
    }

    private fun start() {
        if (running) return
        running = true
        scope.launch {
            var last = -1L
            try {
                while (true) {
                    val now = withFrameNanos { it }
                    val dt = if (last < 0) 0.0 else (now - last) / 1e9
                    last = now
                    if (!tick(dt, now / 1e9)) break
                }
            } finally {
                running = false
            }
        }
    }

    /** One frame of everything. False once all of it has settled. */
    internal fun tick(dt: Double, now: Double): Boolean {
        // 1) Travel.
        var travelSettled = true
        if (travelActive) {
            val r = springStep(travelPos, travelVel, travelTarget, dt, TRAVEL_STIFFNESS, TRAVEL_DAMPING)
            travelPos = r.first
            travelVel = r.second
            travelSettled = abs(travelPos - travelTarget) < 0.003 && abs(travelVel) < 0.05
            if (travelSettled) {
                travelPos = travelTarget
                travelVel = 0.0
            }
        }

        // 2) Under a finger, chase it smoothly.
        if (dragging) {
            dragFollow += if (reduceMotion) {
                dragTarget - dragFollow
            } else {
                (dragTarget - dragFollow) * (1 - exp(-dt / FOLLOW_TAU))
            }
        }

        // 3) The lift, held for the journey and released on landing.
        if (!dragging && (travelSettled || travelProgress() >= HANDOVER_START)) {
            lifted = false
        }
        val liftTarget = if (lifted) 1.0 else 0.0
        stepLift(liftXPos, liftXVel, liftTarget, dt, LIFT_STIFFNESS, LIFT_DAMPING_X).let {
            liftXPos = it.first; liftXVel = it.second
        }
        stepLift(liftYPos, liftYVel, liftTarget, dt, LIFT_STIFFNESS, LIFT_DAMPING_Y).let {
            liftYPos = it.first; liftYVel = it.second
        }
        stepLift(liftPos, liftVel, liftTarget, dt, MATERIAL_STIFFNESS, MATERIAL_DAMPING).let {
            liftPos = it.first; liftVel = it.second
        }
        val liftSettled = !lifted && liftXPos == 0.0 && liftYPos == 0.0 && liftPos == 0.0

        // 4) The deflation outlives the spring that carried the pill there.
        if (travelActive && travelSettled && liftSettled && !dragging) {
            travelActive = false
        }

        // 5) Sample the pill where it is drawn this frame.
        val frac = if (dragging) dragFollow else travelPos
        if (stepDp > 0f) {
            if (!squashModel.isTracking) squashModel.start()
            squash = squashModel.track(frac * stepDp, now, dt)
            if (travelSignEased == 0.0) {
                travelSignEased = travelSign
            } else if (travelSignEased != travelSign) {
                travelSignEased += (travelSign - travelSignEased) * (1 - exp(-dt / SIGN_TAU))
                if (abs(travelSign - travelSignEased) < 0.01) travelSignEased = travelSign
            }
            // Keep the force's magnitude and take its sign from the direction
            // of travel, so a trip deforms one way the whole way.
            val key = travelSignEased
            if (key != 0.0) squash = squash * (1 - abs(key)) - key * abs(squash)
        }

        // 6) The hand-off rides the same landing as the deflation.
        val handoverTarget = if (lifted) 0.0 else 1.0
        val tau = if (handoverTarget > handover) HANDOVER_TAU else RIM_RETURN_TAU
        handover += (handoverTarget - handover) * (1 - exp(-dt / tau))
        if (abs(handoverTarget - handover) < 0.002) handover = handoverTarget

        // 7) Pager samples arrive one at a time, and the squash needs a run of them.
        if (followSampled) {
            followSampled = false
            lastFollowAt = now
        }
        val following = now - lastFollowAt < FOLLOW_IDLE_SECONDS

        val settled = !travelActive && !dragging && !following && liftSettled &&
            abs(squash) < 0.0005 && handover >= 1.0
        if (settled) {
            squashModel.stop()
            squash = 0.0
            travelSign = 0.0
            travelSignEased = 0.0
        }
        publish()
        return !settled
    }

    private fun publish() {
        position = (if (dragging) dragFollow else travelPos).toFloat()
        liftX = liftXPos.toFloat()
        liftY = liftYPos.toFloat()
        deviation = squash.toFloat()
        // Gone by the time the hand-off is 55% through, ahead of the size settling.
        val presence = (1 - handover).coerceIn(0.0, 1.0).toFloat()
        rim = liftPos.coerceIn(0.0, 1.0).toFloat() * ((presence - 0.45f) / 0.55f).coerceIn(0f, 1f)
    }

    private fun travelProgress(): Double {
        val span = abs(travelTarget - travelFrom)
        if (span < 1e-6) return 1.0
        return (1 - abs(travelTarget - travelPos) / span).coerceIn(0.0, 1.0)
    }

    private fun signOfTravel(span: Double): Double = if (abs(span) < 1e-6) 0.0 else sign(span)

    private companion object {
        const val TRAVEL_STIFFNESS = 280.0
        const val TRAVEL_DAMPING = 31.4

        /** Damping ratio 0.6 across, 0.7 down, so width overshoots a shade further. */
        const val LIFT_STIFFNESS = 250.0
        const val LIFT_DAMPING_X = 19.0
        const val LIFT_DAMPING_Y = 22.1

        /** Critically damped and four times as stiff: the rim is on long before the size settles. */
        const val MATERIAL_STIFFNESS = 1000.0
        const val MATERIAL_DAMPING = 63.3

        const val HANDOVER_START = 0.92
        const val HANDOVER_TAU = 0.09
        const val RIM_RETURN_TAU = 0.05
        const val FOLLOW_TAU = 0.05
        const val SIGN_TAU = 0.25
        const val FOLLOW_IDLE_SECONDS = 0.1

        /** Semi-implicit Euler in 1/240 s substeps, so a long frame cannot blow it up. */
        fun springStep(
            x: Double,
            vel: Double,
            target: Double,
            dt: Double,
            stiffness: Double,
            damping: Double,
        ): Pair<Double, Double> {
            var t = dt
            var px = x
            var pv = vel
            while (t > 0) {
                val step = if (t > 1 / 240.0) 1 / 240.0 else t
                val accel = -stiffness * (px - target) - damping * pv
                pv += accel * step
                px += pv * step
                t -= step
            }
            return px to pv
        }

        /** A spring step snapped to its target once it has nothing left to say. */
        fun stepLift(
            x: Double,
            vel: Double,
            target: Double,
            dt: Double,
            stiffness: Double,
            damping: Double,
        ): Pair<Double, Double> {
            val r = springStep(x, vel, target, dt, stiffness, damping)
            return if (abs(r.first - target) < 0.0008 && abs(r.second) < 0.01) target to 0.0 else r
        }
    }
}

/**
 * Acceleration to deformation: the pill's position over a short window,
 * differentiated twice and averaged, scaled and clamped, then eased so a
 * single noisy frame cannot flick it. Tuned in dp/s².
 */
private class NavPillSquashModel {
    private val history = ArrayList<Pair<Double, Double>>()
    var isTracking = false
        private set
    private var value = 0.0

    fun start() {
        history.clear()
        value = 0.0
        isTracking = true
    }

    fun stop() {
        isTracking = false
        history.clear()
        value = 0.0
    }

    fun track(x: Double, now: Double, dt: Double): Double {
        if (!isTracking) return value
        history.add(x to now)
        val cutoff = now - SAMPLE_WINDOW
        history.removeAll { it.second < cutoff }
        val raw = (averageAcceleration() * SENSITIVITY).coerceIn(-MAX_DEFORMATION, MAX_DEFORMATION)
        val ease = (dt / RESPONSE_TIME).coerceIn(0.0, 1.0)
        value += (raw - value) * ease
        return value
    }

    private fun averageAcceleration(): Double {
        if (history.size < 3) return 0.0
        val velocities = ArrayList<Pair<Double, Double>>(history.size)
        for (i in 1 until history.size) {
            val dt = history[i].second - history[i - 1].second
            if (dt <= 0) continue
            velocities.add(
                (history[i].first - history[i - 1].first) / dt to
                    (history[i].second + history[i - 1].second) / 2,
            )
        }
        if (velocities.size < 2) return 0.0
        var total = 0.0
        var count = 0
        for (i in 1 until velocities.size) {
            val dt = velocities[i].second - velocities[i - 1].second
            if (dt <= 0) continue
            total += (velocities[i].first - velocities[i - 1].first) / dt
            count++
        }
        return if (count == 0) 0.0 else total / count
    }

    private companion object {
        const val SAMPLE_WINDOW = 0.3
        const val SENSITIVITY = 0.00007

        /** ±12 %: the pill travels inside the bar, and more would climb out of it. */
        const val MAX_DEFORMATION = 0.12
        const val RESPONSE_TIME = 0.18
    }
}
