package com.onyx.tiertracker.mixin;

import com.onyx.tiertracker.OnyxTierTaggerClient;
import net.minecraft.client.gui.hud.PlayerListHud;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerListHud.class)
public class PlayerListHudMixin {
    @Inject(method = "getPlayerName", at = @At("RETURN"), cancellable = true)
    private void onGetPlayerName(PlayerListEntry entry, CallbackInfoReturnable<Text> cir) {
        if (!OnyxTierTaggerClient.showInTab) return;
        OnyxTierTaggerClient.TierInfo info = OnyxTierTaggerClient.get(entry);
        if (info == null) return;
        cir.setReturnValue(cir.getReturnValue().copy().append(Text.literal(" " + info.emoji() + " " + info.tier())));
    }
}
