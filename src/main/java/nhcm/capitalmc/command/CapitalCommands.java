package nhcm.capitalmc.command;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

import java.util.List;

public class CapitalCommands {
    private static final List<BaseCommand> COMMANDS = List.of(new TradingCommand(), new TestGuiCommand());

    private CapitalCommands() {}

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                COMMANDS.forEach(command -> dispatcher.register(command.build())));
    }
}
