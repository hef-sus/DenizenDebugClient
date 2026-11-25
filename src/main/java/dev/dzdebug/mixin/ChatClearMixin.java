package dev.dzdebug.mixin;

import dev.dzdebug.LogStore;
import net.minecraft.client.gui.components.ChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** F3+D makes vanilla call ChatComponent.clearMessages(...); clear the error box at the same moment. */
@Mixin(ChatComponent.class)
public class ChatClearMixin {
    // require = 0: if the method is renamed in a future version this silently does nothing instead of crashing
    @Inject(method = "clearMessages", at = @At("HEAD"), require = 0)
    private void dzdebug$clearErrorBox(boolean clearSentMessages, CallbackInfo ci) {
        LogStore.clearBox();
    }
}
