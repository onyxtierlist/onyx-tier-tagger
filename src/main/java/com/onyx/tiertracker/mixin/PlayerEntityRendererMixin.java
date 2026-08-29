package com.onyx.tiertracker.mixin;

import com.onyx.tiertracker.OnyxTierTaggerClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.entity.player.PlayerLikeEntity;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerEntityRenderer.class)
public class PlayerEntityRendererMixin {
    @Inject(method = "hasLabel", at = @At("RETURN"), cancellable = true)
    private void onHasLabel(PlayerLikeEntity player, double squaredDistanceToCamera,
                            CallbackInfoReturnable<Boolean> cir) {
        if (!OnyxTierTaggerClient.showAboveHead) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null && player == client.player && OnyxTierTaggerClient.showSelfName
                && !client.options.getPerspective().isFirstPerson()) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "renderLabelIfPresent", at = @At("HEAD"))
    private void onRenderLabel(PlayerEntityRenderState state,
                               net.minecraft.client.util.math.MatrixStack matrices,
                               net.minecraft.client.render.command.OrderedRenderCommandQueue queue,
                               net.minecraft.client.render.state.CameraRenderState camera,
                               CallbackInfo ci) {
        if (!OnyxTierTaggerClient.showAboveHead || state.id < 0) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return;

        var entity = client.world.getEntityById(state.id);
        if (!(entity instanceof net.minecraft.entity.player.PlayerEntity player)) return;

        var info = OnyxTierTaggerClient.get(player);
        if (info == null) return;

        // PlayerEntityRenderer already renders the player's normal name.
        // We append the Onyx tag so the final display is: [HT1 ◆] Steve
        Text tag = Text.literal("[" + info.tier() + " " + info.emoji() + "] ");
        state.playerName = tag.copy().append(state.playerName);
    }
}
