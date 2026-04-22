package dev.denismasterherobrine.densityfunctioncompiler.test;

import dev.denismasterherobrine.densityfunctioncompiler.DensityFunctionCompiler;
import dev.denismasterherobrine.densityfunctioncompiler.cache.DfcCacheFastPath;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;

/**
 * Invariants for {@link DfcCacheFastPath} and related helpers (driven from {@code /dfc cachetest}).
 */
public final class DfcRuntimeHelpersTest {

    private DfcRuntimeHelpersTest() {}

    public static void verify() {
        if (Double.doubleToRawLongBits(DfcCacheFastPath.CACHE_MISS) != DfcCacheFastPath.MISS_BITS) {
            throw new AssertionError("DfcCacheFastPath miss sentinel is not a stable distinct NaN");
        }
        // Non-wrapper externs always miss
        DensityFunction c = DensityFunctions.constant(0.0);
        var ctx = new DensityFunction.SinglePointContext(0, 0, 0);
        if (Double.doubleToRawLongBits(DfcCacheFastPath.tryWrapperDirectRead(c, ctx))
                != DfcCacheFastPath.MISS_BITS) {
            throw new AssertionError("tryWrapperDirectRead must miss for a plain constant pool extern");
        }
        DensityFunctionCompiler.LOGGER.info("DFC runtime helpers (cache miss NaN, wrapper try): OK");
    }
}
