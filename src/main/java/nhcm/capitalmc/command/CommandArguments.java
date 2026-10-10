package nhcm.capitalmc.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.LiteralMessage;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import nhcm.capitalmc.CapitalMC;
import nhcm.capitalmc.account.ServerUser;
import nhcm.capitalmc.exchange.SymbolUtil;
import nhcm.capitalmc.service.GameServices;

import java.util.List;

/**
 * Suggestions share registered assets and exchange snapshots with the GUI.
 */
class CommandArguments
{
    static final SuggestionProvider<CommandSourceStack> ASSETS = (context, builder) ->
            SharedSuggestionProvider.suggest(CapitalMC.services == null ? List.of() : GameServices.get().assets.all().stream().map(a -> a.name()).toList(), builder);
    static final SuggestionProvider<CommandSourceStack> USERS = (context, builder) ->
            SharedSuggestionProvider.suggest(CapitalMC.serverUserManager.getUsers().stream().map(ServerUser::getUsername), builder);
    static final SuggestionProvider<CommandSourceStack> SYMBOLS = (context, builder) -> {
        if (CapitalMC.exchange == null)
        {
            return builder.buildFuture();
        }
        return SymbolUtil.allSymbols().thenApply(symbols -> {
            for (var symbol : symbols)
            {
                String id = Long.toString(symbol.symbolId());
                if (id.startsWith(builder.getRemaining()))
                {
                    builder.suggest(id, new LiteralMessage(symbol.name()));
                }
            }
            return builder.build();
        });
    };

    static RequiredArgumentBuilder<CommandSourceStack, String> text(String name)
    {
        return Commands.argument(name, StringArgumentType.string());
    }

    static RequiredArgumentBuilder<CommandSourceStack, String> choice(String name, String... values)
    {
        return text(name).suggests((context, builder) -> SharedSuggestionProvider.suggest(values, builder));
    }

    static String string(CommandContext<CommandSourceStack> context, String name)
    {
        return StringArgumentType.getString(context, name);
    }
}
