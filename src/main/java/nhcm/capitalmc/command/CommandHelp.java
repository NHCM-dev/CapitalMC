package nhcm.capitalmc.command;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.commands.CommandSourceStack;
import nhcm.capitalmc.gui.TradingGui;

import java.util.Arrays;

/**
 * Help is derived from the actual command tree, so optional arguments stay accurate.
 */
class CommandHelp
{
    static int show(CommandContext<CommandSourceStack> context, String command, String topic, int page)
    {
        var source = context.getSource();
        var dispatcher = source.getServer().getCommands().getDispatcher();
        CommandNode<CommandSourceStack> root = dispatcher.getRoot().getChild(command);
        if (topic.isBlank())
        {
            send(source, "cli.help.title", "/" + command);
            for (var child : root.getChildren())
            {
                if (!child.getName().equals("help") && child.canUse(source))
                {
                    send(source, "cli.help.topic", child.getName(), TradingGui.text(source.getPlayer(), "cli.topic." + child.getName()).getString(), "/" + command + " help " + child.getName());
                }
            }
            send(source, "cli.help.input");
            return 1;
        }
        var section = root.getChild(topic);
        if (section == null || !section.canUse(source))
        {
            throw new IllegalArgumentException("cli.help.unknown");
        }
        String[] usage = Arrays.stream(dispatcher.getAllUsage(section, source, true)).filter(line -> !line.isBlank()).toArray(String[]::new);
        int pages = Math.max(1, (usage.length + 6) / 7);
        if (page < 1 || page > pages)
        {
            throw new IllegalArgumentException("ui.history.end");
        }
        send(source, "cli.help.page", "/" + command + " " + topic, page, pages);
        Arrays.stream(usage).skip((long) (page - 1) * 7).limit(7)
              .forEach(line -> send(source, "&e/" + command + " " + topic + " &b" + line));
        send(source, "cli.help.input");
        if (page < pages)
        {
            send(source, "cli.help.next", "/" + command + " help " + topic + " " + (page + 1));
        }
        return 1;
    }

    static void send(CommandSourceStack source, String key, Object... values)
    {
        source.sendSuccess(() -> TradingGui.text(source.getPlayer(), key, values), false);
    }
}
