package nhcm.capitalmc.command;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.server.permissions.Permissions;

import java.util.List;

public class CapitalCommands
{
    private static final List<BaseCommand> COMMANDS = List.of(new TradingCommand(), new TestGuiCommand(), new AdminCommand(), new UserCommand());

    private CapitalCommands()
    {
    }

    public static void register()
    {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            COMMANDS.forEach(command -> dispatcher.register(command.build()));
            dispatcher.getRoot().getChild("capital").addChild(Commands.literal("admin")
                                                                      .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_OWNER))
                                                                      .redirect(dispatcher.getRoot().getChild("cmcadmin")).build());
        });
    }
}
