// SPDX-License-Identifier: Apache-2.0
package ca.ponderosa.jackolantern;

/**
 * How much glow detail a device can draw smoothly, learned from its own frame
 * times. Kept by SkinController for as long as the plugin runs, so what one
 * turn learns carries into the next: a new overlay view is built for every
 * turn.
 *
 * Only frames while the face is on screen count. After about 45 of them at
 * one level, an average below 30 frames a second drops a level. After 3600
 * frames at a lower level (a minute or two of the face on screen, spread over
 * many turns) it tries the next level up, in case something else was slowing
 * the device. Once it has had to step down twice it stays down.
 */
final class FrameBudget {
    /** 2 is full quality; 1 and 0 are for devices too slow to draw it smoothly. */
    private int quality = 2;
    /** The average time between frames, in seconds. */
    private float frameTime;
    /** Frames seen at this quality. */
    private int frames;
    /** How many times it has stepped down. */
    private int strikes;

    synchronized int quality() {
        return quality;
    }

    /**
     * @param dt seconds since the previous frame
     * @param fresh the first frame after the face was hidden, which says nothing about speed
     */
    synchronized void frame(float dt, boolean fresh) {
        if (fresh) return;
        frameTime = frames == 0 ? dt : frameTime + (dt - frameTime) * 0.05f;
        frames++;
        if (frames > 45 && frameTime > 1 / 30f && quality > 0) {
            quality--;
            strikes++;
            frames = 0;
        } else if (frames > 3600 && quality < 2 && strikes < 2) {
            quality++;
            frames = 0;
        }
    }
}
