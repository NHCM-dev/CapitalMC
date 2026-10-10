package nhcm.capitalmc.mixin;

import net.minecraft.server.level.ServerPlayer;
import nhcm.capitalmc.CapitalMC;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerDropMixin
{
    @Inject(method = "drop(Z)V", at = @At("HEAD"), cancellable = true)
    private void capitalmc$selectDeposit(boolean entireStack, CallbackInfo ci)
    {
        if (CapitalMC.services != null && CapitalMC.services.items.interceptDrop((ServerPlayer) (Object) this, entireStack))
        {
            ci.cancel();
        }
    }
}
