package dev.denismasterherobrine.densityfunctioncompiler.compiler.pipeline;

import dev.denismasterherobrine.densityfunctioncompiler.DensityFunctionCompiler;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseRouter;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Top-level orchestration for replacing every {@link NoiseRouter} field with a compiled
 * version. Holds aggregate stats consumed by the {@code /dfc} command.
 */
public final class RouterPipeline {

    private static final AtomicInteger ROOTS_COMPILED = new AtomicInteger();
    private static final AtomicInteger CLASSES_ALIVE = new AtomicInteger();
    private static final AtomicLong UNIQUE_NODES_TOTAL = new AtomicLong();
    private static final AtomicLong CSE_SAVINGS_TOTAL = new AtomicLong();
    private static final AtomicLong HELPERS_TOTAL = new AtomicLong();

    private RouterPipeline() {}

    /**
     * Compile each of the {@link NoiseRouter}'s 15 root fields independently and return a
     * fresh router pointing at the compiled equivalents. Returns the {@code original}
     * instance unchanged if every field failed (e.g. an unbound
     * {@link net.minecraft.core.Holder.Reference Holder.Reference} when called too early
     * in startup) — partial success still ships a fresh router with the compiled fields.
     *
     * <p>This deliberately does <strong>not</strong> use {@link NoiseRouter#mapAll}.
     * mapAll is post-order over the entire tree, which would invoke
     * {@link CompilingVisitor#apply} on <em>every internal node</em>; each invocation
     * compiles the node into its own {@link dev.denismasterherobrine.densityfunctioncompiler.compiler.codegen.CompiledDensityFunction}
     * that just delegates to its already-compiled child via INVOKEINTERFACE. With a
     * realistic Overworld router that means tens of thousands of hidden classes for the
     * 35 source roots — each layer adds metaspace pressure and a virtual call to the
     * worldgen hot path. By compiling each top-level field as a single unit we get one
     * generated class per field plus one per inner Marker subtree (which we still have
     * to keep separate so {@code NoiseChunk} can swap them with cell caches).
     *
     * <p>Failures are intentionally only logged at {@code DEBUG}: the lazy accessor
     * mixin will retry on the next read, so a transient failure isn't worth a stack
     * trace in the user's console.
     */
    public static NoiseRouter compile(NoiseRouter original) {
        CompilingVisitor visitor = CompilingVisitor.global();
        DensityFunction[] compiled = new DensityFunction[15];
        DensityFunction[] sources = new DensityFunction[]{
                original.barrierNoise(),
                original.fluidLevelFloodednessNoise(),
                original.fluidLevelSpreadNoise(),
                original.lavaNoise(),
                original.temperature(),
                original.vegetation(),
                original.continents(),
                original.erosion(),
                original.depth(),
                original.ridges(),
                original.initialDensityWithoutJaggedness(),
                original.finalDensity(),
                original.veinToggle(),
                original.veinRidged(),
                original.veinGap(),
        };
        boolean anyChanged = false;
        for (int i = 0; i < sources.length; i++) {
            DensityFunction src = sources[i];
            DensityFunction out;
            try {
                out = visitor.apply(src);
            } catch (Throwable t) {
                DensityFunctionCompiler.LOGGER.debug(
                        "RouterPipeline.compile failed for field {} (will retry on next access); "
                                + "this is normal when registries are not yet bound.", i, t);
                out = src;
            }
            compiled[i] = out;
            if (out != src) anyChanged = true;
        }
        if (!anyChanged) {
            return original;
        }
        return new NoiseRouter(
                compiled[0],  compiled[1],  compiled[2],  compiled[3],  compiled[4],
                compiled[5],  compiled[6],  compiled[7],  compiled[8],  compiled[9],
                compiled[10], compiled[11], compiled[12], compiled[13], compiled[14]
        );
    }

    /**
     * Compile the six climate density functions of a {@link Climate.Sampler}.
     *
     * <p>The biome system reads {@code temperature / humidity / continentalness / erosion
     * / depth / weirdness} via {@link Climate.Sampler#sample(int, int, int)} for every
     * biome lookup — that's hundreds of thousands of single-point evaluations per chunk
     * during initial generation. Each of those fields is, structurally, the same kind of
     * shifted-noise + spline tree as the corresponding {@link NoiseRouter} field, so it
     * benefits from the same JIT compilation.
     *
     * <p>The sampler must be compiled <em>after</em> {@code RandomState.<init>} has wired
     * the source DensityFunctions through {@code NoiseWiringHelper}. Compiling earlier
     * leaves every {@link DensityFunction.NoiseHolder} unbound, and our IR builder collapses
     * those to {@code Const(0.0)} (matching vanilla's "noise is null → 0.0" fallback). A
     * NoiseRouter where every noise reads as 0 is what produced the well-known "all River
     * biomes / no terrain features" symptom — the climate sampler picks the closest
     * defined biome to {@code (0, 0, 0, 0, 0, 0)} which happens to be River, and the
     * height/erosion/density routers all collapse to flat featureless terrain.
     *
     * <p>Failures fall back to the original sampler field, identical to {@link #compile}.
     */
    public static Climate.Sampler compileSampler(Climate.Sampler original) {
        CompilingVisitor visitor = CompilingVisitor.global();
        DensityFunction[] sources = new DensityFunction[]{
                original.temperature(),
                original.humidity(),
                original.continentalness(),
                original.erosion(),
                original.depth(),
                original.weirdness(),
        };
        DensityFunction[] compiled = new DensityFunction[6];
        boolean anyChanged = false;
        for (int i = 0; i < sources.length; i++) {
            DensityFunction src = sources[i];
            DensityFunction out;
            try {
                out = visitor.apply(src);
            } catch (Throwable t) {
                DensityFunctionCompiler.LOGGER.debug(
                        "RouterPipeline.compileSampler failed for climate field {} "
                                + "(falling back to vanilla evaluator)", i, t);
                out = src;
            }
            compiled[i] = out;
            if (out != src) anyChanged = true;
        }
        if (!anyChanged) {
            return original;
        }
        return new Climate.Sampler(
                compiled[0], compiled[1], compiled[2],
                compiled[3], compiled[4], compiled[5],
                original.spawnTarget());
    }

    public static void recordCompiledRoot(int uniqueNodes, int csePostInternSavings) {
        ROOTS_COMPILED.incrementAndGet();
        CLASSES_ALIVE.incrementAndGet();
        UNIQUE_NODES_TOTAL.addAndGet(uniqueNodes);
        CSE_SAVINGS_TOTAL.addAndGet(csePostInternSavings);
    }

    public static void recordHelpers(int helpersEmitted) {
        if (helpersEmitted > 0) HELPERS_TOTAL.addAndGet(helpersEmitted);
    }

    public static void recordCompiledClassDropped() {
        CLASSES_ALIVE.decrementAndGet();
    }

    public record Stats(int rootsCompiled, int classesAlive, long uniqueNodes,
                         long savedByCse, long helpersEmitted) {}

    public static Stats snapshotStats() {
        return new Stats(
                ROOTS_COMPILED.get(),
                CLASSES_ALIVE.get(),
                UNIQUE_NODES_TOTAL.get(),
                CSE_SAVINGS_TOTAL.get(),
                HELPERS_TOTAL.get());
    }
}
