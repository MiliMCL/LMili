/*
 * This file is part of Leaves (https://github.com/LeavesMC/Leaves)
 *
 * Leaves is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Leaves is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Leaves. If not, see <https://www.gnu.org/licenses/>.
 */

package org.leavesmc.leaves.command.arguments;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.papermc.paper.command.brigadier.argument.CustomArgumentType;
import org.jetbrains.annotations.NotNull;

// import org.leavesmc.leaves.bot.BotList; // Mili - bot system removed
// import org.leavesmc.leaves.bot.ServerBot; // Mili - bot system removed

import java.util.concurrent.CompletableFuture;

public class BotArgumentType implements CustomArgumentType.Converted<@NotNull Object, @NotNull String> {

    private BotArgumentType() {
    }

    public static @NotNull BotArgumentType bot() {
        return new BotArgumentType();
    }

    @Override
    public <S> @NotNull CompletableFuture<Suggestions> listSuggestions(com.mojang.brigadier.context.@NotNull CommandContext<S> context, @NotNull SuggestionsBuilder builder) {
        // Mili - bot system removed; no suggestions available
        return builder.buildFuture();
    }

    @Override
    public Object convert(String nativeType) throws CommandSyntaxException {
        // Mili - bot system removed; always throws
        throw new CommandSyntaxException(
                CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownArgument(),
                () -> "Bot system has been removed"
        );
    }

    @Override
    public @NotNull ArgumentType<@NotNull String> getNativeType() {
        return StringArgumentType.word();
    }
}
