package dev.denismasterherobrine.densityfunctioncompiler.mixin;

import dev.denismasterherobrine.densityfunctioncompiler.aquifer.DfcAquiferFusion;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Not registered in {@code densityfunctioncompiler.mixins.json} by default: a
 * {@code @Redirect} on {@code DensityFunction#compute} interferes with inlining on
 * aquifer hot paths. Re-add the entry to opt into {@link DfcAquiferFusion}.
 */
@Mixin(Aquifer.NoiseBasedAquifer.class)
public class NoiseBasedAquiferMixin {

    @Redirect(
            method = "calculatePressure",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/level/levelgen/DensityFunction;compute"
                                            + "(Lnet/minecraft/world/level/levelgen/DensityFunction$FunctionContext;)D"))
    private double dfc$pressure(
            DensityFunction instance, DensityFunction.FunctionContext context) {
        return DfcAquiferFusion.computeCached(instance, context);
    }

    @Redirect(
            method = "computeSurfaceLevel",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/level/levelgen/DensityFunction;compute"
                                            + "(Lnet/minecraft/world/level/levelgen/DensityFunction$FunctionContext;)D"))
    private double dfc$surface(
            DensityFunction instance, DensityFunction.FunctionContext context) {
        return DfcAquiferFusion.computeCached(instance, context);
    }

    @Redirect(
            method = "computeRandomizedFluidSurfaceLevel",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/level/levelgen/DensityFunction;compute"
                                            + "(Lnet/minecraft/world/level/levelgen/DensityFunction$FunctionContext;)D"))
    private double dfc$randSurface(
            DensityFunction instance, DensityFunction.FunctionContext context) {
        return DfcAquiferFusion.computeCached(instance, context);
    }

    @Redirect(
            method = "computeFluidType",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/level/levelgen/DensityFunction;compute"
                                            + "(Lnet/minecraft/world/level/levelgen/DensityFunction$FunctionContext;)D"))
    private double dfc$fluidType(
            DensityFunction instance, DensityFunction.FunctionContext context) {
        return DfcAquiferFusion.computeCached(instance, context);
    }
}
