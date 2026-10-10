package nhcm.capitalmc.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import nhcm.capitalmc.CapitalMC;

public class TradingCommand extends BaseCommand
{
    public TradingCommand()
    {
        super("trading");
    }

    @Override
    protected int execute(CommandContext<CommandSourceStack> context) throws CommandSyntaxException
    {
        CapitalMC.guiManager.openTradingGui(context.getSource().getPlayerOrException());
        return Command.SINGLE_SUCCESS;
    }
}
