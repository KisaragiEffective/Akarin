package io.akarin.server.mixin.core;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.function.BooleanSupplier;

import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.chunkio.ChunkIOExecutor;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import co.aikar.timings.MinecraftTimings;
import io.akarin.api.internal.Akari;
import io.akarin.api.internal.mixin.IMixinWorldServer;
import io.akarin.server.core.AkarinGlobalConfig;
import io.akarin.server.core.AkarinSlackScheduler;
import net.minecraft.server.CrashReport;
import net.minecraft.server.CustomFunctionData;
import net.minecraft.server.DimensionManager;
import net.minecraft.server.ITickable;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.MojangStatisticsGenerator;
import net.minecraft.server.ReportedException;
import net.minecraft.server.ServerConnection;
import net.minecraft.server.SystemUtils;
import net.minecraft.server.TileEntityHopper;
import net.minecraft.server.WorldServer;

@Mixin(value = MinecraftServer.class, remap = false)
public abstract class MixinMinecraftServer {
    @Shadow @Final public Thread primaryThread;
    private int cachedWorldSize;
    
    @Overwrite
    public String getServerModName() {
        return "Akarin";
    }
    
    @Inject(method = "run()V", at = @At(
            value = "INVOKE",
            target = "net/minecraft/server/SystemUtils.b()J",
            shift = At.Shift.BEFORE
    ))
    private void prerun(CallbackInfo info) throws IllegalArgumentException, IllegalAccessException, NoSuchFieldException, SecurityException {
        primaryThread.setPriority(AkarinGlobalConfig.primaryThreadPriority < Thread.NORM_PRIORITY ? Thread.NORM_PRIORITY :
            (AkarinGlobalConfig.primaryThreadPriority > Thread.MAX_PRIORITY ? 10 : AkarinGlobalConfig.primaryThreadPriority));
        Akari.resizeTickExecutors((cachedWorldSize = worldServer.size()));
        
        Field skipHopperEvents = TileEntityHopper.class.getDeclaredField("skipHopperEvents"); // No idea why static but check each world
        skipHopperEvents.setAccessible(true);
        for (WorldServer world : worldServer.values()) {
            skipHopperEvents.set(null, world.paperConfig.disableHopperMoveEvents || InventoryMoveItemEvent.getHandlerList().getRegisteredListeners().length == 0);
        }
        AkarinSlackScheduler.get().boot();
    }
    
    @Overwrite
    public boolean isMainThread() {
        return Akari.isPrimaryThread();
    }
    
    /*
     * Forcely disable snooper
     */
    @Overwrite
    public void a(MojangStatisticsGenerator generator) {}
    
    /*
     * Parallel world ticking
     */
    @Shadow public CraftServer server;
    @Shadow @Mutable protected Queue<FutureTask<?>> f;
    @Shadow public Queue<Runnable> processQueue;
    @Shadow private int ticks;
    @Shadow @Final public Map<DimensionManager, WorldServer> worldServer;
    @Shadow(aliases = "k") @Final private List<ITickable> tickables;
    
    @Shadow public abstract CustomFunctionData getFunctionData();
    @Shadow public abstract ServerConnection getServerConnection();
    
    private boolean tickEntities(WorldServer world) {
        try {
            world.timings.tickEntities.startTiming();
            world.tickEntities();
            world.timings.tickEntities.stopTiming();
            world.getTracker().updatePlayers();
            world.explosionDensityCache.clear(); // Paper - Optimize explosions
        } catch (Throwable throwable) {
            CrashReport crashreport;
            try {
                crashreport = CrashReport.a(throwable, "Exception ticking world entities");
            } catch (Throwable t){
                throw new RuntimeException("Error generating crash report", t);
            }
            world.a(crashreport);
            throw new ReportedException(crashreport);
        }
        return true;
    }
    
    private void tickWorld(WorldServer world, BooleanSupplier supplier) {
        try {
            world.timings.doTick.startTiming();
            world.doTick(supplier);
            world.timings.doTick.stopTiming();
        } catch (Throwable throwable) {
            CrashReport crashreport;
            try {
                crashreport = CrashReport.a(throwable, "Exception ticking world");
            } catch (Throwable t){
                throw new RuntimeException("Error generating crash report", t);
            }
            world.a(crashreport);
            throw new ReportedException(crashreport);
        }
    }
    
    @Overwrite
    public void b(BooleanSupplier supplier) throws InterruptedException, ExecutionException {
        Runnable runnable;
        MinecraftTimings.bukkitSchedulerTimer.startTiming();
        this.server.getScheduler().mainThreadHeartbeat(this.ticks);
        MinecraftTimings.bukkitSchedulerTimer.stopTiming();
        
        MinecraftTimings.minecraftSchedulerTimer.startTiming();
        FutureTask<?> task;
        int count = f.size();
        while (count-- > 0 && (task = f.poll()) != null) {
            SystemUtils.a(task, MinecraftServer.LOGGER);
        }
        MinecraftTimings.minecraftSchedulerTimer.stopTiming();
        
        MinecraftTimings.commandFunctionsTimer.startTiming();
        getFunctionData().Y_();
        MinecraftTimings.commandFunctionsTimer.stopTiming();
        
        MinecraftTimings.processQueueTimer.startTiming();
        while ((runnable = processQueue.poll()) != null) runnable.run();
        MinecraftTimings.processQueueTimer.stopTiming();
        
        MinecraftTimings.chunkIOTickTimer.startTiming();
        ChunkIOExecutor.tick();
        MinecraftTimings.chunkIOTickTimer.stopTiming();
        
        if (cachedWorldSize != worldServer.size()) Akari.resizeTickExecutors((cachedWorldSize = worldServer.size()));
        switch (AkarinGlobalConfig.parallelMode) {
            case 1:
            case 2:
            default:
                // Never tick one world concurrently!
                WorldServer interlacedWorld = null;
                for (WorldServer world : worldServer.values()) {
                    if (interlacedWorld == null) {
                        interlacedWorld = world;
                    } else {
                        Akari.STAGE_TICK.submit(() -> {
                            synchronized (((IMixinWorldServer) world).tickLock()) {
                                tickEntities(world);
                            }
                        }, null);
                    }
                    
                    if (AkarinGlobalConfig.parallelMode != 1 /* >= 2 */) {
                        Akari.STAGE_TICK.submit(() -> {
                            synchronized (((IMixinWorldServer) world).tickLock()) {
                                tickWorld(world, supplier);
                            }
                        }, null);
                    }
                }
                WorldServer fInterlacedWorld = interlacedWorld;
                Akari.STAGE_TICK.submit(() -> {
                    synchronized (((IMixinWorldServer) fInterlacedWorld).tickLock()) {
                        tickEntities(fInterlacedWorld);
                    }
                }, null);
                
                if (AkarinGlobalConfig.parallelMode == 1)
                Akari.STAGE_TICK.submit(() -> {
                    for (WorldServer world : worldServer.values()) {
                        synchronized (((IMixinWorldServer) world).tickLock()) {
                            tickWorld(world, supplier);
                        }
                    }
                }, null);
                
                for (int i = (AkarinGlobalConfig.parallelMode == 1 ? cachedWorldSize + 1 : cachedWorldSize * 2); i --> 0 ;) {
                    Akari.STAGE_TICK.take();
                }
                
                break;
            case 0:
                Akari.STAGE_TICK.submit(() -> {
                    WorldServer interlacedWorld_ = null;
                    for (WorldServer world : worldServer.values()) {
                        if (interlacedWorld_ == null) {
                            interlacedWorld_ = world;
                            continue;
                        }
                        synchronized (((IMixinWorldServer) world).tickLock()) {
                            tickEntities(world);
                        }
                    }
                    synchronized (((IMixinWorldServer) interlacedWorld_).tickLock()) {
                        tickEntities(interlacedWorld_);
                    }
                }, null);
                
                Akari.STAGE_TICK.submit(() -> {
                    for (WorldServer world : worldServer.values()) {
                        synchronized (((IMixinWorldServer) world).tickLock()) {
                            tickWorld(world, supplier);
                        }
                    }
                }, null);
                
                Akari.STAGE_TICK.take();
                Akari.STAGE_TICK.take();
                break;
            case -1:
                for (WorldServer world : worldServer.values()) {
                    tickWorld(world, supplier);
                    tickEntities(world);
                }
                break;
        }
        
        Akari.callbackTiming.startTiming();
        while ((runnable = Akari.callbackQueue.poll()) != null) runnable.run();
        Akari.callbackTiming.stopTiming();
        
        MinecraftTimings.connectionTimer.startTiming();
        getServerConnection().c();
        MinecraftTimings.connectionTimer.stopTiming();
        
        Akari.callbackTiming.startTiming();
        while ((runnable = Akari.callbackQueue.poll()) != null) runnable.run();
        Akari.callbackTiming.stopTiming();
        
        MinecraftTimings.tickablesTimer.startTiming();
        for (int i = 0; i < this.tickables.size(); ++i) {
            tickables.get(i).Y_();
        }
        MinecraftTimings.tickablesTimer.stopTiming();
    }
}
