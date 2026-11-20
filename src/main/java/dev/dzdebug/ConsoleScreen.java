package dev.dzdebug;

import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.ChatFormatting;
import net.minecraft.util.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Console UI: script sidebar (click to filter), smooth-scrolling console with entry separators and a flash on new
 * lines, toolbar (Pause / Dedup / Copy / Clear), footer (status, Server log, Reload, Close) and a Settings button.
 * Tab 0 = live errors, tab 1 = errors stored while nobody was online.
 * Visuals are drawn manually; vanilla Buttons are only invisible hit areas (earlier-added widgets win clicks).
 */
public class ConsoleScreen extends Screen {
    private static boolean dedup = true;

    private record VLine(FormattedCharSequence text, String key, boolean first, long time) {}
    private record Side(String name, int count) {}

    private static final class Btn {
        final String id;
        final Supplier<String> label;
        final int style; // 0 ghost, 1 primary, 2 danger
        final int x, y, w, h;

        Btn(String id, Supplier<String> label, int style, int x, int y, int w, int h) {
            this.id = id;
            this.label = label;
            this.style = style;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }
    }

    private static final int SIDE_ROW = 18;
    private static final long NEW_FLASH_MS = 1400;

    private final Screen parent;
    private final long openedAt = System.currentTimeMillis();

    private int tab = 0;
    private boolean paused = false;
    private List<LogStore.Row> frozen = List.of();
    private long frozenAdded = 0;
    private final Set<String> selected = new HashSet<>();
    private String scriptFilter = null;

    // layout cache
    private List<VLine> vlines = List.of();
    private List<Side> side = List.of();
    private String stats = "";
    private int lastTotal = 0;
    private int layoutVersion = -1, layoutW = -1, layoutTab = -1;
    private boolean layoutDedup;
    private boolean layoutDirty = true;

    // scrolling (in lines, measured from the bottom)
    private float scrollTarget = 0f;
    private float scrollPos = 0f;
    private long lastNano = 0;
    private int sideScroll = 0;
    private int sideVisible = 1;

    // hit-testing data from the last frame
    private boolean hitOverflow;
    private int hitTotal, hitVpTop, hitVpH;

    private int seenVersion = -1;
    private String builtLogUrl = null;
    private int mouseX, mouseY;
    private String flashId = "";
    private long flashUntil = 0;

    private final List<Btn> btns = new ArrayList<>();

    // layout
    private int px0, py0, px1, py1, tabY, bodyY, bodyBottom, footerY;
    private int sideX, sideW, consoleX, consoleY, consoleW, consoleH, lh;
    private int tab1W, tab2W, jumpX, jumpY, jumpW, footerStatusRight;

    public ConsoleScreen(Screen parent) {
        super(Component.literal("Denizen Debug Console"));
        this.parent = parent;
    }

    // ------------------------------------------------------------------ setup

    @Override
    protected void init() {
        LogStore.markRead();
        seenVersion = LogStore.version();
        builtLogUrl = LogStore.logUrl();

        px0 = 8;
        py0 = 8;
        px1 = this.width - 8;
        py1 = this.height - 8;
        tabY = py0 + 34;
        bodyY = tabY + 24;
        footerY = py1 - 26;
        bodyBottom = footerY - 6;

        sideW = this.width >= 380 ? Mth.clamp(this.width / 5, 96, 140) : 0;
        sideX = px0 + 8;
        consoleX = sideX + (sideW > 0 ? sideW + 6 : 0);
        consoleW = Math.max(100, px1 - 8 - consoleX);
        consoleY = bodyY;
        consoleH = Math.max(40, bodyBottom - bodyY);
        lh = this.font.lineHeight + 2;

        int unseenN = LogStore.unseen().size();
        tab1W = this.font.width("Console") + 24;
        tab2W = this.font.width("Unseen") + 24 + (unseenN > 0 ? this.font.width(String.valueOf(unseenN)) + 16 : 0);

        jumpW = this.font.width("Latest \u2193") + 14;
        jumpX = consoleX + consoleW - jumpW - 14;
        jumpY = consoleY + consoleH - 22;

        buildButtons();

        // Order matters: the first widget under the cursor wins the click.
        this.addWidget(hit(jumpX, jumpY, jumpW, 16, this::clickJump));
        this.addWidget(hit(px0 + 8, tabY, tab1W, 20, () -> switchTab(0)));
        this.addWidget(hit(px0 + 8 + tab1W + 4, tabY, tab2W, 20, () -> switchTab(1)));
        for (Btn b : btns) {
            final String id = b.id;
            this.addWidget(hit(b.x, b.y, b.w, b.h, () -> press(id)));
        }
        if (sideW > 0) {
            this.addWidget(hit(sideX, bodyY + 20, sideW, bodyBottom - bodyY - 20, this::clickSidebar));
        }
        this.addWidget(hit(consoleX, consoleY, consoleW - 8, consoleH, this::clickConsole));

        layoutDirty = true;
    }

    private Button hit(int x, int y, int w, int h, Runnable action) {
        return Button.builder(Component.empty(), b -> action.run()).bounds(x, y, w, h).build();
    }

    private void buildButtons() {
        btns.clear();

        // toolbar (right-aligned on the tab row, h = 18)
        int x = px1 - 8;
        if (tab == 0) {
            x = addR(x, tabY + 1, 18, 44, "clear", () -> "Clear", 0);
            x = addR(x, tabY + 1, 18, 66, "copy", this::copyLabel, 1);
            x = addR(x, tabY + 1, 18, 76, "dedup", () -> "Dedup: " + (dedup ? "ON" : "OFF"), 0);
            addR(x, tabY + 1, 18, 84, "pause", () -> paused
                    ? "Resume" + (newWhilePaused() > 0 ? " +" + newWhilePaused() : "")
                    : "Pause", paused ? 1 : 0);
        } else {
            x = addR(x, tabY + 1, 18, 72, "clearlog", () -> lab("clearlog", "Clear log", "Sent!"), 2);
            addR(x, tabY + 1, 18, 66, "copy", this::copyLabel, 1);
        }

        // footer (right-aligned, h = 20)
        int fx = px1 - 8;
        fx = addR(fx, footerY, 20, 56, "close", () -> "Close", 0);
        if (tab == 0) {
            fx = addR(fx, footerY, 20, 66, "reload", () -> lab("reload", "Reload", "Sent!"), 2);
        }
        if (LogStore.logUrl() != null) {
            fx = addR(fx, footerY, 20, 74, "openlog", () -> lab("openlog", "Open log", "Opened"), 1);
        }
        fx = addR(fx, footerY, 20, 88, "serverlog", () -> lab("serverlog", "Server log", "Requested"), 0);
        footerStatusRight = fx;

        // header
        add(px1 - 12 - 92 - 64, py0 + 6, 16, 64, "settings", () -> "Settings", 0);
    }

    private int addR(int right, int y, int h, int w, String id, Supplier<String> label, int style) {
        btns.add(new Btn(id, label, style, right - w, y, w, h));
        return right - w - 4;
    }

    private void add(int x, int y, int h, int w, String id, Supplier<String> label, int style) {
        btns.add(new Btn(id, label, style, x, y, w, h));
    }

    private void press(String id) {
        switch (id) {
            case "pause" -> togglePause();
            case "dedup" -> toggleDedup();
            case "copy" -> copy();
            case "clear" -> clearLive();
            case "clearlog" -> clearUnseenLog();
            case "reload" -> reload();
            case "serverlog" -> downloadServerLog();
            case "openlog" -> openLog();
            case "settings" -> this.minecraft.gui.setScreen(new ConfigScreen(this));
            case "close" -> this.onClose();
            default -> { }
        }
    }

    private String copyLabel() {
        return lab("copy", selected.isEmpty() ? "Copy all" : "Copy (" + selected.size() + ")", "Copied!");
    }

    private String lab(String id, String normal, String flashed) {
        return id.equals(flashId) && System.currentTimeMillis() < flashUntil ? flashed : normal;
    }

    private void flash(String id) {
        flashId = id;
        flashUntil = System.currentTimeMillis() + 1500;
    }

    // ------------------------------------------------------------------ actions

    private void switchTab(int t) {
        if (tab == t) return;
        tab = t;
        selected.clear();
        scriptFilter = null;
        sideScroll = 0;
        scrollTarget = 0;
        scrollPos = 0;
        layoutDirty = true;
        this.rebuildWidgets();
    }

    private void togglePause() {
        if (!paused) {
            frozen = new ArrayList<>(LogStore.live());
            frozenAdded = LogStore.totalAdded();
            paused = true;
        } else {
            paused = false;
            frozen = List.of();
            scrollTarget = 0;
        }
        layoutDirty = true;
    }

    private long newWhilePaused() {
        return paused ? LogStore.totalAdded() - frozenAdded : 0;
    }

    private void toggleDedup() {
        dedup = !dedup;
        selected.clear();
        layoutDirty = true;
    }

    private void clearLive() {
        LogStore.clearLive();
        frozen = List.of();
        paused = false;
        selected.clear();
        scriptFilter = null;
        scrollTarget = 0;
        scrollPos = 0;
        layoutDirty = true;
    }

    private void clearUnseenLog() {
        runCommand("cleardenizenlogs");
        LogStore.clearUnseen();
        selected.clear();
        scriptFilter = null;
        layoutDirty = true;
        flash("clearlog");
    }

    private void reload() {
        runCommand("ex reload");
        flash("reload");
    }

    private void downloadServerLog() {
        runCommand("serverlastestlog");
        LogStore.markLogRequested(); // the server uploads the log and sends "Log uploaded: <url>" after a while
        LogStore.setStatus("Requesting server log\u2026 waiting for the server", 300_000);
        flash("serverlog");
    }

    private void openLog() {
        String url = LogStore.logUrl();
        if (url == null) return;
        this.minecraft.keyboardHandler.setClipboard(url); // also copy, in case the browser doesn't open
ConfirmLinkScreen.confirmLinkNow(this, URI.create(url));        flash("openlog");
    }

    private void runCommand(String cmd) {
        Minecraft mc = this.minecraft;
        if (mc != null && mc.player != null) {
            mc.player.connection.sendCommand(cmd);
        }
    }

    private void copy() {
        List<LogStore.Row> rows = sourceRows();
        if (!selected.isEmpty()) {
            rows = rows.stream().filter(r -> selected.contains(key(r))).collect(Collectors.toList());
        }
        if (rows.isEmpty()) return;
        this.minecraft.keyboardHandler.setClipboard(
                rows.stream().map(LogStore.Row::asText).collect(Collectors.joining("\n")));
        flash("copy");
    }

    private void clickJump() {
        if (scrollTarget > 0.5f) {
            scrollTarget = 0;
        } else {
            clickConsole();
        }
    }

    private void clickConsole() {
        int i = lineAt(mouseY);
        if (i < 0 || i >= vlines.size()) return;
        String k = vlines.get(i).key();
        if (!selected.remove(k)) {
            selected.add(k);
        }
    }

    private void clickSidebar() {
        int idx = sideScroll + (mouseY - (bodyY + 20)) / SIDE_ROW;
        if (idx < 0 || idx >= side.size()) return;
        scriptFilter = side.get(idx).name();
        selected.clear();
        scrollTarget = 0;
        scrollPos = 0;
        layoutDirty = true;
    }

    // ------------------------------------------------------------------ data / layout

    private List<LogStore.Row> baseRows() {
        if (tab == 1) return LogStore.unseen();
        List<LogStore.Row> base = paused ? frozen : LogStore.live();
        return dedup ? LogStore.dedup(base) : base;
    }

    private List<LogStore.Row> sourceRows() {
        List<LogStore.Row> b = baseRows();
        if (scriptFilter == null) return b;
        return b.stream().filter(r -> r.script.equals(scriptFilter)).collect(Collectors.toList());
    }

    private String key(LogStore.Row r) {
        return (tab == 0 && dedup) ? r.contentKey() : "#" + r.id;
    }

    private void ensureLayout() {
        boolean frozenView = tab == 0 && paused;
        boolean stale = layoutDirty || layoutW != consoleW || layoutTab != tab || layoutDedup != dedup
                || (!frozenView && layoutVersion != LogStore.version());
        if (!stale) return;

        List<LogStore.Row> base = baseRows();

        // sidebar + stats from the unfiltered rows
        Map<String, Integer> counts = new LinkedHashMap<>();
        Set<String> unique = new HashSet<>();
        int total = 0;
        for (LogStore.Row r : base) {
            counts.merge(r.script, r.count, Integer::sum);
            unique.add(r.contentKey());
            total += r.count;
        }
        if (scriptFilter != null && !counts.containsKey(scriptFilter)) {
            scriptFilter = null;
        }
        List<Side> sl = new ArrayList<>();
        sl.add(new Side(null, total));
        counts.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .forEach(e -> sl.add(new Side(e.getKey(), e.getValue())));
        side = sl;
        stats = total + (total == 1 ? " error" : " errors") + "  \u00b7  " + unique.size() + " unique  \u00b7  "
                + counts.size() + (counts.size() == 1 ? " script" : " scripts");

        // console lines
        int w = consoleW - 24;
        List<VLine> out = new ArrayList<>();
        for (LogStore.Row r : sourceRows()) {
            String k = key(r);
            String[] paras = r.message.split("\\R", -1);

            MutableComponent head = Component.literal("[" + r.timeText() + "] ").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(r.script + ":" + r.line).withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
            if (r.count > 1) {
                head.append(Component.literal(" (" + r.count + ")").withStyle(ChatFormatting.YELLOW));
            }
            head.append(Component.literal("  " + paras[0]).withStyle(ChatFormatting.WHITE));

            boolean first = true;
            for (FormattedCharSequence s : this.font.split(head, w)) {
                out.add(new VLine(s, k, first, r.time));
                first = false;
            }
            for (int i = 1; i < paras.length; i++) {
                for (FormattedCharSequence s : this.font.split(
                        Component.literal("    " + paras[i]).withStyle(ChatFormatting.WHITE), w)) {
                    out.add(new VLine(s, k, false, r.time));
                }
            }
        }

        // new lines slide in: shift the view by the added lines, then let it ease back to the bottom
        if (!layoutDirty && out.size() > lastTotal) {
            float added = out.size() - lastTotal;
            scrollPos += added;
            if (scrollTarget > 0) scrollTarget += added;
        }
        vlines = out;
        lastTotal = out.size();
        layoutW = consoleW;
        layoutTab = tab;
        layoutDedup = dedup;
        layoutVersion = LogStore.version();
        layoutDirty = false;
    }

    @Override
    public void tick() {
        super.tick();
        if (LogStore.version() != seenVersion) {
            seenVersion = LogStore.version();
            LogStore.markRead();
        }
        if (!Objects.equals(builtLogUrl, LogStore.logUrl())) {
            this.rebuildWidgets(); // the "Open log" button appears when the link arrives
        }
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        super.extractRenderState(g, mx, my, delta); // vanilla background only; our widgets aren't renderable
        mouseX = mx;
        mouseY = my;
        long now = System.currentTimeMillis();

        float open = Math.min(1f, (now - openedAt) / 180f);
        g.fill(0, 0, this.width, this.height, ((int) (0x66 * open)) << 24);

        Ui.roundRect(g, px0, py0, px1, py1, 5, Ui.BG_PANEL);
        drawHeader(g);
        drawTabs(g);
        ensureLayout();
        if (sideW > 0) drawSidebar(g);
        drawConsole(g, now);
        drawButtons(g);
        drawFooterStatus(g, now);
    }

    private void drawHeader(GuiGraphicsExtractor g) {
        // slightly lighter header band
        Ui.roundRect(g, px0, py0, px1, py0 + 28, 5, 0xFF16161B);
        g.fill(px0, py0 + 14, px1, py0 + 28, 0xFF16161B);

        Ui.roundRect(g, px0 + 12, py0 + 11, px0 + 17, py0 + 16, 1, Ui.RED);
        g.text(this.font, Component.literal("Denizen Debug").withStyle(ChatFormatting.BOLD),
                px0 + 23, py0 + 10, Ui.TEXT, false);

        String chip;
        int bg;
        if (tab == 0) {
            if (paused) {
                chip = "PAUSED" + (newWhilePaused() > 0 ? "  +" + newWhilePaused() : "");
                bg = Ui.AMBER;
            } else {
                chip = "LIVE";
                bg = Ui.RED_DARK;
            }
        } else {
            chip = LogStore.unseen().size() + " stored";
            bg = Ui.BG_ROW;
        }
        int cw = this.font.width(chip) + 10;
        Ui.pill(g, this.font, chip, px1 - 12 - cw, py0 + 7, bg, Ui.TEXT);
        g.fill(px0, py0 + 28, px1, py0 + 29, Ui.RED_DARK);
    }

    private void drawTabs(GuiGraphicsExtractor g) {
        int x1 = px0 + 8;
        int x2 = x1 + tab1W + 4;

        boolean h0 = Ui.inside(mouseX, mouseY, x1, tabY, tab1W, 20);
        g.text(this.font, "Console", x1 + 12, tabY + 6, (tab == 0 || h0) ? Ui.TEXT : Ui.MUTED, false);
        if (tab == 0) g.fill(x1, tabY + 18, x1 + tab1W, tabY + 20, Ui.RED);

        boolean h1 = Ui.inside(mouseX, mouseY, x2, tabY, tab2W, 20);
        g.text(this.font, "Unseen", x2 + 12, tabY + 6, (tab == 1 || h1) ? Ui.TEXT : Ui.MUTED, false);
        int n = LogStore.unseen().size();
        if (n > 0) {
            Ui.pill(g, this.font, String.valueOf(n), x2 + 12 + this.font.width("Unseen") + 6, tabY + 3, Ui.RED, Ui.TEXT);
        }
        if (tab == 1) g.fill(x2, tabY + 18, x2 + tab2W, tabY + 20, Ui.RED);
    }

    private void drawSidebar(GuiGraphicsExtractor g) {
        Ui.roundRect(g, sideX, bodyY, sideX + sideW, bodyBottom, 4, 0xFF14141A);
        g.text(this.font, "SCRIPTS", sideX + 9, bodyY + 7, Ui.MUTED, false);

        int y0 = bodyY + 20;
        sideVisible = Math.max(1, (bodyBottom - y0 - 4) / SIDE_ROW);
        sideScroll = Mth.clamp(sideScroll, 0, Math.max(0, side.size() - sideVisible));

        for (int i = 0; i < sideVisible && sideScroll + i < side.size(); i++) {
            Side s = side.get(sideScroll + i);
            int y = y0 + i * SIDE_ROW;
            boolean sel = Objects.equals(s.name(), scriptFilter);
            boolean hov = Ui.inside(mouseX, mouseY, sideX + 3, y, sideW - 6, SIDE_ROW - 1);

            if (sel) {
                Ui.roundRect(g, sideX + 3, y, sideX + sideW - 3, y + SIDE_ROW - 1, 3, Ui.SELECT);
                g.fill(sideX + 3, y + 3, sideX + 5, y + SIDE_ROW - 4, Ui.RED);
            } else if (hov) {
                Ui.roundRect(g, sideX + 3, y, sideX + sideW - 3, y + SIDE_ROW - 1, 3, Ui.ROW_HOVER);
            }

            String cnt = String.valueOf(s.count());
            int cw = this.font.width(cnt);
            String name = s.name() == null ? "All errors" : s.name();
            g.text(this.font, Ui.fit(this.font, name, sideW - 26 - cw), sideX + 10, y + 5,
                    sel ? Ui.TEXT : (hov ? Ui.TEXT : Ui.MUTED), false);
            g.text(this.font, cnt, sideX + sideW - 8 - cw, y + 5, sel ? Ui.RED_SOFT : Ui.MUTED, false);
        }
    }

    private int lineAt(int my) {
        if (hitTotal == 0) return -1;
        int i = hitOverflow
                ? (int) Math.floor(hitTotal - scrollPos + (my - hitVpTop - hitVpH) / (float) lh)
                : Math.floorDiv(my - hitVpTop, lh);
        return (i >= 0 && i < hitTotal) ? i : -1;
    }

    private int yOf(int i) {
        return hitOverflow
                ? hitVpTop + hitVpH - Math.round((hitTotal - i - scrollPos) * lh)
                : hitVpTop + i * lh;
    }

    private void drawConsole(GuiGraphicsExtractor g, long now) {
        Ui.roundRect(g, consoleX, consoleY, consoleX + consoleW, consoleY + consoleH, 4, Ui.CONSOLE);

        int vpTop = consoleY + 4;
        int vpH = consoleH - 8;
        int total = vlines.size();
        boolean overflow = total * lh > vpH;
        float maxS = overflow ? total - vpH / (float) lh : 0f;

        scrollTarget = Mth.clamp(scrollTarget, 0f, maxS);
        long nn = System.nanoTime();
        float dt = lastNano == 0 ? 0.016f : Mth.clamp((nn - lastNano) / 1e9f, 0.001f, 0.1f);
        lastNano = nn;
        scrollPos += (scrollTarget - scrollPos) * (1f - (float) Math.exp(-dt * 16f));
        if (Math.abs(scrollTarget - scrollPos) < 0.01f) scrollPos = scrollTarget;
        scrollPos = Mth.clamp(scrollPos, 0f, maxS);

        hitOverflow = overflow;
        hitTotal = total;
        hitVpTop = vpTop;
        hitVpH = vpH;

        if (total == 0) {
            String a = tab == 0 ? (paused ? "Paused - nothing captured yet" : "Waiting for errors\u2026")
                    : "No unseen errors";
            String b = tab == 0 ? "Script errors from the server will pile up here."
                    : "Errors that happen while nobody is online are listed here.";
            g.text(this.font, a, consoleX + (consoleW - this.font.width(a)) / 2, consoleY + consoleH / 2 - 10, Ui.TEXT, false);
            g.text(this.font, b, consoleX + (consoleW - this.font.width(b)) / 2, consoleY + consoleH / 2 + 3, Ui.MUTED, false);
            return;
        }

        String hoverKey = null;
        if (Ui.inside(mouseX, mouseY, consoleX, consoleY, consoleW - 8, consoleH)) {
            int hl = lineAt(mouseY);
            if (hl >= 0) hoverKey = vlines.get(hl).key();
        }

        int iStart = overflow ? Math.max(0, (int) Math.floor(total - scrollPos - vpH / (float) lh) - 1) : 0;
        int iEnd = overflow ? Math.min(total - 1, (int) Math.ceil(total - scrollPos)) : total - 1;

        g.enableScissor(consoleX + 2, consoleY + 2, consoleX + consoleW - 2, consoleY + consoleH - 2);
        for (int i = iStart; i <= iEnd; i++) {
            VLine l = vlines.get(i);
            int y = yOf(i);

            // flash on fresh lines (live tab)
            if (tab == 0) {
                long age = now - l.time();
                if (age >= 0 && age < NEW_FLASH_MS) {
                    int a = (int) (0x48 * (1f - age / (float) NEW_FLASH_MS));
                    g.fill(consoleX + 3, y, consoleX + consoleW - 9, y + lh, (a << 24) | 0xE5383B);
                }
            }
            // selection / hover
            if (selected.contains(l.key())) {
                g.fill(consoleX + 3, y, consoleX + consoleW - 9, y + lh, Ui.SELECT);
                g.fill(consoleX + 3, y, consoleX + 5, y + lh, Ui.RED);
            } else if (l.key().equals(hoverKey)) {
                g.fill(consoleX + 3, y, consoleX + consoleW - 9, y + lh, Ui.HOVER_LINE);
            }
            // separator between entries
            if (l.first() && i > 0) {
                g.fill(consoleX + 8, y, consoleX + consoleW - 12, y + 1, 0x1CFFFFFF);
            }
            g.text(this.font, l.text(), consoleX + 10, y + 1, 0xFFFFFFFF, false);
        }
        g.disableScissor();

        if (overflow) {
            int visibleLines = vpH / lh;
            Ui.scrollbar(g, consoleX + consoleW - 7, vpTop, vpTop + vpH, total, visibleLines,
                    Math.round(maxS - scrollPos));
        }

        if (scrollTarget > 0.5f) {
            boolean hov = Ui.inside(mouseX, mouseY, jumpX, jumpY, jumpW, 16);
            Ui.roundRect(g, jumpX, jumpY, jumpX + jumpW, jumpY + 16, 4, hov ? Ui.RED_HOVER : Ui.RED);
            g.text(this.font, "Latest \u2193", jumpX + 7, jumpY + 4, Ui.TEXT, false);
        }
    }

    private void drawButtons(GuiGraphicsExtractor g) {
        for (Btn b : btns) {
            boolean hov = Ui.inside(mouseX, mouseY, b.x, b.y, b.w, b.h);
            int bg;
            int fg = Ui.TEXT;
            switch (b.style) {
                case 1 -> bg = hov ? Ui.RED_HOVER : Ui.RED;
                case 2 -> {
                    bg = hov ? 0xFF3D1A1D : 0xFF2A1618;
                    fg = Ui.RED_SOFT;
                }
                default -> bg = hov ? Ui.ROW_HOVER : Ui.BG_ROW;
            }
            Ui.roundRect(g, b.x, b.y, b.x + b.w, b.y + b.h, 3, bg);
            String label = b.label.get();
            g.text(this.font, label, b.x + (b.w - this.font.width(label)) / 2, b.y + (b.h - 8) / 2, fg, false);
        }
    }

    private void drawFooterStatus(GuiGraphicsExtractor g, long now) {
        String st = LogStore.status();
        boolean showStatus = st != null && now - LogStore.statusAt() < LogStore.statusTtl();
        String text;
        int color;
        if (showStatus) {
            text = st;
            color = st.contains("failed") ? Ui.RED_SOFT : Ui.TEXT;
        } else {
            text = stats + (selected.isEmpty() ? "" : "  \u00b7  " + selected.size() + " selected");
            color = Ui.MUTED;
        }
        int x = px0 + 10;
        int maxW = footerStatusRight - x - 6;
        if (maxW > 20) {
            g.text(this.font, Ui.fit(this.font, text, maxW), x, footerY + 6, color, false);
        }
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (sideW > 0 && mouseX < consoleX) {
            sideScroll = Math.max(0, sideScroll + (scrollY > 0 ? -1 : 1));
        } else {
            scrollTarget = Math.max(0f, scrollTarget + (scrollY > 0 ? 3f : -3f));
        }
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(parent);
    }
}
