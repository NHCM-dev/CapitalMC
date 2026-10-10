package nhcm.capitalmc.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import nhcm.capitalmc.CapitalMC;
import nhcm.capitalmc.gui.PageType;

import java.util.Locale;

/**
 * Temporary player-only page preview entry point; no accounts or orders are created.
 */
public class TestGuiCommand extends BaseCommand
{
    public TestGuiCommand()
    {
        super("testgui");
    }

    @Override
    protected void configure(LiteralArgumentBuilder<CommandSourceStack> builder)
    {
        for (PageType page : PageType.values())
        {
            builder.then(Commands.literal(page.name().toLowerCase(Locale.ROOT))
                                 .executes(context -> open(context, page)));
        }
    }

    @Override
    protected int execute(CommandContext<CommandSourceStack> context) throws CommandSyntaxException
    {
        return open(context, PageType.HOME);
    }

    private int open(CommandContext<CommandSourceStack> context, PageType page) throws CommandSyntaxException
    {
        var player = context.getSource().getPlayerOrException();
        try
        {
            CapitalMC.guiManager.openTradingGui(player, page);
            return Command.SINGLE_SUCCESS;
        } catch (RuntimeException error)
        {
            CapitalMC.logger.error("Cannot preview GUI page {}", page, error);
            context.getSource().sendFailure(Component.literal("GUI preview failed: " + page
                                                              + ". See the server log for details."));
            return 0;
        }
    }
}
