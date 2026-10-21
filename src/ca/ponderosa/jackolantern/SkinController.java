// SPDX-License-Identifier: Apache-2.0
package ca.ponderosa.jackolantern;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import me.jxl.kiosk.plugins.KsTheme;
import me.jxl.kiosk.plugins.OverlayFactory;
import me.jxl.kiosk.plugins.OverlaySpec;
import me.jxl.kiosk.plugins.PluginHost;

/**
 * Draws the Voice Satellite turn while this plugin's skin is picked. Follows
 * the voice skin contract: draw only while selected, follow voice.overlay for
 * when to show, and honour the Appearance settings (Theme, Background, Text
 * size, Reactive activity bar).
 */
final class SkinController {
    static final String KEY = "jack_o_lantern";
    /** Where the docked bubble sits above the bottom edge, in dp. */
    static final int DOCK_INSET = 24;
    static final String[] EVENTS = {"voice.skin", "voice.overlay", "voice.level", "voice.text", "voice.appearance"};
    /** The Whole pumpkin backdrop's opacity when Background is left at Skin default. In the dark is solid black. */
    static final float DEFAULT_BACKGROUND = 0.88f;

    private PluginHost host;
    private int generation;
    private boolean selected;
    private boolean skinHeard;
    private boolean appearanceHeard;
    private String overlay = "hidden";
    private String drawn;

    // Read by the view on the main thread.
    volatile float level;
    volatile String state = "idle";
    volatile String command = "";
    volatile String answer = "";
    volatile String tool = "";
    volatile String theme = "auto";
    /** Null for Skin default. */
    volatile Float background;
    volatile float textScale = 1;
    volatile boolean reactive = true;
    /** Only the lit cutouts on black. */
    volatile boolean inTheDark = true;
    /** The Animation quality setting: Auto, High, Low or Off. */
    volatile String quality = "Auto";
    /** The showing view. Touched on the main thread only. */
    private SkinView view;
    /** What each look has learned about this device's speed. Kept across turns: each turn builds a new view. */
    private final FrameBudget fullBudget = new FrameBudget();
    private final FrameBudget dockedBudget = new FrameBudget();

    synchronized void start(PluginHost host) {
        this.host = host;
        int current = ++generation;
        try {
            for (String event : EVENTS) host.subscribe(event);
        } catch (IllegalArgumentException | UnsupportedOperationException olderHost) {
            host.status("This Kiosk Satellite version has no voice skins.", true);
            return;
        }
        try {
            host.subscribe("voice.state");
        } catch (IllegalArgumentException | UnsupportedOperationException olderHost) {
            // Without it the face guesses the phase from the words.
        }
        read(current);
    }

    private void read(int current) {
        // Events win over these reads: they are newer.
        host.executeCommand("getVoiceState", Collections.<String, Object>emptyMap(), (ok, data, error) -> {
            synchronized (SkinController.this) {
                if (current != generation || !ok || !(data instanceof Map)) return;
                Map<?, ?> voice = (Map<?, ?>) data;
                if (!skinHeard) selected = Boolean.TRUE.equals(voice.get("skinSelected"));
                if ("hidden".equals(overlay) && voice.get("overlay") instanceof String) overlay = (String) voice.get("overlay");
                if (voice.get("state") instanceof String) state = (String) voice.get("state");
                update();
            }
        });
        host.executeCommand("getVoiceAppearance", Collections.<String, Object>emptyMap(), (ok, data, error) -> {
            synchronized (SkinController.this) {
                if (current == generation && ok && data instanceof Map && !appearanceHeard) appearance((Map<?, ?>) data);
            }
        });
        host.executeCommand("getVoiceText", Collections.<String, Object>emptyMap(), (ok, data, error) -> {
            synchronized (SkinController.this) {
                if (current == generation && ok && data instanceof Map && command.isEmpty() && answer.isEmpty()) words((Map<?, ?>) data);
            }
        });
    }

    synchronized void configure(Map<String, Object> settings) {
        inTheDark = !"Whole pumpkin".equals(settings.get("pumpkinStyle"));
        Object chosen = settings.get("animationQuality");
        quality = chosen instanceof String ? (String) chosen : "Auto";
        restyleLater();
    }

    synchronized void onEvent(String event, Map<String, Object> payload) {
        if (host == null) return;
        if ("ks.voice.skin".equals(event)) {
            selected = Boolean.TRUE.equals(payload.get("selected"));
            skinHeard = true;
        } else if ("ks.voice.overlay".equals(event)) {
            overlay = String.valueOf(payload.get("overlay"));
        } else if ("ks.voice.level".equals(event)) {
            Object value = payload.get("level");
            level = value instanceof Number ? ((Number) value).floatValue() : 0;
            return;
        } else if ("ks.voice.state".equals(event)) {
            Object value = payload.get("state");
            state = value instanceof String ? (String) value : "idle";
            return;
        } else if ("ks.voice.text".equals(event)) {
            words(payload);
            return;
        } else if ("ks.voice.appearance".equals(event)) {
            appearanceHeard = true;
            appearance(payload);
            return;
        } else {
            return;
        }
        update();
    }

    /** Which part of the turn the face is in: listening, thinking, answering or resting. */
    String phase() {
        String now = state;
        if ("listening".equals(now)) return "listening";
        if ("processing".equals(now)) return "thinking";
        if ("responding".equals(now)) return "answering";
        // No voice.state, or the answer stays up after it is spoken: guess from the words.
        if (!command.isEmpty() && answer.isEmpty() && tool.isEmpty()) return "thinking";
        return "resting";
    }

    private void appearance(Map<?, ?> settings) {
        theme = settings.get("theme") instanceof String ? (String) settings.get("theme") : "auto";
        background = settings.get("background") instanceof Number ? ((Number) settings.get("background")).floatValue() : null;
        textScale = settings.get("textScale") instanceof Number ? ((Number) settings.get("textScale")).floatValue() : 1;
        reactive = !Boolean.FALSE.equals(settings.get("reactive"));
        restyleLater();
    }

    private void words(Map<?, ?> text) {
        command = text.get("command") instanceof String ? (String) text.get("command") : "";
        answer = text.get("answer") instanceof String ? (String) text.get("answer") : "";
        Object tools = text.get("tools");
        tool = tools instanceof List && !((List<?>) tools).isEmpty() ? String.valueOf(((List<?>) tools).get(((List<?>) tools).size() - 1)) : "";
        SkinView current = view;
        if (current != null) current.post(current::refresh);
    }

    private void restyleLater() {
        SkinView current = view;
        if (current != null) current.post(current::restyle);
    }

    /** Shows the skin while it is picked and the overlay is up, in the look that matches it. */
    private void update() {
        String want = selected && ("full".equals(overlay) || "docked".equals(overlay)) ? overlay : null;
        if (want == null ? drawn == null : want.equals(drawn)) return;
        drawn = want;
        if (want == null) {
            host.hideOverlay(KEY);
            level = 0;
        } else if ("full".equals(want)) {
            host.showOverlay(KEY, animated(OverlaySpec.fullScreen().onTop(true).touchable(false).closeOnBack(false), OverlaySpec.FADE, 300), new Skin(false));
        } else {
            // A bubble at the bottom. Touches reach the dashboard around and under it.
            host.showOverlay(KEY, animated(OverlaySpec.at(OverlaySpec.BOTTOM).inset(DOCK_INSET).onTop(true).touchable(false).closeOnBack(false), OverlaySpec.SLIDE, 250), new Skin(true));
        }
    }

    /** Transitions, or none on Kiosk Satellite versions without them. */
    private static OverlaySpec animated(OverlaySpec spec, String transition, int ms) {
        try {
            return spec.enter(transition).exit(transition).transitionDuration(ms);
        } catch (LinkageError olderHost) {
            return spec;
        }
    }

    synchronized void stop() {
        host = null;
        generation++;
        selected = false;
        skinHeard = false;
        appearanceHeard = false;
        overlay = "hidden";
        drawn = null;
        level = 0;
        state = "idle";
        command = answer = tool = "";
        theme = "auto";
        background = null;
        textScale = 1;
        reactive = true;
    }

    private final class Skin implements OverlayFactory {
        private final boolean docked;

        Skin(boolean docked) { this.docked = docked; }

        @Override public View create(Context context, KsTheme theme) {
            SkinView skin = new SkinView(context, theme, docked);
            view = skin;
            return skin;
        }

        @Override public void onThemeChanged(View view, KsTheme theme) {
            ((SkinView) view).restyle(theme);
        }

        @Override public void onDestroy(View destroyed) {
            if (view == destroyed) view = null;
        }
    }

    /** Full screen: a backdrop, the face and the conversation under it. Docked: a card with a small face beside the words. */
    private final class SkinView extends FrameLayout {
        private final boolean docked;
        private final FaceView face;
        private final TextView said;
        private final TextView answered;
        private final LinearLayout card;
        private KsTheme ksTheme;

        SkinView(Context context, KsTheme theme, boolean docked) {
            super(context);
            this.ksTheme = theme;
            this.docked = docked;
            face = new FaceView(context, docked);
            said = new TextView(context);
            answered = new TextView(context);
            // The listening push-in carries the glow past the face's own square. Let it draw there
            // instead of being cut off in a straight line.
            setClipChildren(false);
            setClipToPadding(false);
            int screenWidth = context.getResources().getDisplayMetrics().widthPixels;
            int screenHeight = context.getResources().getDisplayMetrics().heightPixels;
            if (docked) {
                card = new LinearLayout(context);
                card.setOrientation(LinearLayout.HORIZONTAL);
                card.setClipChildren(false);
                card.setClipToPadding(false);
                card.setGravity(Gravity.CENTER_VERTICAL);
                int faceSize = theme.px(80);
                card.addView(face, new LinearLayout.LayoutParams(faceSize, faceSize));
                LinearLayout words = new LinearLayout(context);
                words.setOrientation(LinearLayout.VERTICAL);
                answered.setMaxLines(3);
                answered.setEllipsize(TextUtils.TruncateAt.END);
                words.addView(said);
                words.addView(answered);
                LinearLayout.LayoutParams wordsParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
                wordsParams.leftMargin = theme.px(16);
                card.addView(words, wordsParams);
                int width = Math.min(theme.px(560), screenWidth - theme.px(48));
                addView(card, new FrameLayout.LayoutParams(width, LayoutParams.WRAP_CONTENT));
            } else {
                card = null;
                said.setGravity(Gravity.CENTER);
                answered.setGravity(Gravity.CENTER);
                LinearLayout column = new LinearLayout(context);
                column.setOrientation(LinearLayout.VERTICAL);
                column.setClipChildren(false);
                column.setClipToPadding(false);
                column.setGravity(Gravity.CENTER);
                int size = Math.round(Math.min(screenWidth, screenHeight) * 0.72f);
                column.addView(face, new LinearLayout.LayoutParams(size, size));
                LinearLayout.LayoutParams line = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                line.topMargin = theme.px(8);
                column.addView(said, line);
                LinearLayout.LayoutParams answerLine = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                answerLine.topMargin = theme.px(8);
                column.addView(answered, answerLine);
                int side = theme.px(48);
                column.setPadding(side, 0, side, 0);
                addView(column, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            }
            restyle();
            refresh();
        }

        void restyle(KsTheme next) {
            ksTheme = next;
            restyle();
        }

        void restyle() {
            // The cutouts only work on black, whatever the theme.
            boolean blackout = inTheDark;
            boolean dark = blackout || "dark".equals(theme) || (!"light".equals(theme) && ksTheme.isDark());
            int backdrop = blackout ? 0xFF000000 : dark ? 0xFF0F0A14 : 0xFFFFF3E6;
            int text = dark ? 0xFFF2ECE6 : 0xFF241A12;
            int muted = dark ? 0xFFB9AFA6 : 0xFF6A5D52;
            float scale = textScale;
            if (docked) {
                GradientDrawable bubble = new GradientDrawable();
                bubble.setColor(blackout ? 0xFF000000 : dark ? 0xFF1C1324 : 0xFFFFF8F0);
                bubble.setCornerRadius(ksTheme.px(ksTheme.radiusCard()));
                bubble.setStroke(Math.max(1, ksTheme.px(1) / 2), 0x66F2801C);
                card.setBackground(bubble);
                int pad = ksTheme.px(12);
                card.setPadding(pad, pad, ksTheme.px(20), pad);
                ksTheme.styleText(said, 14 * scale, 400, "onSurfaceVariant");
                ksTheme.styleText(answered, 17 * scale, 500, "onSurface");
            } else {
                // In the dark defaults to solid black; Background still overrides it.
                Float chosen = background;
                float opacity = chosen != null ? chosen : blackout ? 1f : DEFAULT_BACKGROUND;
                setBackgroundColor(Color.argb(Math.round(255 * opacity), Color.red(backdrop), Color.green(backdrop), Color.blue(backdrop)));
                ksTheme.styleText(said, 20 * scale, 400, "onSurfaceVariant");
                ksTheme.styleText(answered, 28 * scale, 500, "onSurface");
            }
            said.setTextColor(muted);
            answered.setTextColor(text);
            face.invalidate();
        }

        void refresh() {
            said.setText(command);
            said.setVisibility(command.isEmpty() ? GONE : VISIBLE);
            answered.setText(answer.isEmpty() ? tool : answer);
            answered.setVisibility(answer.isEmpty() && tool.isEmpty() ? GONE : VISIBLE);
        }

        /**
         * The face, redrawn every frame, or about 30 times a second where the
         * Pumpkin asks for that. Eases toward the latest level so 30 readings a
         * second still move smoothly.
         */
        private final class FaceView extends View {
            private final boolean small;
            private final Pumpkin pumpkin;
            private float shown;

            FaceView(Context context, boolean small) {
                super(context);
                this.small = small;
                pumpkin = new Pumpkin(small ? dockedBudget : fullBudget);
            }

            @Override protected void onDraw(Canvas canvas) {
                // With Reactive activity bar off nothing follows the voice. KS sends 0 then too.
                shown += ((reactive ? level : 0) - shown) * 0.3f;
                pumpkin.draw(canvas, getWidth(), getHeight(), shown, phase(), SystemClock.uptimeMillis(), !small, inTheDark, quality);
                if (pumpkin.capped()) postInvalidateDelayed(33);
                else postInvalidateOnAnimation();
            }
        }
    }
}
