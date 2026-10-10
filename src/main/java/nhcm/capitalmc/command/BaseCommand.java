package nhcm.capitalmc.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

import java.util.Objects;

/**
 * Shared lifecycle for commands: access checks, root execution, and argument setup.
 */
public abstract class BaseCommand
{
    private final String name;

    protected BaseCommand(String name)
    {
        Objects.requireNonNull(name, "name");
        if (name.isBlank() || name.codePoints().anyMatch(Character::isWhitespace) || name.startsWith("/"))
        {
            throw new IllegalArgumentException("Command name must be a single literal without a leading slash");
        }
        this.name = name;
    }

    public final String getName()
    {
        return name;
    }

    /**
     * Creates a fresh command tree for each server registration.
     */
    public final LiteralArgumentBuilder<CommandSourceStack> build()
    {
        LiteralArgumentBuilder<CommandSourceStack> builder = Commands.literal(name)
                                                                     .requires(this::canUse)
                                                                     .executes(this::execute);
        configure(builder);
        return builder;
    }

    /**
     * Override to restrict access to the command and its children.
     */
    protected boolean canUse(CommandSourceStack source)
    {
        return true;
    }

    /**
     * Add arguments and subcommands here using Brigadier builders.
     */
    protected void configure(LiteralArgumentBuilder<CommandSourceStack> builder)
    {
    }

    protected abstract int execute(CommandContext<CommandSourceStack> context)
            throws CommandSyntaxException;
}
