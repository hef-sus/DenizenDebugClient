package dev.dzdebug;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

/** config/dzdebug.json */
public final class DzConfig {
    public boolean soundEnabled = true;
    public int soundIndex = 0;
    public float volume = 0.75f;
    public boolean soundOnRepeats = false;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static DzConfig instance = new DzConfig();

    private DzConfig() {}

    public static DzConfig get() {
        return instance;
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("dzdebug.json");
    }

    public static void load() {
        try {
            Path p = path();
            if (Files.exists(p)) {
                DzConfig c = GSON.fromJson(Files.readString(p), DzConfig.class);
                if (c != null) instance = c;
            }
        } catch (Exception ignored) {
            // bad file: keep defaults
        }
        if (instance.soundIndex < 0 || instance.soundIndex >= Sounds.count()) instance.soundIndex = 0;
        instance.volume = Math.max(0.05f, Math.min(1f, instance.volume));
    }

    public static void save() {
        try {
            Path p = path();
            Files.createDirectories(p.getParent());
            Files.writeString(p, GSON.toJson(instance));
        } catch (Exception ignored) {
            // best-effort
        }
    }
}
