package dev.oddfox.infectedcore;
import dev.letitwalk.content.WalkerCoreBlock;
import dev.letitwalk.content.WalkerCoreBlockEntity;
import dev.letitwalk.walker.WalkerBrain;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import java.util.Map;
import java.util.WeakHashMap;
public final class InfectedCoreAI {
  private static final double DETECT=48.0*48.0,MELEE=3.7*3.7;
  private static final Map<WalkerCoreBlockEntity,Long> ATTACK=new WeakHashMap<>();
  private InfectedCoreAI(){}
  public static void tick(WalkerCoreBlockEntity core,ServerSubLevel walkerLevel) {
    if (!core.getBlockState().is(InfectedCore.INFECTED_BLOCK.get())) return;
    if (!(core.getLevel() instanceof ServerLevel world)) return;
    if (!core.isAssembled()||core.parked) return;
    long time=world.getGameTime();
    var pose=walkerLevel.logicalPose();
    Vec3 position=pose.transformPosition(Vec3.atCenterOf(core.getBlockPos()));
    ServerPlayer closest=null;
    double closestDistance=DETECT;
    for (ServerPlayer player:world.players()) {
      if(!player.isAlive()||player.isCreative()||player.isSpectator()) continue;
      double d=player.distanceToSqr(position.x,position.y,position.z);
      if (d<closestDistance) { closestDistance=d; closest=player; }
    }
    if(time%10==0) world.sendParticles(ParticleTypes.HAPPY_VILLAGER,position.x,position.y+0.6,position.z,4,0.3,0.3,0.3,0.02);
    if(closest==null||!core.isPowered()) {core.receiveInput(0,time);return;}
    Direction facing=core.getBlockState().getValue(WalkerCoreBlock.FACING);
    Vec3i normal=facing.getNormal();
    Vector3d forward=new Vector3d(normal.getX(),0,normal.getZ());
    pose.orientation().transform(forward);
    double yaw=Math.atan2(-forward.z,forward.x);
    double targetYaw=Math.atan2(-(closest.getZ()-position.z),closest.getX()-position.x);
    double angle=Math.atan2(Math.sin(targetYaw-yaw),Math.cos(targetYaw-yaw));
    int input=2<<WalkerBrain.GEAR_SHIFT;
    if(angle>0.13)input|=WalkerBrain.KEY_LEFT;
    else if(angle< -0.13)input|=WalkerBrain.KEY_RIGHT;
    if(closestDistance>MELEE&&Math.abs(angle)<0.9)input|=WalkerBrain.KEY_FORWARD;
    core.receiveInput(input,time);
    if(closestDistance<=MELEE) {
      long prev=ATTACK.getOrDefault(core,-20L);
      if(time-prev>=20) {ATTACK.put(core,time); if(closest.hurt(world.damageSources().generic(),6f))
        closest.knockback(0.65,position.x-closest.getX(),position.z-closest.getZ());}
    }
  }
}
