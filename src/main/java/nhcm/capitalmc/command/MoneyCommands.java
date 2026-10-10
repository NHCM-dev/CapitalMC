package nhcm.capitalmc.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.*;
import net.minecraft.server.permissions.Permissions;
import nhcm.capitalmc.exchange.ExchangeMessages;
import nhcm.capitalmc.gui.TradingGui;
import nhcm.capitalmc.service.*;

import java.util.List;

/**
 * Typed privileged monetary commands. Reasons are mandatory and credentials never enter the command tree.
 */
final class MoneyCommands
{
    private MoneyCommands()
    {
    }

    static void register(LiteralArgumentBuilder<CommandSourceStack> root)
    {
        for (String action : List.of("mint", "burn"))
            root.then(Commands.literal(action)
                              .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_OWNER))
                              .then(Commands.argument("asset", StringArgumentType.word()).suggests((context, builder) -> {
                                  GameServices.get().assets.all().stream().filter(asset -> asset.electronic())
                                                           .map(asset -> asset.name()).filter(name -> name.startsWith(builder.getRemaining())).forEach(builder::suggest);
                                  return builder.buildFuture();
                              }).then(Commands.argument("amount", StringArgumentType.word())
                                              .then(Commands.argument("reason", StringArgumentType.greedyString()).executes(context -> {
                                                  var source = context.getSource();
                                                  var services = GameServices.get();
                                                  try
                                                  {
                                                      if (!services.ready().isDone() || services.ready().isCompletedExceptionally())
                                                      {
                                                          throw new IllegalStateException("ui.loading");
                                                      }
                                                      var asset = services.assets.find(StringArgumentType.getString(context, "asset")).orElseThrow();
                                                      long amount = asset.parse(StringArgumentType.getString(context, "amount"));
                                                      String reason = StringArgumentType.getString(context, "reason");
                                                      String actor = source.getPlayer() == null ? "console:" + source.getTextName() : source.getPlayer().getUUID().toString();
                                                      var work = action.equals("mint") ? services.currency.mint(asset.id(), amount, actor, reason)
                                                              : services.currency.burn(asset.id(), amount, actor, reason);
                                                      work.whenComplete((receipt, error) -> source.getServer().execute(() -> {
                                                          if (error == null)
                                                          {
                                                              source.sendSuccess(() -> TradingGui.text(source.getPlayer(), "ui.money.completed", ExchangeMessages.action(source.getPlayer(), action), asset.displayName(), asset.format(amount)), false);
                                                          }
                                                          else
                                                          {
                                                              var cause = error;
                                                              while (cause.getCause() != null)
                                                                  cause = cause.getCause();
                                                              source.sendFailure(TradingGui.text(source.getPlayer(), "ui.error", ExchangeMessages.error(source.getPlayer(), cause)));
                                                          }
                                                      }));
                                                      return 1;
                                                  } catch (RuntimeException error)
                                                  {
                                                      source.sendFailure(TradingGui.text(source.getPlayer(), "ui.error", ExchangeMessages.error(source.getPlayer(), error)));
                                                      return 0;
                                                  }
                                              })))));
    }
}
