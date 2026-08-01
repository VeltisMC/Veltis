package ca.spottedleaf.moonrise.mixin.tick_loop;

import org.veltismc.veltis.spark.SparksFlyHolder;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
public abstract class MinecraftServerSparkMixin {

    @Unique
    private long sparkTickStartNanos;

    @Inject(
            method = "processPacketsAndTick",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/MinecraftServer;tickServer(Ljava/util/function/BooleanSupplier;)V",
                    shift = At.Shift.BEFORE
            )
    )
    private void beforeTickServer(final CallbackInfo ci) {
        this.sparkTickStartNanos = System.nanoTime();
        final var fly = SparksFlyHolder.instance;
        if (fly != null) {
            fly.tickStart();
        }
    }

    @Inject(
            method = "processPacketsAndTick",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/MinecraftServer;tickServer(Ljava/util/function/BooleanSupplier;)V",
                    shift = At.Shift.AFTER
            )
    )
    private void afterTickServer(final CallbackInfo ci) {
        final double durationMs = (System.nanoTime() - this.sparkTickStartNanos) / 1_000_000.0;
        final var fly = SparksFlyHolder.instance;
        if (fly != null) {
            fly.executeMainThreadTasks();
            fly.tickEnd(durationMs);
        }
    }
}
