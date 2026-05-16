package io.twostars.sdk.internal

import io.twostars.sdk.AutoFrameProcessor
import io.twostars.sdk.AutoFrameProcessor.CropWindow
import io.twostars.sdk.AutoFrameProcessor.NormalizedBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit test for [AutoFrameProcessor]'s deterministic math:
 *   - EMA smoothing
 *   - largest-face selection
 *   - hysteresis around speaker switching
 *   - lost-face hold + slow revert to full frame
 *
 * What this DOES NOT cover (needs hardware):
 *   - ML Kit actually detects a face on a real camera frame
 *   - YUV crop+scale produces a frame the encoder accepts
 *   - Per-frame budget on a real device
 */
class AutoFrameProcessorTest {

    private fun box(cx: Float, cy: Float, w: Float, h: Float = w) =
        NormalizedBox(cx, cy, w, h)

    private fun pickTargetReflected(
        boxes: List<NormalizedBox>,
        current: CropWindow,
        lastFaceWallMs: Long,
        nowWallMs: Long,
        holdMs: Long,
        targetFaceFraction: Float,
        lastArea: Float,
        pendingFrames: Int,
        requiredRatio: Float = 1.4f,
        requiredFrames: Int = 3,
    ): Triple<CropWindow, Float, Int> {
        // pickTarget is `internal` to the AutoFrameProcessor.Companion;
        // call it via Kotlin reflection so this test stays out of the
        // production class's `internal` package.
        val cls = Class.forName("io.twostars.sdk.AutoFrameProcessor\$Companion")
        val hysteresisCls = Class.forName("io.twostars.sdk.AutoFrameProcessor\$HysteresisState")
        val hysteresis = hysteresisCls.getDeclaredConstructor(
            java.lang.Float.TYPE, java.lang.Integer.TYPE,
            java.lang.Float.TYPE, java.lang.Integer.TYPE,
        ).newInstance(lastArea, pendingFrames, requiredRatio, requiredFrames)
        val method = cls.declaredMethods.first { it.name == "pickTarget\$library" || it.name == "pickTarget" }
        method.isAccessible = true
        val instance = AutoFrameProcessor.Companion
        val r = method.invoke(instance, boxes, current, lastFaceWallMs, nowWallMs,
            holdMs, targetFaceFraction, hysteresis)
        val win = r!!.javaClass.getMethod("getWindow").invoke(r) as CropWindow
        val la = r.javaClass.getMethod("getLastArea").invoke(r) as Float
        val pf = r.javaClass.getMethod("getPendingFrames").invoke(r) as Int
        return Triple(win, la, pf)
    }

    // -- EMA smoothing -----------------------------------------------

    @Test fun `EMA pulls current toward target by alpha`() {
        val cur = CropWindow(0.5f, 0.5f, 1.0f)
        val tgt = CropWindow(0.7f, 0.4f, 0.5f)
        val out = AutoFrameProcessor.ema(cur, tgt, 0.5f)
        // Half-way exactly.
        assertEquals(0.6f,  out.cx,    1e-5f)
        assertEquals(0.45f, out.cy,    1e-5f)
        assertEquals(0.75f, out.scale, 1e-5f)
    }

    @Test fun `EMA with alpha=1 jumps directly to target`() {
        val cur = CropWindow(0.0f, 0.0f, 0.0f)
        val tgt = CropWindow(0.7f, 0.4f, 0.5f)
        val out = AutoFrameProcessor.ema(cur, tgt, 1.0f)
        assertEquals(tgt.cx,    out.cx,    1e-5f)
        assertEquals(tgt.cy,    out.cy,    1e-5f)
        assertEquals(tgt.scale, out.scale, 1e-5f)
    }

    @Test fun `EMA with alpha=0 keeps current unchanged`() {
        val cur = CropWindow(0.5f, 0.5f, 1.0f)
        val tgt = CropWindow(0.7f, 0.4f, 0.5f)
        val out = AutoFrameProcessor.ema(cur, tgt, 0.0f)
        assertEquals(cur.cx,    out.cx,    1e-5f)
        assertEquals(cur.cy,    out.cy,    1e-5f)
        assertEquals(cur.scale, out.scale, 1e-5f)
    }

    // -- Largest-face selection + sizing -----------------------------

    @Test fun `single face sets target centered on face with target fraction sizing`() {
        val faceCx = 0.6f; val faceCy = 0.4f; val faceSide = 0.10f
        val cur = CropWindow(0.5f, 0.5f, 1.0f)
        val (win, la, pf) = pickTargetReflected(
            boxes = listOf(box(faceCx, faceCy, faceSide)),
            current = cur,
            lastFaceWallMs = 1000, nowWallMs = 2000, holdMs = 2000,
            targetFaceFraction = 0.30f,
            lastArea = 0f, pendingFrames = 0,
        )
        // First face ever — snap target.
        assertEquals(faceCx, win.cx, 1e-5f)
        assertEquals(faceCy, win.cy, 1e-5f)
        // scale = faceShorter / 0.30 = 0.10/0.30 ≈ 0.333.
        assertEquals(0.333f, win.scale, 1e-3f)
        assertEquals(faceSide * faceSide, la, 1e-6f)
        assertEquals(0, pf)
    }

    @Test fun `two faces — picks the larger`() {
        val small = box(cx = 0.2f, cy = 0.5f, w = 0.05f)
        val big   = box(cx = 0.8f, cy = 0.5f, w = 0.20f)
        val (win, la, _) = pickTargetReflected(
            boxes = listOf(small, big),
            current = CropWindow(0.5f, 0.5f, 1.0f),
            lastFaceWallMs = 1000, nowWallMs = 2000, holdMs = 2000,
            targetFaceFraction = 0.30f,
            lastArea = 0f, pendingFrames = 0,
        )
        assertEquals(big.cx, win.cx, 1e-5f)
        assertEquals(big.area, la, 1e-6f)
    }

    // -- Hysteresis around speaker switching -------------------------

    @Test fun `switching to a slightly-larger face takes hysteresis frames`() {
        // We've been tracking a face of area 0.04; a new candidate of
        // area 0.06 (1.5x) should NOT switch immediately — needs 3 frames.
        val current = CropWindow(0.3f, 0.5f, 0.5f)
        val newBox = box(cx = 0.7f, cy = 0.5f, w = 0.245f) // area ≈ 0.06
        // Frame 1 — pending=1, no switch
        var (win, la, pf) = pickTargetReflected(
            boxes = listOf(newBox), current = current,
            lastFaceWallMs = 1000, nowWallMs = 1100, holdMs = 2000,
            targetFaceFraction = 0.30f,
            lastArea = 0.04f, pendingFrames = 0,
        )
        assertEquals("frame 1: target unchanged", current.cx, win.cx, 1e-5f)
        assertEquals(1, pf)
        // Frame 2 — pending=2, still no switch
        val (win2, la2, pf2) = pickTargetReflected(
            boxes = listOf(newBox), current = current,
            lastFaceWallMs = 1100, nowWallMs = 1300, holdMs = 2000,
            targetFaceFraction = 0.30f,
            lastArea = 0.04f, pendingFrames = pf,
        )
        assertEquals("frame 2: still target unchanged", current.cx, win2.cx, 1e-5f)
        assertEquals(2, pf2)
        // Frame 3 — pending=3, accepted: switch + reset.
        val (win3, _, pf3) = pickTargetReflected(
            boxes = listOf(newBox), current = current,
            lastFaceWallMs = 1300, nowWallMs = 1500, holdMs = 2000,
            targetFaceFraction = 0.30f,
            lastArea = 0.04f, pendingFrames = pf2,
        )
        assertEquals("frame 3: target snaps to new face", newBox.cx, win3.cx, 1e-5f)
        assertEquals("pending resets", 0, pf3)
    }

    @Test fun `face only slightly larger doesn't trip hysteresis at all`() {
        // Same face area + 5% — under the 1.4x ratio.
        val current = CropWindow(0.3f, 0.5f, 0.5f)
        val newBox = box(cx = 0.7f, cy = 0.5f, w = 0.205f) // area ≈ 0.042
        val (win, _, pf) = pickTargetReflected(
            boxes = listOf(newBox), current = current,
            lastFaceWallMs = 1000, nowWallMs = 1100, holdMs = 2000,
            targetFaceFraction = 0.30f,
            lastArea = 0.04f, pendingFrames = 0,
        )
        // Not a switch — but also not a hysteresis frame. We accept the
        // new (smaller) face's framing immediately because it isn't
        // a different speaker; the policy treats it as continued tracking.
        // pendingFrames stays 0.
        assertEquals(0, pf)
        assertEquals(newBox.cx, win.cx, 1e-5f)
    }

    // -- Lost-face hold + revert to full frame -----------------------

    @Test fun `face lost within hold window keeps current target`() {
        val current = CropWindow(0.6f, 0.4f, 0.4f)
        val (win, _, _) = pickTargetReflected(
            boxes = emptyList(), current = current,
            lastFaceWallMs = 1000, nowWallMs = 2500, holdMs = 2000,  // 1500ms since face → within hold
            targetFaceFraction = 0.30f,
            lastArea = 0.04f, pendingFrames = 0,
        )
        assertEquals("hold preserves cx", current.cx, win.cx, 1e-5f)
        assertEquals("hold preserves cy", current.cy, win.cy, 1e-5f)
        assertEquals("hold preserves scale", current.scale, win.scale, 1e-5f)
    }

    @Test fun `face lost past hold window slowly reverts to full frame`() {
        val current = CropWindow(0.6f, 0.4f, 0.4f)
        val (win, _, _) = pickTargetReflected(
            boxes = emptyList(), current = current,
            lastFaceWallMs = 1000, nowWallMs = 5000, holdMs = 2000,  // 4000ms since face — past hold
            targetFaceFraction = 0.30f,
            lastArea = 0.04f, pendingFrames = 0,
        )
        // Reverting toward (0.5, 0.5, 1.0) at α=0.05.
        // cx: 0.6 + (0.5 - 0.6)*0.05 = 0.595
        // cy: 0.4 + (0.5 - 0.4)*0.05 = 0.405
        // scale: 0.4 + (1.0 - 0.4)*0.05 = 0.43
        assertEquals(0.595f, win.cx,    1e-5f)
        assertEquals(0.405f, win.cy,    1e-5f)
        assertEquals(0.43f,  win.scale, 1e-5f)
        assertNotEquals("must move toward full-frame", current.scale, win.scale)
    }

    @Test fun `no faces ever seen + no lastFace timestamp also reverts to full`() {
        // Edge: lastFaceWallMs = 0 (never seen a face since processor
        // attached). pickTarget should treat as "past hold" and revert.
        val current = CropWindow(0.6f, 0.4f, 0.4f)
        val (win, _, _) = pickTargetReflected(
            boxes = emptyList(), current = current,
            lastFaceWallMs = 0, nowWallMs = 1000, holdMs = 2000,
            targetFaceFraction = 0.30f,
            lastArea = 0f, pendingFrames = 0,
        )
        // EMA toward (0.5, 0.5, 1.0) at 0.05 — same math as above.
        assertEquals(0.595f, win.cx, 1e-5f)
    }

    @Test fun `target face fraction larger than face → smaller crop window`() {
        // Target face fills 60% of frame instead of 30% → tighter crop.
        val (winSmall, _, _) = pickTargetReflected(
            boxes = listOf(box(0.5f, 0.5f, 0.10f)), current = CropWindow(0.5f, 0.5f, 1.0f),
            lastFaceWallMs = 1000, nowWallMs = 2000, holdMs = 2000,
            targetFaceFraction = 0.60f,
            lastArea = 0f, pendingFrames = 0,
        )
        // scale = 0.10 / 0.60 ≈ 0.167
        assertEquals(0.167f, winSmall.scale, 1e-3f)
        assertTrue("tighter crop", winSmall.scale < 0.5f)
    }
}
