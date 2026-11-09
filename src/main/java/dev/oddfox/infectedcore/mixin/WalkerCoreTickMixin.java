package dev.oddfox.infectedcore.mixin;
import dev.letitwalk.content.WalkerCoreBlockEntity;
import dev.oddfox.infectedcore.InfectedCoreAI;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(value=WalkerCoreBlockEntity.class,remap=false)
public abstract class WalkerCoreTickMixin {
  @Inject(method="sable$tick",at=@At("TAIL"),remap=false,require=1)
  private void infectedCore$afterWalkerTick(ServerSubLevel subLevel,CallbackInfo ci) {
    InfectedCoreAI.tick((WalkerCoreBlockEntity)(Object)this,subLevel);
  }
}
