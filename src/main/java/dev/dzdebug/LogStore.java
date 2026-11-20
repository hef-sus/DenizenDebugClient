package dev.dzdebug;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Client-side log. LIVE = every error packet received this session (chronological, like a console).
 * UNSEEN = errors the server stored while nobody with the mod was online (sent on dzdebug:unseen).
 * All access happens on the client main thread.
 */
public final class LogStore {
    public static final class Row {
        public final long id;
        public final long time;
        public final String script;
        public final String line;
        public final String message;
        public final int count;
        public final boolean showDate;

        public Row(long id, long time, String script, String line, String message, int count, boolean showDate) {
            this.id = id;
            this.time = time;
            this.script = script;
            this.line = line;
            this.message = message;
            this.count = count;
            this.showDate = showDate;
        }

        public String contentKey() {
            return script + '\u0000' + line + '\u0000' + message;
        }

        public String timeText() {
            return (showDate ? DATE_TIME : TIME).format(Instant.ofEpochMilli(time));
        }

        public String asText() {
            return "[" + timeText() + "] " + script + ":" + line + (count > 1 ? " (" + count + ")" : "")
                    + "\n" + message;
        }
    }

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private static final int MAX_LIVE = 2000;
    private static final List<Row> LIVE = new ArrayList<>();
    private static final List<Row> UNSEEN = new ArrayList<>();
    private static final Map<String, Long> LOGGED = new HashMap<>();

    private static long nextId = 1;
    private static long totalAdded = 0;
    private static int version = 0;
    private static int unread = 0;
    private static long lastAt = 0;
    private static Row last = null;

    private LogStore() {}

    // ------------------------------------------------------------ live

    /** @return true if this error was not seen in the last minute (used for the alert sound). */
    public static boolean addLive(String script, String line, String message, int count) {
        Row r = new Row(nextId++, System.currentTimeMillis(), script, line, message, Math.max(1, count), false);
        LIVE.add(r);
        if (LIVE.size() > MAX_LIVE) {
            LIVE.subList(0, LIVE.size() - MAX_LIVE).clear();
        }
        totalAdded++;
        unread++;
        lastAt = r.time;
        last = r;
        version++;
        return writeLog(r);
    }

    public static List<Row> live() {
        return Collections.unmodifiableList(LIVE);
    }

    public static void clearLive() {
        LIVE.clear();
        unread = 0;
        last = null;
        version++;
    }

    /** One row per unique error; the row sits at the position of its latest occurrence and carries the total count. */
    public static List<Row> dedup(List<Row> src) {
        LinkedHashMap<String, Row> map = new LinkedHashMap<>();
        for (Row r : src) {
            String k = r.contentKey();
            Row prev = map.remove(k);
            map.put(k, prev == null ? r
                    : new Row(r.id, r.time, r.script, r.line, r.message, prev.count + r.count, r.showDate));
        }
        return new ArrayList<>(map.values());
    }

    // ------------------------------------------------------------ unseen

    public static List<Row> unseen() {
        return Collections.unmodifiableList(UNSEEN);
    }

    public static void setUnseen(boolean reset, List<DzUnseenPayload.Entry> entries) {
        if (reset) UNSEEN.clear();
        for (DzUnseenPayload.Entry e : entries) {
            UNSEEN.add(new Row(nextId++, e.lastSeen(), e.script(), e.line(), e.message(), Math.max(1, e.count()), true));
        }
        version++;
    }

    public static void clearUnseen() {
        UNSEEN.clear();
        version++;
    }

    // ------------------------------------------------------------ state

    public static int version() { return version; }
    public static int unread() { return unread; }
    public static long totalAdded() { return totalAdded; }
    public static long lastAt() { return lastAt; }
    public static Row last() { return last; }
    public static void markRead() { unread = 0; }

    // ------------------------------------------------------------ file log

    /** @return true if the error is "new" (not seen within the last minute); only new ones are written to the file. */
    private static boolean writeLog(Row r) {
        long now = r.time;
        String key = r.contentKey();
        Long prev = LOGGED.get(key);
        if (LOGGED.size() > 500) LOGGED.clear();
        LOGGED.put(key, now); // keeps being "a repeat" for as long as it keeps happening
        if (prev != null && now - prev < 60_000) return false;
        try {
            Path file = FabricLoader.getInstance().getGameDir().resolve("logs").resolve("denizen-errors.log");
            Files.createDirectories(file.getParent());
            Files.writeString(file, r.asText() + "\n\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // best-effort
        }
        return true;
    }

    // ------------------------------------------------------------ status line (server log download etc.)

    private static String status = null;
    private static long statusAt = 0;

    private static long statusTtl = 20_000;

    public static void setStatus(String s) {
        setStatus(s, 20_000);
    }

    public static void setStatus(String s, long ttlMs) {
        status = s;
        statusAt = System.currentTimeMillis();
        statusTtl = ttlMs;
    }

    public static String status() { return status; }
    public static long statusAt() { return statusAt; }
    public static long statusTtl() { return statusTtl; }

    // ------------------------------------------------------------ server log link ("Log uploaded: <url>" from the server)

    private static String logUrl = null;
    private static long logPendingUntil = 0;

    /** Called when the console's "Server log" button runs the command; the reply may take a while. */
    public static void markLogRequested() {
        logPendingUntil = System.currentTimeMillis() + 300_000;
    }

    /** @return true (once) if we were waiting for the reply, i.e. the chat message should be hidden. */
    public static boolean consumeLogPending() {
        boolean pending = System.currentTimeMillis() < logPendingUntil;
        logPendingUntil = 0;
        return pending;
    }

    public static void setLogUrl(String url) {
        logUrl = url;
        setStatus("Log uploaded: " + url, 600_000);
    }

    public static String logUrl() { return logUrl; }
}
