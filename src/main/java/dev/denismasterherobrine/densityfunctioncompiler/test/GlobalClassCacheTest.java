package dev.denismasterherobrine.densityfunctioncompiler.test;

import dev.denismasterherobrine.densityfunctioncompiler.DensityFunctionCompiler;
import dev.denismasterherobrine.densityfunctioncompiler.compiler.cache.GlobalCompileCache;
import dev.denismasterherobrine.densityfunctioncompiler.compiler.Compiler;
import dev.denismasterherobrine.densityfunctioncompiler.compiler.codegen.CompiledDensityFunction;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;

/**
 * Verifies the Tier-5 global class cache: two compiles of the same IR+pool
 * fingerprint reuse the same hidden class, and a constant root fast-fill works.
 */
public final class GlobalClassCacheTest {

    private GlobalClassCacheTest() {}

    public static void verify() {
        GlobalCompileCache.INSTANCE.clear();
        int before = GlobalCompileCache.INSTANCE.size();
        long sharedBefore = GlobalCompileCache.INSTANCE.instancesShared();
        long savedBefore = GlobalCompileCache.INSTANCE.bytesSaved();

        DensityFunction a = Compiler.compile(DensityFunctions.constant(7.25));
        DensityFunction b = Compiler.compile(DensityFunctions.constant(7.25));
        if (a.getClass() != b.getClass()) {
            throw new AssertionError("expected one shared hidden class, got "
                    + a.getClass().getName() + " vs " + b.getClass().getName());
        }
        if (GlobalCompileCache.INSTANCE.size() != before + 1) {
            DensityFunctionCompiler.LOGGER.warn("DFC cache test: expected cache size {}+1, got {}",
                    before, GlobalCompileCache.INSTANCE.size());
        }
        long sharedAfter = GlobalCompileCache.INSTANCE.instancesShared();
        if (sharedAfter <= sharedBefore) {
            throw new AssertionError("instancesShared should have ticked up after the second compile, "
                    + "got " + sharedBefore + " -> " + sharedAfter);
        }
        long savedAfter = GlobalCompileCache.INSTANCE.bytesSaved();
        if (savedAfter <= savedBefore) {
            throw new AssertionError("bytesSaved should have grown after the second compile, "
                    + "got " + savedBefore + " -> " + savedAfter);
        }

        double[] out = new double[64];
        ((CompiledDensityFunction) a).fillArray(out, null);
        for (double v : out) {
            if (v != 7.25) {
                throw new AssertionError("expected 7.25 in every slot, got " + v);
            }
        }

        // Per-bundle structural sanity: classInternalName aligned with key, helper
        // handle array sized to helpersEmitted, no null helper / ctor handles, and
        // bytecode actually present. A non-ok report indicates somebody else (a
        // mod, a coremod, or a buggy reload pathway) is tampering with the cache.
        var report = GlobalCompileCache.INSTANCE.verifyConsistency();
        if (!report.ok()) {
            throw new AssertionError("GlobalCompileCache consistency check failed: " + report);
        }
        DensityFunctionCompiler.LOGGER.info(
                "DFC global class cache + const fast fill: OK (class={}, bundles={}, instancesShared={}, bytesSaved={})",
                a.getClass().getName(), report.bundlesChecked(),
                GlobalCompileCache.INSTANCE.instancesShared(),
                GlobalCompileCache.INSTANCE.bytesSaved());
    }
}
