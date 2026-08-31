package com.onyx.tiertracker.mixin;

import com.onyx.tiertracker.OnyxTierTaggerClient;
import com.onyx.tiertracker.OnyxTierTaggerClient.TierEntry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.entity.PlayerLikeEntity;
import net.minecraft.text.Style;
import net.minecraft.text.StyleSpriteSource;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Locale;

@Mixin(PlayerEntityRenderer.class)
public class PlayerEntityRendererMixin {
    private static final Identifier ICON_FONT = Identifier.of("onyx_tagger", "icons");

    @Inject(method = "hasLabel", at = @At("RETURN"), cancellable = true)
    private void onHasLabel(PlayerLikeEntity player, double squaredDistanceToCamera,
                            CallbackInfoReturnable<Boolean> cir) {
        if (!OnyxTierTaggerClient.showAboveHead || !OnyxTierTaggerClient.showSelfName) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null && player == client.player && !client.options.getPerspective().isFirstPerson()) {
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
        if (info == null || info.allTiers().isEmpty()) return;

        String profileName = player.getGameProfile().name();
        if (profileName == null || profileName.isBlank()) return;

        // Always rebuild from the actual profile name. Render states can be reused.
        state.playerName = buildAllTiersCentered(Text.literal(profileName), info);
    }

    /**
     * Keeps the player's name visually central while putting all tiers around it.
     * The stronger half is placed on the left and the remaining tiers on the right.
     */
    private static Text buildAllTiersCentered(Text name, OnyxTierTaggerClient.TierInfo info) {
        var tiers = info.allTiers();
        if (tiers.isEmpty()) return name;

        int leftCount = tiers.size() / 2;
        MutableText result = Text.empty();

        for (int i = 0; i < leftCount; i++) {
            if (i > 0) result.append(Text.literal("  "));
            result.append(tierWithIcon(tiers.get(i), true));
        }

        if (leftCount > 0) result.append(Text.literal("    "));
        result.append(name.copy());
        if (leftCount < tiers.size()) result.append(Text.literal("    "));

        for (int i = leftCount; i < tiers.size(); i++) {
            if (i > leftCount) result.append(Text.literal("  "));
            result.append(tierWithIcon(tiers.get(i), false));
        }
        return result;
    }

    private static Text tierWithIcon(TierEntry tier, boolean iconFirst) {
        String icon = iconChar(tier.gamemode());
        Text iconText = Text.literal(icon)
                .setStyle(Style.EMPTY.withFont(new StyleSpriteSource.Font(ICON_FONT)));
        Text tierText = Text.literal(tier.tier());

        if (iconFirst) return Text.empty().append(iconText).append(Text.literal(" ")).append(tierText);
        return Text.empty().append(tierText).append(Text.literal(" ")).append(iconText);
    }

    private static String iconChar(String gamemode) {
        if (gamemode == null) return "\uE008";
        String mode = gamemode.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "").replace(" ", "");
        return switch (mode) {
            case "sword" -> "\uE001";
            case "uhc" -> "\uE002";
            case "smp" -> "\uE003";
            case "pot", "potion" -> "\uE004";
            case "nethop", "netheriteop", "netherite" -> "\uE005";
            case "mace" -> "\uE006";
            case "axe" -> "\uE007";
            case "vanilla" -> "\uE008";
            default -> "\uE008";
        };
    }
}
