package nhcm.capitalmc.gui.subgui.account;

import com.mojang.serialization.JsonOps;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;
import nhcm.capitalmc.CapitalMC;
import nhcm.capitalmc.gui.*;

import java.time.Duration;

public class AccessPage extends BaseSubGui
{
    public AccessPage(TradingGui gui)
    {
        super(gui, PageType.ACCESS);
    }

    public void login(String preset)
    {
        if (preset != null)
        {
            password(preset);
            return;
        }
        GuiActions.input(getGui(), "ui.access.username", String::strip, value -> {
                  })
                  .thenAccept(value -> value.ifPresent(this::password))
                  .exceptionally(error -> {
                      GuiActions.error(getGui(), error);
                      return null;
                  });
    }

    private void authenticate(String username, String password)
    {
        var gui = getGui();
        var work = CapitalMC.serverUserManager.authenticateAsync(username, password)
                                              .thenApply(result -> {
                                                  if (result.isEmpty())
                                                  {
                                                      throw new IllegalArgumentException("ui.access.bad_password");
                                                  }
                                                  return result.get();
                                              });
        CapitalMC.guiInputManager.awaitPrivateCondition(gui, Duration.ofSeconds(30), work::isDone, () -> GuiActions.run(gui, () -> {
            var verified = work.getNow(null);
            CapitalMC.serverUserManager.activateVerified(gui.getPlayer().getUUID(), verified);
            GuiActions.message(gui, "ui.success.login", verified.getUsername());
        })).exceptionally(error -> {
            GuiActions.error(gui, error);
            return null;
        });
    }

    private void password(String username)
    {
        if (CapitalMC.serverUserManager.findByUsername(username).filter(user -> !user.hasPassword()).isPresent())
        {
            authenticate(username, "");
            return;
        }
        GuiActions.credentialInput(getGui())
                  .thenAccept(value -> value.ifPresent(password -> authenticate(username, password)))
                  .exceptionally(error -> {
                      GuiActions.error(getGui(), error);
                      return null;
                  });
    }

    public void register()
    {
        var gui = getGui();
        var profile = ResolvableProfile.CODEC.encodeStart(JsonOps.INSTANCE,
                                                          ResolvableProfile.createResolved(gui.getPlayer().getGameProfile())).getOrThrow().getAsJsonObject();
        GuiActions.input(gui, "ui.access.username", String::strip, value -> {
        }).thenAccept(name -> name.ifPresent(username ->
                                                     GuiActions.input(gui, "ui.access.display_name", String::strip, value -> {
                                                     }).thenAccept(display -> display.ifPresent(label ->
                                                                                                        GuiActions.credentialInput(gui, "ui.access.new_password")
                                                                                                                  .thenAccept(password -> password.ifPresent(credential -> GuiActions.run(gui, () -> GuiActions.task(gui,
                                                                                                                                                                                                                     CapitalMC.serverUserManager.registerAsync(label, username, credential, gui.getPlayer().getUUID(), profile), "ui.success.register", username))))
                                                                                                                  .exceptionally(error -> {
                                                                                                                      GuiActions.error(gui, error);
                                                                                                                      return null;
                                                                                                                  })
                                                     )).exceptionally(error -> {
                                                         GuiActions.error(gui, error);
                                                         return null;
                                                     })
        )).exceptionally(error -> {
            GuiActions.error(gui, error);
            return null;
        });
    }

    @Override
    public void render(TradingGui gui)
    {
        if (!GuiActions.ready(gui))
        {
            return;
        }
        GuiWidgets.button(gui, 20, Items.PLAYER_HEAD, "ui.access.login", "", () -> login(null));
        GuiWidgets.button(gui, 22, Items.WRITABLE_BOOK, "ui.access.register", "ui.access.register_limit", this::register);
        GuiWidgets.button(gui, 24, Items.BARRIER, "ui.access.logout",
                          gui.getUser().map(user -> user.getDisplayName() + " (" + user.getUsername() + ") #" + user.getExchangeUserId()).orElse(""),
                          () -> {
                              CapitalMC.serverUserManager.logout(gui.getPlayer().getUUID());
                              GuiActions.message(gui, "ui.success.logout");
                          });
        var owned = CapitalMC.serverUserManager.getOwnedAccounts(gui.getPlayer().getUUID());
        for (int i = 0; i < owned.size(); i++)
        {
            var user = owned.get(i);
            gui.setSlot(28 + i, GuiWidgets.accountHead(gui, user, () -> login(user.getUsername())));
        }
        gui.getUser().ifPresent(user -> gui.setSlot(4, GuiWidgets.accountHead(gui, user, null)));
    }
}
