package net.minecraft.server.commands;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;

public class SeedCommand {
    public static void register(final CommandDispatcher<CommandSourceStack> dispatcher, final boolean checkPermissions) {
        dispatcher.register(
            Commands.literal("seed").requires(Commands.hasPermission(checkPermissions ? Commands.LEVEL_GAMEMASTERS : Commands.LEVEL_ALL)).executes(c -> {
                long seed = c.getSource().getLevel().getSeed();
                Component seedText = ComponentUtils.copyOnClickText(String.valueOf(seed));
                c.getSource().sendSuccess(() -> Component.translatable("commands.seed.success", seedText), false);
                // Leaf start - Matter - SecureSeed Command
                if (fun.bm.mili.lmili.config.modules.function.SecureSeedConfig.enabled) {
                    su.plo.matter.Globals.setupGlobals(c.getSource().getLevel());
                    String seedStr = su.plo.matter.Globals.seedToString(su.plo.matter.Globals.worldSeed);
                    Component featureSeedComponent = ComponentUtils.copyOnClickText(seedStr);

                    c.getSource().sendSuccess(() -> Component.translatable(("Feature seed: %s"), featureSeedComponent), false);
                }
                // Leaf end - Matter - SecureSeed Command
                return (int)seed;
            })
        );
    }
}
