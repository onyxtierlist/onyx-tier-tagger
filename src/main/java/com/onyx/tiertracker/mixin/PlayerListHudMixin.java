package com.onyx.tiertracker.mixin;

import com.onyx.tiertracker.OnyxTierTaggerClient;
import com.onyx.tiertracker.OnyxTierTaggerClient.TierEntry;
import net.minecraft.client.gui.hud.PlayerListHud;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.text.Style;
import net.minecraft.text.StyleSpriteSource;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Locale;

@Mixin(PlayerListHud.class)
public class PlayerListHudMixin {
    private static final Identifier ICON_FONT = Identifier.of("onyx_tagger", "icons");

    @Inject(method = "getPlayerName", at = @At("RETURN"), cancellable = true)
    private void onGetPlayerName(PlayerListEntry entry, CallbackInfoReturnable<Text> cir) {
        if (!OnyxTierTaggerClient.showInTab) return;
        var info = OnyxTierTaggerClient.get(entry);
        if (info == null || info.allTiers().isEmpty()) return;

        Text out = cir.getReturnValue().copy().append(Text.literal("  "));
        for (int i = 0; i < info.allTiers().size(); i++) {
            if (i > 0) out.append(Text.literal("  "));
            TierEntry tier = info.allTiers().get(i);
            out.append(iconText(tier.gamemode()));
            out.append(Text.literal(" "));
            out.append(Text.literal(tier.tier()));
        }
        cir.setReturnValue(out);
    }

    private static Text iconText(String gamemode) {
        String mode = gamemode == null ? "vanilla" : gamemode.toLowerCase(Locale.ROOT)
                .replace("_", "").replace("-", "").replace(" ", "");
        String icon = switch (mode) {
            case "sword" -> "\uE001";
            case "uhc" -> "\uE002";
            case "smp" -> "\uE003";
            case "pot", "potion" -> "\uE004";
            case "nethop", "netheriteop", "netherite" -> "\uE005";
            case "mace" -> "\uE006";
            case "axe" -> "\uE007";
            default -> "\uE008";
        };
        return Text.literal(icon).setStyle(Style.EMPTY.withFont(new StyleSpriteSource.Font(ICON_FONT)));
    }
}
