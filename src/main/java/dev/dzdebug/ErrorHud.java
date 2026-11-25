package dev.dzdebug;

import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;

/**
 * Error box in the bottom-right corner that behaves like the vanilla chat:
 *  - errors are word-wrapped over as many lines as they need (nothing is cut off), newest at the bottom,
 *  - while the chat is closed only recent lines show (10 s, each line fades out on its own),
 *  - while the chat is open (T) the whole history shows in a 180 px panel, scrollable with the mouse wheel;
 *    click the panel to open the console,
 *  - F3+D clears it (see ChatClearMixin / LogStore.clearBox()).
 */
final class ErrorHud {
    private static final long SHOW_MS = 10_000;
    private static final long LINE_IN_MS = 250;
    private static final int LINE_H = 10;
    private static final int MAX_UNFOCUSED_LINES = 9;
    private static final int PAD = 6;

    private record HLine(FormattedCharSequence text, long time, boolean first) {}

    // layout cache
    private static List<HLine> lines = List.of();
    private static int layoutVersion = -1;
    private static int layoutW = -1;

    // chat-like scrolling (lines from the bottom), only while the chat is open
    private static int scrollLines = 0;
    private static int maxScroll = 0;

    // hit rect (chat open only)
    private static boolean visible;
    private static int bx, by, bw, bh;

    private static boolean wasChat = false;
    private static long chatSince = 0;

    private ErrorHud() {}

    static boolean hit(double x, double y) {
        return visible && x >= bx && x < bx + bw && y >= by && y < by + bh;
    }

    /** Mouse wheel over the panel scrolls it. @return true if the event was consumed. */
    static boolean scroll(double x, double y, double amount) {
        if (!hit(x, y)) return false;
        scrollLines = Mth.clamp(scrollLines + (amount > 0 ? 3 : -3), 0, maxScroll);
        return true;
    }

    private static int alpha(int argb, float p) {
        int a = (int) (((argb >>> 24) & 0xFF) * p);
        return (a << 24) | (argb & 0xFFFFFF);
    }

    // ------------------------------------------------------------------ layout

    private static void ensureLayout(Font font, int textW) {
        long cleared = LogStore.boxClearedAt();
        if (layoutVersion == LogStore.version() && layoutW == textW) return;

        List<LogStore.Row> live = LogStore.live();
        List<LogStore.Row> tail = new ArrayList<>();
        for (int i = Math.max(0, live.size() - 120); i < live.size(); i++) {
            LogStore.Row r = live.get(i);
            if (r.time > cleared) tail.add(r);
        }
        List<LogStore.Row> rows = LogStore.dedup(tail);

        List<HLine> out = new ArrayList<>();
        for (LogStore.Row r : rows) {
            String[] paras = r.message.split("\\R", -1);
            MutableComponent head = Component.literal("[" + r.timeText() + "] ").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(r.script + ":" + r.line).withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
            if (r.count > 1) {
                head.append(Component.literal(" (" + r.count + ")").withStyle(ChatFormatting.YELLOW));
            }
            head.append(Component.literal("  " + paras[0]).withStyle(ChatFormatting.WHITE));

            boolean first = true;
            for (FormattedCharSequence s : font.split(head, textW)) {
                out.add(new HLine(s, r.time, first));
                first = false;
            }
            for (int i = 1; i < paras.length; i++) {
                for (FormattedCharSequence s : font.split(
                        Component.literal("    " + paras[i]).withStyle(ChatFormatting.WHITE), textW)) {
                    out.add(new HLine(s, r.time, false));
                }
            }
        }

        // keep the view anchored if the user scrolled up while new lines arrive (like chat)
        if (scrollLines > 0 && out.size() > lines.size()) {
            scrollLines += out.size() - lines.size();
        }
        lines = out;
        layoutVersion = LogStore.version();
        layoutW = textW;
    }

    // ------------------------------------------------------------------ render

    static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
        visible = false;
        Minecraft mc = Minecraft.getInstance();
        Screen screen = mc.gui.screen();
        if (screen instanceof ConsoleScreen) {
            wasChat = false;
            return;
        }

        boolean chatOpen = screen instanceof ChatScreen;
        if (!chatOpen) scrollLines = 0; // like chat: scroll resets when it closes

        Font font = mc.font;
        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();
        int bottom = sh - 40;                                              // where the vanilla chat ends
        int w = Math.max(180, Math.min(320, (int) (sw * 0.45f)));
        int x = sw - 4 - w;
        long now = System.currentTimeMillis();

        ensureLayout(font, w - 2 * PAD - 6);
        if (!chatOpen) {
            wasChat = false;
        } else if (!wasChat) {
            wasChat = true;
            chatSince = now;
        }
        if (lines.isEmpty()) return;

        if (chatOpen) {
            LogStore.markRead();
            drawFocused(g, font, now, x, bottom, w, sh);
        } else {
            drawRecent(g, font, now, x, bottom, w, sw);
        }
    }

    /** Chat closed: only lines younger than 10 s, each fading out on its own and sliding in when new. */
    private static void drawRecent(GuiGraphicsExtractor g, Font font, long now, int x, int bottom, int w, int sw) {
        List<HLine> vis = new ArrayList<>();
        for (int i = lines.size() - 1; i >= 0 && vis.size() < MAX_UNFOCUSED_LINES; i--) {
            HLine l = lines.get(i);
            if (now - l.time() >= SHOW_MS) break; // lines are ordered oldest -> newest
            vis.add(l);
        }

        if (vis.isEmpty()) {
            if (LogStore.unread() > 0) drawBadge(g, font, bottom, sw);
            return;
        }
        for (int k = 0; k < vis.size(); k++) {
            HLine l = vis.get(k);
            long age = now - l.time();

            float f = Mth.clamp((1f - age / (float) SHOW_MS) * 10f, 0f, 1f); // vanilla chat fade-out curve
            f *= f;
            float t = Math.min(1f, age / (float) LINE_IN_MS);
            float in = 1f - (1f - t) * (1f - t) * (1f - t);                   // ease-out cubic fade/slide in
            float p = f * in;
            if (p < 0.08f) continue; // very low alpha text would render opaque

            drawLine(g, font, l, x + Math.round((1f - in) * 12), bottom - (k + 1) * LINE_H, w, p);
        }
    }

    /** Chat open: full history in a panel, scrollable; click to open the console. */
    private static void drawFocused(GuiGraphicsExtractor g, Font font, long now, int x, int bottom, int w, int sh) {
        int h = Math.min(180, sh / 2);
        int y = bottom - h;
        visible = true;
        bx = x;
        by = y;
        bw = w;
        bh = h;

        float t = Math.min(1f, (now - chatSince) / 150f);
        float p = 1f - (1f - t) * (1f - t) * (1f - t);

        Ui.roundRect(g, x, y, x + w, y + h, 4, alpha(0x70101014, p));
        if (p >= 0.08f) {
            g.text(font, Component.literal("Denizen errors").withStyle(ChatFormatting.BOLD),
                    x + PAD + 2, y + 4, alpha(Ui.RED_SOFT, p), false);
            String hint = "Click to open console  \u00b7  F4";
            g.text(font, hint, x + w - PAD - font.width(hint), y + 4, alpha(Ui.MUTED, p), false);
        }
        g.fill(x + 4, y + 15, x + w - 4, y + 16, alpha(Ui.DIVIDER, p));

        int areaTop = y + 18;
        int areaBottom = y + h - 3;
        int maxLines = Math.max(1, (areaBottom - areaTop) / LINE_H);
        maxScroll = Math.max(0, lines.size() - maxLines);
        scrollLines = Mth.clamp(scrollLines, 0, maxScroll);

        for (int k = 0; k < maxLines; k++) {
            int idx = lines.size() - 1 - scrollLines - k;
            if (idx < 0) break;
            drawLine(g, font, lines.get(idx), x, areaBottom - (k + 1) * LINE_H, w - 6, p);
        }
        if (maxScroll > 0) {
            Ui.scrollbar(g, x + w - 6, areaTop, areaBottom, lines.size(), maxLines, maxScroll - scrollLines);
        }
    }

    private static void drawLine(GuiGraphicsExtractor g, Font font, HLine l, int x, int y, int w, float p) {
        g.fill(x, y, x + w, y + LINE_H, alpha(0xB00E0E12, p));
        if (l.first()) {
            g.fill(x, y, x + 2, y + LINE_H, alpha(Ui.RED, p));
        }
        g.text(font, l.text(), x + PAD, y + 1, alpha(0xFFFFFFFF, p), false);
    }

    /** Small marker that stays after the lines have faded while errors are still unread. */
    private static void drawBadge(GuiGraphicsExtractor g, Font font, int bottom, int sw) {
        String s = "\u26a0 " + LogStore.unread();
        int bwid = font.width(s) + 14;
        int x = sw - 4 - bwid;
        int y = bottom - 16;
        Ui.roundRect(g, x, y, x + bwid, y + 16, 4, 0xEE111114);
        g.fill(x + bwid - 2, y + 3, x + bwid, y + 13, Ui.RED);
        g.text(font, s, x + 7, y + 4, Ui.RED_SOFT, false);
    }
}
