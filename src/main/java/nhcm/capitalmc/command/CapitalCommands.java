package nhcm.capitalmc.command;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.server.permissions.Permissions;

import java.util.List;

public class CapitalCommands
{
    private static final List<BaseCommand> COMMANDS = List.of(new TradingCommand(), new TestGuiCommand(), new AdminCommand(), new UserCommand());

    public static void register()
    {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            COMMANDS.forEach(command -> dispatcher.register(command.build()));
            var trade = dispatcher.getRoot().getChild("trade");
            for (String name : List.of("account", "wallet", "order", "orders", "position", "positions", "conditional"))
            {
                var branch = trade.getChild(name);
                dispatcher.register(Commands.literal(name).requires(source -> source.getPlayer() != null)
                                            .executes(branch.getCommand()).redirect(branch));
            }
            dispatcher.register(Commands.literal("user").requires(source -> source.getPlayer() != null).executes(trade.getCommand()).redirect(trade));
            trade.addChild(Commands.literal("admin").requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_OWNER))
                                   .redirect(dispatcher.getRoot().getChild("cmcadmin")).build());
        });
    }
}
