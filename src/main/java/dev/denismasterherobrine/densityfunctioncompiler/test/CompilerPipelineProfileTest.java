package dev.denismasterherobrine.densityfunctioncompiler.test;

import dev.denismasterherobrine.densityfunctioncompiler.DensityFunctionCompiler;
import dev.denismasterherobrine.densityfunctioncompiler.compiler.cache.GlobalCompileCache;
import dev.denismasterherobrine.densityfunctioncompiler.compiler.Compiler;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;

/**
 * In-process pipeline timing for DFC: complements {@code /dfc bench} (server + router
 * field) by isolating full compile (IR build + optimizer + codegen + link) and
 * global-cache hit paths. Logged to the mod logger; safe to run from
 * {@link GlobalClassCacheTest#verify()} or a dev run.
 */
public final class CompilerPipelineProfileTest {

    private CompilerPipelineProfileTest() {}

    public static void logToLogger() {
        DensityFunction yc = DensityFunctions.yClampedGradient(-64, 320, -1.0, 1.0);
        DensityFunction k1 = DensityFunctions.constant(1.0);
        DensityFunction shared = yc.abs();
        DensityFunction cseStress = DensityFunctions.add(
                DensityFunctions.mul(shared, shared),
                DensityFunctions.add(shared, k1));

        // Cold: each compile has a distinct constant so the global fingerprint
        // misses; measures full Codegen + defineHiddenClass + link (first call only).
        GlobalCompileCache.INSTANCE.clear();
        int coldTrials = 3;
        long coldSum = 0L;
        for (int t = 0; t < coldTrials; t++) {
            GlobalCompileCache.INSTANCE.clear();
            DensityFunction df = DensityFunctions.add(yc, DensityFunctions.constant(0.0001 * (t + 1)));
            long t0 = System.nanoTime();
            Compiler.compile(df);
            coldSum += System.nanoTime() - t0;
        }
        long coldMean = coldSum / coldTrials;

        // Warm: same tree many times; second and later compiles share the hidden class
        // (global cache hit) and should be orders of magnitude faster.
        DensityFunction warm = cseStress;
        Compiler.compile(warm);
        int warmIters = 200;
        long t0w = System.nanoTime();
        for (int i = 0; i < warmIters; i++) {
            Compiler.compile(warm);
        }
        long warmMean = (System.nanoTime() - t0w) / warmIters;

        DensityFunctionCompiler.LOGGER.info(
                "DFC pipeline profile: cold full compile (cache cleared, {} trials) mean {} ms; "
                        + "warm (global class cache hit, {} iters) mean {} ns/call",
                coldTrials, String.format("%.3f", coldMean / 1_000_000.0),
                warmIters, warmMean);
    }
}
