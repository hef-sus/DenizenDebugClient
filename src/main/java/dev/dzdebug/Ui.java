package dev.dzdebug;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Shared theme colors (ARGB) and tiny draw helpers. */
final class Ui {
    static final int DIM          = 0x66000000;
    static final int BG_PANEL     = 0xF0111114;
    static final int BG_ROW       = 0xFF1F1F25;
    static final int ROW_HOVER    = 0xFF2A2A32;
    static final int CONSOLE      = 0xFF0B0B0E;
    static final int RED          = 0xFFE5383B;
    static final int RED_HOVER    = 0xFFFF5457;
    static final int RED_DARK     = 0xFF8E1F22;
    static final int RED_SOFT     = 0xFFFF7A7D;
    static final int SELECT       = 0xFF3A1519;
    static final int HOVER_LINE   = 0x14FFFFFF;
    static final int TEXT         = 0xFFF2F2F2;
    static final int MUTED        = 0xFF9A9AA3;
    static final int DIVIDER      = 0xFF2C2C33;
    static final int TRACK        = 0xFF26262C;
    static final int AMBER        = 0xFF7A5C12;

    private Ui() {}

    /** Filled rectangle with pixel-approximated rounded corners. */
    static void roundRect(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int r, int color) {
        r = Math.min(r, Math.min((x2 - x1) / 2, (y2 - y1) / 2));
        if (r <= 0) {
            g.fill(x1, y1, x2, y2, color);
            return;
        }
        g.fill(x1, y1 + r, x2, y2 - r, color);
        for (int i = 0; i < r; i++) {
            double dy = r - i - 0.5;
            int inset = r - (int) Math.round(Math.sqrt(r * r - dy * dy));
            g.fill(x1 + inset, y1 + i, x2 - inset, y1 + i + 1, color);
            g.fill(x1 + inset, y2 - i - 1, x2 - inset, y2 - i, color);
        }
    }

    /** Small rounded label; returns its width. */
    static int pill(GuiGraphicsExtractor g, Font font, String text, int x, int y, int bg, int fg) {
        int w = font.width(text) + 10;
        roundRect(g, x, y, x + w, y + 13, 3, bg);
        g.text(font, text, x + 5, y + 3, fg, false);
        return w;
    }

    static void scrollbar(GuiGraphicsExtractor g, int x, int y1, int y2, int total, int visible, int offsetFromTop) {
        if (total <= visible || y2 <= y1) return;
        g.fill(x, y1, x + 3, y2, TRACK);
        int h = Math.max(14, (y2 - y1) * visible / total);
        int ty = y1 + (int) ((long) (y2 - y1 - h) * offsetFromTop / (total - visible));
        roundRect(g, x, ty, x + 3, ty + h, 1, RED);
    }

    static String fit(Font font, String s, int maxW) {
        if (font.width(s) <= maxW) return s;
        while (s.length() > 1 && font.width(s + "\u2026") > maxW) {
            s = s.substring(0, s.length() - 1);
        }
        return s + "\u2026";
    }

    static boolean inside(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }
}
