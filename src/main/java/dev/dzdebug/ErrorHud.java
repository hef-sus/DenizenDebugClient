package dev.dzdebug;

import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * A chat-sized box in the bottom-right corner that lists the latest errors (newest at the bottom, like chat).
 * Unfocused: ~90 px high, shown for 10 s after an error and then fades out (a small badge stays while errors are unread).
 * While the chat is open (T): full 180 px height with a header; click it to open the console.
 */
final class ErrorHud {
    private static final long SHOW_MS = 10_000;
    private static final long FADE_IN_MS = 250;
    private static final long FADE_OUT_MS = 600;
    private static final int LINE_H = 10;

    private static boolean visible;
    private static int bx, by, bw, bh;

    // animation state: 0 hidden, 1 badge, 2 box
    private static int mode = 0;
    private static long shownSince = 0;

    private ErrorHud() {}

    static boolean hit(double x, double y) {
        return visible && x >= bx && x < bx + bw && y >= by && y < by + bh;
    }

    private static int alpha(int argb, float p) {
        int a = (int) (((argb >>> 24) & 0xFF) * p);
        return (a << 24) | (argb & 0xFFFFFF);
    }

    static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
        visible = false;
        Minecraft mc = Minecraft.getInstance();
        Screen screen = mc.gui.screen();
        List<LogStore.Row> live = LogStore.live();
        if (live.isEmpty() || screen instanceof ConsoleScreen) {
            mode = 0;
            return;
        }

        boolean chatOpen = screen instanceof ChatScreen;
        long now = System.currentTimeMillis();
        long age = now - LogStore.lastAt();

        int newMode;
        if (chatOpen) {
            newMode = 2;
        } else if (LogStore.unread() > 0) {
            newMode = age < SHOW_MS ? 2 : 1;
        } else {
            newMode = 0;
        }
        if (newMode == 0) {
            mode = 0;
            return;
        }
        if (newMode != mode) shownSince = now;
        mode = newMode;

        float t = Math.min(1f, (now - shownSince) / (float) FADE_IN_MS);
        float in = 1f - (1f - t) * (1f - t) * (1f - t);                       // ease-out cubic
        float out = 1f;
        if (newMode == 2 && !chatOpen && age > SHOW_MS - FADE_OUT_MS) {       // fade out like chat
            out = Math.max(0f, 1f - (age - (SHOW_MS - FADE_OUT_MS)) / (float) FADE_OUT_MS);
        }
        float p = in * out;
        int slide = Math.round((1f - in) * 12);                               // slides in from the right
        boolean text = p >= 0.08f;                                            // very low alpha text would render opaque

        Font font = mc.font;
        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();
        int bottom = sh - 40;                                                  // where the vanilla chat ends
        int w = Math.max(180, Math.min(320, (int) (sw * 0.45f)));
        int x = sw - 4 - w;

        if (newMode == 1) {
            String s = "\u26a0 " + LogStore.unread();
            int bwid = font.width(s) + 14;
            int bxx = sw - 4 - bwid;
            int byy = bottom - 16;
            set(bxx, byy, bwid, 16);
            Ui.roundRect(g, bxx + slide, byy, bxx + slide + bwid, byy + 16, 4, alpha(0xEE111114, p));
            g.fill(bxx + bwid + slide - 2, byy + 3, bxx + bwid + slide, byy + 13, alpha(Ui.RED, p));
            if (text) g.text(font, s, bxx + 7 + slide, byy + 4, alpha(Ui.RED_SOFT, p), false);
            return;
        }

        int h = Math.min(chatOpen ? 180 : 90, sh / 2);
        int y = bottom - h;
        set(x, y, w, h);
        int ox = x + slide;

        Ui.roundRect(g, ox, y, ox + w, y + h, 4, alpha(0xD0101014, p));
        g.fill(ox + w - 2, y + 4, ox + w, y + h - 4, alpha(Ui.RED, p));

        int areaTop = y + 4;
        if (chatOpen) {
            if (text) {
                g.text(font, Component.literal("Denizen errors").withStyle(ChatFormatting.BOLD),
                        ox + 8, y + 5, alpha(Ui.RED_SOFT, p), false);
                String hint = "Click to open console  \u00b7  F4";
                g.text(font, hint, ox + w - 10 - font.width(hint), y + 5, alpha(Ui.MUTED, p), false);
            }
            g.fill(ox + 6, y + 16, ox + w - 8, y + 17, alpha(Ui.DIVIDER, p));
            areaTop = y + 20;
        }
        int areaBottom = y + h - 4;
        int maxLines = Math.max(1, (areaBottom - areaTop) / LINE_H);

        // newest at the bottom, like chat; identical errors are merged into one line with (n)
        List<LogStore.Row> tail = live.size() > 80 ? live.subList(live.size() - 80, live.size()) : live;
        List<LogStore.Row> rows = LogStore.dedup(tail);
        int n = Math.min(maxLines, rows.size());

        for (int i = 0; i < n; i++) {
            LogStore.Row r = rows.get(rows.size() - n + i);
            int ly = areaBottom - (n - i) * LINE_H + 1;
            if (!text) continue;

            int cx = ox + 8;
            int right = ox + w - 8;

            String time = r.timeText();
            g.text(font, time, cx, ly, alpha(Ui.MUTED, p), false);
            cx += font.width(time) + 5;

            String head = Ui.fit(font, r.script + ":" + r.line, Math.max(30, (right - cx) / 2));
            g.text(font, head, cx, ly, alpha(Ui.RED, p), false);
            cx += font.width(head);

            if (r.count > 1) {
                String c = " (" + r.count + ")";
                g.text(font, c, cx, ly, alpha(0xFFE0B341, p), false);
                cx += font.width(c);
            }

            cx += 6;
            if (right - cx > 20) {
                String msg = Ui.fit(font, r.message.split("\\R", 2)[0], right - cx);
                g.text(font, msg, cx, ly, alpha(Ui.TEXT, p), false);
            }
        }
    }

    private static void set(int x, int y, int w, int h) {
        visible = true;
        bx = x;
        by = y;
        bw = w;
        bh = h;
    }
}
