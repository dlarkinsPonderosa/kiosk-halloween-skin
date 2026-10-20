// SPDX-License-Identifier: Apache-2.0
package ca.ponderosa.jackolantern;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RecordingCanvas;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;

/**
 * A soft glow blurred on the GPU: the shapes are recorded into a render node
 * whose blur effect the GPU applies as it draws. Android 12 (API 31) and
 * later, on a hardware canvas only. Kept in its own class so older devices
 * never load it.
 */
final class GpuGlow {
    private final RenderNode node = new RenderNode("jack-o-lantern-glow");
    private int blurred = -1;

    /**
     * @param radius the blur radius in pixels
     * @param passes how many times to draw the blurred result; blurring spreads the light thin, and each pass builds it back up
     */
    void draw(Canvas canvas, int width, int height, float radius, int passes, Path[] shapes, Paint paint) {
        int px = Math.max(1, Math.round(radius));
        if (px != blurred) {
            node.setRenderEffect(RenderEffect.createBlurEffect(px, px, Shader.TileMode.DECAL));
            blurred = px;
        }
        node.setPosition(0, 0, width, height);
        node.setClipToBounds(false);
        RecordingCanvas recording = node.beginRecording(width, height);
        try {
            for (Path shape : shapes) recording.drawPath(shape, paint);
        } finally {
            node.endRecording();
        }
        for (int i = 0; i < passes; i++) canvas.drawRenderNode(node);
    }
}
