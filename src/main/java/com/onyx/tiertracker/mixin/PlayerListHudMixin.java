package com.onyx.tiertracker.mixin;

import com.onyx.tiertracker.OnyxTierTaggerClient;
import net.minecraft.client.gui.hud.PlayerListHud;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Adds the Onyx tier to TAB without allowing malformed player/API data to
 * take down the entire TAB rendering path.
 */
@Mixin(PlayerListHud.class)
public class PlayerListHudMixin {
    @Inject(
        method = "getPlayerName",
        at = @At("RETURN"),
        cancellable = true
    )
    private void onGetPlayerName(PlayerListEntry entry, CallbackInfoReturnable<Text> cir) {
        if (!OnyxTierTaggerClient.showInTab || entry == null) return;

        try {
            var profile = entry.getProfile();
            if (profile == null || profile.id() == null) return;

            String name = profile.name();
            if (name == null || name.isBlank()) return;

            var info = OnyxTierTaggerClient.get(entry);
            if (info == null || info.topTiers() == null || info.topTiers().isEmpty()) return;

            Text base = cir.getReturnValue();
            if (base == null) base = Text.literal(name);

            cir.setReturnValue(PlayerEntityRendererMixin.buildCenteredLabel(base, info));
        } catch (Throwable ignored) {
            // TAB must remain usable even if a third-party profile, API
            // response, font, or text component is malformed.
        }
    }
}
