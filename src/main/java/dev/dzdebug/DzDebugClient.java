package dev.dzdebug;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.Command;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DzDebugClient implements ClientModInitializer {
    public static final String MOD_ID = "dzdebug";

    /** Chat fallback format:  [[DZERR]]script[[|]]line[[|]]message  (captured and hidden, never shown in chat) */
    private static final String MARKER = "[[DZERR]]";
    private static final String SEP = "[[|]]";

    /** Reply of your server log command, e.g. "Log uploaded: https://...". */
    private static final String LOG_PREFIX = "Log uploaded:";
    private static final Pattern URL = Pattern.compile("https?://\\S+");

    private static KeyMapping openKey;
    private static boolean pendingOpen;

    @Override
    public void onInitializeClient() {
        DzConfig.load();

        KeyMapping.Category category =
                KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
        openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.dzdebug.open", InputConstants.Type.KEYBOARD, InputConstants.KEY_F4, category));

        // ---- networking ----
        PayloadTypeRegistry.clientboundPlay().register(DzErrorPayload.TYPE, DzErrorPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(DzUnseenPayload.TYPE, DzUnseenPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(DzHelloPayload.TYPE, DzHelloPayload.CODEC);

        ClientPlayNetworking.registerGlobalReceiver(DzErrorPayload.TYPE, (payload, context) ->
                context.client().execute(() ->
                        accept(payload.script(), payload.line(), payload.message(), payload.count())));

        ClientPlayNetworking.registerGlobalReceiver(DzUnseenPayload.TYPE, (payload, context) ->
                context.client().execute(() -> LogStore.setUnseen(payload.reset(), payload.entries())));

        // On join: forget the previous server's unseen list and ask this server for its own.
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            LogStore.clearUnseen();
            try {
                ClientPlayNetworking.send(DzHelloPayload.INSTANCE);
            } catch (Throwable ignored) {
                // server doesn't know the channel - fine
            }
        });

        // Chat fallback path: capture marked messages and hide them (nothing is printed to chat).
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> overlay || !handleChat(message));

        // ---- HUD box (top-left) + click on it while chat is open ----
        HudElementRegistry.attachElementAfter(VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath(MOD_ID, "error_box"), ErrorHud::render);

        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (screen instanceof ChatScreen) {
                ScreenMouseEvents.allowMouseClick(screen).register((s, click) -> {
                    if (ErrorHud.hit(click.x(), click.y())) {
                        client.gui.setScreen(new ConsoleScreen(null));
                        return false; // consume the click
                    }
                    return true;
                });
            }
        });

        // ---- commands / key ----
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) ->
                dispatcher.register(ClientCommands.literal("dzerr")
                        .executes(ctx -> {
                            pendingOpen = true; // opened on next tick, after the chat screen has closed
                            return Command.SINGLE_SUCCESS;
                        })
                        .then(ClientCommands.literal("clear").executes(ctx -> {
                            LogStore.clearLive();
                            ctx.getSource().sendFeedback(Component.literal("Denizen console cleared.")
                                    .withStyle(ChatFormatting.GRAY));
                            return Command.SINGLE_SUCCESS;
                        }))));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openKey.consumeClick()) {
                pendingOpen = true;
            }
            if (pendingOpen && client.gui.screen() == null) {
                pendingOpen = false;
                client.gui.setScreen(new ConsoleScreen(null));
            }
        });
    }

    private static void accept(String script, String line, String msg, int count) {
        boolean isNew = LogStore.addLive(script.strip(), line.strip(), msg.strip(), count);
        if (isNew || DzConfig.get().soundOnRepeats) {
            Sounds.play(false);
        }
    }

    /** @return true if the chat message was one of ours (and should be hidden). */
    private static boolean handleChat(Component message) {
        String text = ChatFormatting.stripFormatting(message.getString());
        if (text == null) return false;

        // The server's reply to the "Server log" button: remember the link; hide the chat line only if we asked for it.
        int up = text.indexOf(LOG_PREFIX);
        if (up >= 0) {
            Matcher m = URL.matcher(text.substring(up));
            if (m.find()) {
                LogStore.setLogUrl(m.group());
                return LogStore.consumeLogPending();
            }
        }

        int at = text.indexOf(MARKER);
        if (at < 0) return false;

        String[] parts = text.substring(at + MARKER.length()).split(Pattern.quote(SEP), 3);
        accept(parts.length > 0 ? parts[0] : "null",
               parts.length > 1 ? parts[1] : "null",
               parts.length > 2 ? parts[2] : "", 1);
        return true;
    }
}
