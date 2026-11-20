package dev.dzdebug;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.Supplier;

/** Settings (Mod Menu "configure" button, or the Settings button in the console). Same look as the console. */
public class ConfigScreen extends Screen {
    private static final int ROW_H = 28;
    private static final float[] VOLUMES = {0.25f, 0.5f, 0.75f, 1.0f};

    private record Row(String label, String hint, Supplier<String> value, Runnable action, boolean primary) {}

    private final Screen parent;
    private List<Row> rows = List.of();
    private int px, py, pw, ph;
    private int mouseX, mouseY;

    public ConfigScreen(Screen parent) {
        super(Component.literal("Denizen Debug Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        DzConfig cfg = DzConfig.get();
        rows = List.of(
                new Row("Sound alerts", "Play a sound when a script error arrives",
                        () -> cfg.soundEnabled ? "ON" : "OFF",
                        () -> { cfg.soundEnabled = !cfg.soundEnabled; DzConfig.save(); }, false),
                new Row("Alert sound", "Click to cycle",
                        () -> Sounds.name(cfg.soundIndex),
                        () -> { cfg.soundIndex = (cfg.soundIndex + 1) % Sounds.count(); DzConfig.save(); Sounds.play(true); }, false),
                new Row("Volume", "Click to cycle",
                        () -> Math.round(cfg.volume * 100) + "%",
                        () -> { cfg.volume = nextVolume(cfg.volume); DzConfig.save(); Sounds.play(true); }, false),
                new Row("Sound for repeated errors", "Off = only new errors make a sound",
                        () -> cfg.soundOnRepeats ? "ON" : "OFF",
                        () -> { cfg.soundOnRepeats = !cfg.soundOnRepeats; DzConfig.save(); }, false),
                new Row("Test sound", "",
                        () -> "Play",
                        () -> Sounds.play(true), true));

        pw = Math.min(340, this.width - 40);
        ph = 50 + rows.size() * ROW_H + 40;
        px = (this.width - pw) / 2;
        py = Math.max(8, (this.height - ph) / 2);

        for (int i = 0; i < rows.size(); i++) {
            final Row r = rows.get(i);
            this.addWidget(hit(px + 10, py + 44 + i * ROW_H, pw - 20, ROW_H - 4, r.action()));
        }
        this.addWidget(hit(px + pw - 10 - 80, py + ph - 32, 80, 22, this::onClose));
    }

    private static float nextVolume(float cur) {
        for (float v : VOLUMES) {
            if (v > cur + 0.01f) return v;
        }
        return VOLUMES[0];
    }

    private Button hit(int x, int y, int w, int h, Runnable action) {
        return Button.builder(Component.empty(), b -> action.run()).bounds(x, y, w, h).build();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        super.extractRenderState(g, mx, my, delta);
        mouseX = mx;
        mouseY = my;
        g.fill(0, 0, this.width, this.height, Ui.DIM);

        Ui.roundRect(g, px, py, px + pw, py + ph, 5, Ui.BG_PANEL);
        Ui.roundRect(g, px + 12, py + 13, px + 17, py + 18, 1, Ui.RED);
        g.text(this.font, Component.literal("Denizen Debug Settings").withStyle(ChatFormatting.BOLD),
                px + 23, py + 12, Ui.TEXT, false);
        g.fill(px + 8, py + 30, px + pw - 8, py + 31, Ui.DIVIDER);

        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            int y = py + 44 + i * ROW_H;
            int w = pw - 20;
            boolean hov = Ui.inside(mouseX, mouseY, px + 10, y, w, ROW_H - 4);
            Ui.roundRect(g, px + 10, y, px + 10 + w, y + ROW_H - 4, 4, hov ? Ui.ROW_HOVER : Ui.BG_ROW);

            g.text(this.font, r.label(), px + 18, y + (r.hint().isEmpty() ? 8 : 4), Ui.TEXT, false);
            if (!r.hint().isEmpty()) {
                g.text(this.font, Ui.fit(this.font, r.hint(), w - 110), px + 18, y + 14, Ui.MUTED, false);
            }

            String v = r.value().get();
            int bg = r.primary() ? (hov ? Ui.RED_HOVER : Ui.RED)
                    : "ON".equals(v) ? Ui.RED : (hov ? 0xFF3A3A44 : 0xFF2C2C34);
            int vw = this.font.width(v) + 16;
            Ui.roundRect(g, px + 10 + w - 8 - vw, y + 4, px + 10 + w - 8, y + ROW_H - 8, 3, bg);
            g.text(this.font, v, px + 10 + w - 8 - vw + 8, y + 8, Ui.TEXT, false);
        }

        boolean dh = Ui.inside(mouseX, mouseY, px + pw - 90, py + ph - 32, 80, 22);
        Ui.roundRect(g, px + pw - 90, py + ph - 32, px + pw - 10, py + ph - 10, 3, dh ? Ui.RED_HOVER : Ui.RED);
        g.text(this.font, "Done", px + pw - 50 - this.font.width("Done") / 2, py + ph - 25, Ui.TEXT, false);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        DzConfig.save();
        this.minecraft.gui.setScreen(parent);
    }
}
