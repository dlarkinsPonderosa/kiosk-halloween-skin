// SPDX-License-Identifier: Apache-2.0
package ca.ponderosa.jackolantern;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.Build;

/**
 * A jack-o'-lantern drawn in code, one frame per call.
 *
 * Listening: it slowly pushes in and cocks its head, and the candle flickers
 * hard, flaring with the user's voice. Thinking: it ponders. The eyes glance
 * up and to the side, one squints, the head tips the other way, the mouth
 * tightens, the nose spins like a loading spinner and the candle pulses.
 * Answering: the face settles back and the mouth opens with the answer's
 * voice.
 *
 * The light is drawn in layers: a wide soft glow, a tight hot edge, the
 * candlelit inside, and a deeper orange just inside each cut. On Android 12
 * and later the glow is blurred on the GPU; older devices build it from
 * stacked outlines instead.
 *
 * Kept apart from SkinController so a set of rendered frames can replace it
 * without touching anything else. tools/render_art.py draws the icon and
 * thumbnail from the same geometry.
 */
final class Pumpkin {
    /** The five lobes, outer pair first so the middle ones overlap them: centre offset, half width, half height. */
    private static final float[] LOBE_X = {-0.5f, 0.5f, -0.26f, 0.26f, 0f};
    private static final float[] LOBE_W = {0.5f, 0.5f, 0.52f, 0.52f, 0.46f};
    private static final float[] LOBE_H = {0.86f, 0.86f, 0.95f, 0.95f, 1f};
    /** The nose's spin while thinking, in radians a second: one turn a second. */
    private static final float SPIN = (float) (Math.PI * 2);
    /** A triangle looks the same every third of a turn, so the nose can stop at any of these. */
    private static final float THIRD = (float) (Math.PI * 2 / 3);
    /**
     * Where the teeth sit along the mouth, from -1 at the left corner to 1 at
     * the right. Even places hang from the top, odd ones stand on the bottom,
     * so they interlock.
     */
    private static final float[] TEETH = {-0.7f, -0.56f, -0.42f, -0.28f, -0.14f, 0f, 0.14f, 0.28f, 0.42f, 0.56f, 0.7f};
    /** Extra points that keep the curve smooth between each corner and the first tooth. */
    private static final float[] CORNER_LEFT = {-0.94f, -0.86f, -0.78f};
    /** The narrowest band of light left between the rows of teeth, as a share of the pumpkin's half width. */
    private static final float BAND = 0.06f;
    /** Stacked outlines that stand in for the blur on older devices. */
    private static final int STEPS = 14;
    private static final boolean GPU_BLUR = Build.VERSION.SDK_INT >= 31;

    private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rib = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stem = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cut = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint light = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path stemPath = new Path();
    private final Path eyeLeft = new Path();
    private final Path eyeRight = new Path();
    private final Path nose = new Path();
    private final Path mouth = new Path();
    private final Path[] holes = {eyeLeft, eyeRight, nose, mouth};
    private final Matrix place = new Matrix();
    private GpuGlow wide;
    private GpuGlow middle;
    private GpuGlow tight;
    private float shaderCx = Float.NaN, shaderCy, shaderR;
    private float glow = 0.7f;
    /** 0 to 1: how far into the listening pose. */
    private float lean;
    /** 0 to 1: how far into the pondering pose. */
    private float ponder;
    /** The nose's angle, in radians. */
    private float spin;
    private long last;

    Pumpkin() {
        rib.setStyle(Paint.Style.STROKE);
        rib.setColor(0x558A3A05);
        stem.setColor(0xFF5A6B2C);
        glowLine.setStyle(Paint.Style.STROKE);
        glowLine.setStrokeJoin(Paint.Join.ROUND);
        cut.setStyle(Paint.Style.STROKE);
        cut.setStrokeJoin(Paint.Join.ROUND);
        cut.setColor(0xFF7A3205);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeJoin(Paint.Join.ROUND);
    }

    /**
     * @param level the eased voice level, 0 to 1
     * @param phase listening, thinking, answering or resting
     * @param withHalo the full screen look, as opposed to the small docked face
     * @param inTheDark only the lit cutouts on black, with no shell, stem or skin
     */
    void draw(Canvas canvas, int width, int height, float level, String phase, long now, boolean withHalo, boolean inTheDark) {
        float dt = last == 0 ? 0 : Math.min(0.1f, (now - last) / 1000f);
        last = now;
        boolean listening = "listening".equals(phase);
        boolean thinking = "thinking".equals(phase);
        boolean answering = "answering".equals(phase);

        // The nose spins while thinking. After, it carries on to the next point-up angle and stops there.
        if (thinking) {
            spin += dt * SPIN;
        } else if (spin != 0) {
            float next = (float) Math.ceil(spin / THIRD - 1e-4f) * THIRD;
            float step = dt * SPIN;
            spin = next - spin <= step ? 0 : spin + step;
        }

        // The candle: a flicker that never repeats exactly, with now and then a
        // deep dip as the flame gutters. Brighter while listening or answering.
        float flicker = (float) (0.5 * Math.sin(now / 83.0) + 0.3 * Math.sin(now / 41.0 + 1.3) + 0.2 * Math.sin(now / 157.0 + 2.1));
        float gutter = (float) Math.pow(Math.max(0, Math.sin(now / 1300.0 + Math.sin(now / 530.0))), 6);
        float wanted;
        // Listening, the flame is at its most restless.
        if (listening) wanted = 0.6f + 0.32f * flicker - 0.4f * gutter + 0.35f * level;
        else if (thinking) wanted = 0.42f + 0.32f * (float) (0.5 + 0.5 * Math.sin(now / 240.0)) + 0.12f * flicker;
        else if (answering) wanted = 0.74f + 0.2f * flicker - 0.2f * gutter + 0.3f * level;
        else wanted = 0.62f + 0.28f * flicker - 0.3f * gutter;
        glow += (wanted - glow) * 0.35f;
        float t = Math.max(0, Math.min(1.15f, glow));
        float bright = Math.min(1, t);
        // A slow push in while listening, as if intent on what is said.
        lean += ((listening ? 1 : 0) - lean) * 0.06f;
        ponder += ((thinking ? 1 : 0) - ponder) * 0.08f;
        // The mouth follows only the answer. Listening it hangs a little open; pondering it shuts tight.
        float open = answering ? Math.min(1, level * 1.5f) : listening ? 0.08f : thinking ? 0 : 0.02f;

        float size = Math.min(width, height);
        // In the dark there is no shell to fit, so the carved face can be much larger.
        float r = size * (withHalo ? (inTheDark ? 0.42f : 0.33f) : (inTheDark ? 0.46f : 0.4f));
        float hh = r * 0.8f;
        float cx = width / 2f;
        float cy = height / 2f + r * 0.1f;
        float scale = 1 + 0.45f * lean;
        // Lengths after the push in, for blur radii and line widths.
        float rs = r * scale;

        if (withHalo && !inTheDark) {
            int alpha = Math.round(90 * bright);
            halo.setShader(new RadialGradient(cx, cy, r * 1.6f, Color.argb(alpha, 255, 150, 40), Color.argb(0, 255, 150, 40), Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, r * 1.6f, halo);
        }

        // Bob, sway, push in, and tilt: listening cocks the head one way, pondering tips it the other.
        // Applied to the canvas for the shell and to the paths for the light.
        place.reset();
        place.postScale(scale, scale, cx, cy);
        place.postRotate(2f * (float) Math.sin(now / 1700.0) + 10f * lean - 6f * ponder, cx, cy + hh);
        place.postTranslate(0, r * 0.02f * (float) Math.sin(now / 1100.0));

        if (!inTheDark) {
            canvas.save();
            canvas.concat(place);
            drawStem(canvas, cx, cy - hh, r);
            drawBody(canvas, cx, cy, r, hh);
            canvas.restore();
        }

        // Pondering, the eyes glance up and to the right and wander a little. The nose and
        // mouth follow less, which gives the face some depth.
        float glance = ponder * r * (0.12f + 0.03f * (float) Math.sin(now / 700.0));
        float lift = ponder * r * (0.06f + 0.015f * (float) Math.sin(now / 1100.0));
        // The right eye squints; the left opens a touch wider.
        buildEye(eyeLeft, -1, cx + glance, cy - lift, r, 1 + 0.08f * ponder);
        buildEye(eyeRight, 1, cx + glance, cy - lift, r, 1 - 0.45f * ponder);
        buildNose(cx + glance * 0.5f, cy, r);
        // A tight, narrower grimace while pondering.
        buildMouth(cx + glance * 0.3f, cy, r, 1 - 0.12f * ponder, open);
        for (Path hole : holes) hole.transform(place);

        drawGlow(canvas, width, height, rs, bright, inTheDark);

        if (!inTheDark) {
            // The cut edge of the shell, around each hole.
            cut.setStrokeWidth(rs * 0.045f);
            for (Path hole : holes) canvas.drawPath(hole, cut);
        }
        light.setColor(candle(t));
        for (Path hole : holes) canvas.drawPath(hole, light);
        drawInnerEdge(canvas, rs, bright);
    }

    /**
     * Light thrown out of the cuts: a wide soft glow and a tight hot edge.
     * Blurred on the GPU where it can be, otherwise outlines that widen and
     * fade build the same falloff.
     */
    private void drawGlow(Canvas canvas, int width, int height, float rs, float bright, boolean inTheDark) {
        int wideColor = inTheDark ? Color.rgb(255, 140, 30) : Color.rgb(255, 170, 60);
        int tightColor = Color.rgb(255, 120, 20);
        if (GPU_BLUR && canvas.isHardwareAccelerated()) {
            if (wide == null) {
                wide = new GpuGlow();
                middle = new GpuGlow();
                tight = new GpuGlow();
            }
            int alpha = Math.round(255 * bright);
            glowFill.setColor(wideColor);
            glowFill.setAlpha(alpha);
            wide.draw(canvas, width, height, rs * 0.2f, 2, holes, glowFill);
            middle.draw(canvas, width, height, rs * 0.085f, 1, holes, glowFill);
            glowFill.setColor(tightColor);
            glowFill.setAlpha(alpha);
            tight.draw(canvas, width, height, rs * 0.037f, 1, holes, glowFill);
            return;
        }
        // Widest and faintest first. Each outline adds to the ones inside it, so the light falls off away from the cut.
        for (int k = STEPS; k >= 1; k--) {
            float share = k / (float) STEPS;
            glowLine.setColor(k <= 4 ? tightColor : wideColor);
            glowLine.setAlpha(Math.round(255 * bright * (k <= 4 ? 0.15f : 0.06f)));
            glowLine.setStrokeWidth(rs * 0.34f * share);
            for (Path hole : holes) canvas.drawPath(hole, glowLine);
        }
    }

    /** A deeper orange just inside each cut, fading toward the middle, so the holes look carved through a thick shell. */
    private void drawInnerEdge(Canvas canvas, float rs, float bright) {
        edge.setColor(Color.rgb(255, 120, 20));
        float[] widths = {0.12f, 0.1f, 0.08f, 0.06f, 0.04f, 0.02f};
        float[] alphas = {0.12f, 0.12f, 0.13f, 0.15f, 0.18f, 0.22f};
        for (Path hole : holes) {
            canvas.save();
            canvas.clipPath(hole);
            for (int i = 0; i < widths.length; i++) {
                edge.setStrokeWidth(rs * widths[i]);
                edge.setAlpha(Math.round(255 * alphas[i] * (0.6f + 0.4f * bright)));
                canvas.drawPath(hole, edge);
            }
            canvas.restore();
        }
    }

    /**
     * An angry eye: a crescent pointed at both ends, its top edge sloping down toward the nose like a frown.
     * @param tall 1 for a normal eye, less to squint, more to open wide
     */
    private void buildEye(Path eye, int side, float fx, float cy, float r, float tall) {
        float ex = fx + side * 0.36f * r;
        float ey = cy - 0.2f * r;
        float e = r * 0.21f;
        float outerX = ex + side * 1.05f * e, outerY = ey - 0.62f * e * tall;
        float innerX = ex - side * 1.0f * e, innerY = ey + 0.22f * e * tall;
        eye.reset();
        eye.moveTo(outerX, outerY);
        eye.lineTo(innerX, innerY);
        eye.quadTo(ex + side * 0.15f * e, ey + 1.0f * e * tall, outerX, outerY);
        eye.close();
    }

    /** A small triangle, point up when still, spun about its own centre a third of the way up from its base. */
    private void buildNose(float x, float cy, float r) {
        float centreY = cy + 0.08f * r;
        float cos = (float) Math.cos(spin), sin = (float) Math.sin(spin);
        float[] corners = {x, cy, x - 0.08f * r, cy + 0.12f * r, x + 0.08f * r, cy + 0.12f * r};
        nose.reset();
        for (int i = 0; i < corners.length; i += 2) {
            float dx = corners[i] - x, dy = corners[i + 1] - centreY;
            float px = x + dx * cos - dy * sin, py = centreY + dx * sin + dy * cos;
            if (i == 0) nose.moveTo(px, py); else nose.lineTo(px, py);
        }
        nose.close();
    }

    /**
     * A wide grin whose corners sweep up toward the eyes. Its outline follows
     * a row of jagged teeth along each edge: closed, the teeth interlock; as
     * the mouth opens they part with their edges.
     */
    private void buildMouth(float fx, float cy, float r, float squeeze, float open) {
        float half = 0.72f * r * squeeze;
        float base = cy + 0.3f * r;
        mouth.reset();
        // Top edge, left to right: smooth at the corners, then a tooth hanging down at every top position.
        mouth.moveTo(fx - half, upper(-1, base, r));
        for (float u : CORNER_LEFT) mouth.lineTo(fx + u * half, upper(u, base, r));
        for (int i = 0; i < TEETH.length; i++) {
            float u = TEETH[i];
            mouth.lineTo(fx + u * half, upper(u, base, r) + (i % 2 == 0 ? tooth(u, r, open) : 0));
        }
        for (int i = CORNER_LEFT.length - 1; i >= 0; i--) {
            float u = -CORNER_LEFT[i];
            mouth.lineTo(fx + u * half, upper(u, base, r));
        }
        mouth.lineTo(fx + half, upper(1, base, r));
        // Bottom edge, right to left: a tooth standing up at every bottom position.
        for (float u : CORNER_LEFT) mouth.lineTo(fx - u * half, upper(-u, base, r) + gap(-u, r, open));
        for (int i = TEETH.length - 1; i >= 0; i--) {
            float u = TEETH[i];
            mouth.lineTo(fx + u * half, upper(u, base, r) + gap(u, r, open) - (i % 2 == 1 ? tooth(u, r, open) : 0));
        }
        for (int i = CORNER_LEFT.length - 1; i >= 0; i--) {
            float u = CORNER_LEFT[i];
            mouth.lineTo(fx + u * half, upper(u, base, r) + gap(u, r, open));
        }
        mouth.close();
    }

    private void drawStem(Canvas canvas, float x, float top, float r) {
        stemPath.reset();
        stemPath.moveTo(x - 0.08f * r, top + 0.14f * r);
        stemPath.quadTo(x - 0.07f * r, top - 0.16f * r, x + 0.03f * r, top - 0.33f * r);
        stemPath.lineTo(x + 0.15f * r, top - 0.27f * r);
        stemPath.quadTo(x + 0.06f * r, top - 0.13f * r, x + 0.08f * r, top + 0.14f * r);
        stemPath.close();
        canvas.drawPath(stemPath, stem);
    }

    private void drawBody(Canvas canvas, float cx, float cy, float r, float hh) {
        if (cx != shaderCx || cy != shaderCy || r != shaderR) {
            // Lit from the upper left, darker toward the edges.
            body.setShader(new RadialGradient(cx - 0.25f * r, cy - 0.35f * r, 1.5f * r,
                new int[] {0xFFFFB347, 0xFFF2801C, 0xFFC2570B, 0xFF8E3B06},
                new float[] {0, 0.45f, 0.8f, 1}, Shader.TileMode.CLAMP));
            shaderCx = cx;
            shaderCy = cy;
            shaderR = r;
        }
        rib.setStrokeWidth(r * 0.02f);
        for (int i = 0; i < LOBE_X.length; i++) {
            float x = cx + LOBE_X[i] * r;
            float w = LOBE_W[i] * r;
            float h = LOBE_H[i] * hh;
            canvas.drawOval(x - w, cy - h, x + w, cy + h, body);
            canvas.drawOval(x - w, cy - h, x + w, cy + h, rib);
        }
    }

    private static float upper(float u, float base, float r) {
        return base + 0.1f * r * (1 - u * u) - 0.16f * r * u * u * u * u;
    }

    private static float gap(float u, float r, float open) {
        return (0.16f * r + 0.32f * r * open) * (float) Math.sqrt(Math.max(0, 1 - u * u));
    }

    /**
     * How far a tooth reaches into the mouth: shorter toward the corners,
     * and never so far that the light between the rows closes up. Top and
     * bottom teeth alternate, so the edges can't cross while each tooth
     * leaves a band of light.
     */
    private static float tooth(float u, float r, float open) {
        float full = 0.14f * r * (0.6f + 0.4f * (float) Math.sqrt(Math.max(0, 1 - u * u)));
        return Math.min(full, Math.max(0, gap(u, r, open) - BAND * r));
    }

    /** Dark ember at 0, warm yellow at 1, nearly white past it. */
    private static int candle(float t) {
        if (t <= 1) return mix(0xFF3A1505, 0xFFFFD45C, t);
        return mix(0xFFFFD45C, 0xFFFFF2B0, (t - 1) / 0.15f);
    }

    private static int mix(int from, int to, float amount) {
        float a = Math.max(0, Math.min(1, amount));
        return Color.rgb(
            Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * a),
            Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * a),
            Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * a));
    }
}
