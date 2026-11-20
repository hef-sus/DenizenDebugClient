package dev.dzdebug;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;

import java.util.HashMap;
import java.util.Map;

/** Plays the alert sound when an error arrives. Choices and volume come from DzConfig. */
final class Sounds {
    private record Choice(String name, String id) {}

    private static final Choice[] CHOICES = {
            new Choice("Bell", "block.note_block.bell"),
            new Choice("Pling", "block.note_block.pling"),
            new Choice("Bit", "block.note_block.bit"),
            new Choice("Chime", "block.amethyst_block.chime"),
            new Choice("Bass", "block.note_block.bass"),
            new Choice("Villager", "entity.villager.no"),
            new Choice("Anvil", "block.anvil.land"),
    };

    private static final Map<String, SoundEvent> CACHE = new HashMap<>();
    private static long lastPlayed = 0;
    private static final long COOLDOWN_MS = 1500;

    private Sounds() {}

    static int count() {
        return CHOICES.length;
    }

    static String name(int index) {
        return CHOICES[Math.floorMod(index, CHOICES.length)].name();
    }

    /** @param force true for the "test" button (ignores the on/off switch and the cooldown) */
    static void play(boolean force) {
        DzConfig cfg = DzConfig.get();
        if (!force && !cfg.soundEnabled) return;
        long now = System.currentTimeMillis();
        if (!force && now - lastPlayed < COOLDOWN_MS) return; // a per-tick error must not machine-gun the sound
        lastPlayed = now;

        Choice c = CHOICES[Math.floorMod(cfg.soundIndex, CHOICES.length)];
        SoundEvent ev = CACHE.computeIfAbsent(c.id(),
                id -> SoundEvent.createVariableRangeEvent(Identifier.withDefaultNamespace(id)));
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(ev, 1.0f, cfg.volume));
    }
}
