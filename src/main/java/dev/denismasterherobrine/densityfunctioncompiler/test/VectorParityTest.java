package dev.denismasterherobrine.densityfunctioncompiler.test;

import dev.denismasterherobrine.densityfunctioncompiler.DensityFunctionCompiler;
import dev.denismasterherobrine.densityfunctioncompiler.compiler.Compiler;
import dev.denismasterherobrine.densityfunctioncompiler.compiler.codegen.CompiledDensityFunction;
import dev.denismasterherobrine.densityfunctioncompiler.compiler.vector.DfcVectorSupport;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;

import java.util.Random;

/**
 * Probe + smoke test for {@link DfcVectorSupport} (Tier B7).
 *
 * <p>Vector vs scalar parity across JVM launches is a two-process check: {@link
 * DfcVectorSupport#AVAILABLE} is fixed at class init from whether {@code jdk.incubator.vector}
 * is on the module path. {@link dev.denismasterherobrine.densityfunctioncompiler.compiler.cache.CompilationFingerprint}
 * bakes that into the global class-cache key so scalar and vector launchers never share hidden classes.
 *
 * <p>What we do instead:
 *
 * <ol>
 *   <li><strong>Probe stability.</strong> Reading {@link DfcVectorSupport#AVAILABLE}
 *       and {@link DfcVectorSupport#PREFERRED_LANES} must never crash regardless of module state.</li>
 *   <li><strong>Lane count sanity.</strong> If {@link DfcVectorSupport#AVAILABLE}
 *       is true, {@link DfcVectorSupport#PREFERRED_LANES} must be a power of
 *       two between 2 and 16 inclusive — covering AVX2 (4), AVX-512 (8), and
 *       hypothetical AVX-1024 (16). Other values mean the species probe got
 *       garbage.</li>
 *   <li><strong>Codegen smoke.</strong> Compile a small arithmetic DF and verify {@code compute}
 *       stays bit-identical; lattice / native slab paths add SIMD only when {@code AVAILABLE} at codegen time.</li>
 * </ol>
 */
public final class VectorParityTest {

    private VectorParityTest() {}

    public static void verify() {
        verifyProbeNeverCrashes();
        verifyLaneCountSanity();
        verifyArithmeticCompileStability();
        DensityFunctionCompiler.LOGGER.info(
                "DFC vector parity: OK (available={}, preferred-lanes={})",
                DfcVectorSupport.AVAILABLE, DfcVectorSupport.PREFERRED_LANES);
    }

    /**
     * Reading {@link DfcVectorSupport#AVAILABLE} triggers the {@code <clinit>}
     * probe if it hasn't fired yet. Any uncaught exception here means we'd crash
     * on first {@code /dfc stats} call after server start, which is unacceptable.
     */
    private static void verifyProbeNeverCrashes() {
        try {
            boolean ignored = DfcVectorSupport.AVAILABLE;
            int alsoIgnored = DfcVectorSupport.PREFERRED_LANES;
            // Touching the species handle should also be safe even when the module is missing.
            var ignoredHandle = DfcVectorSupport.speciesHandle();
            if (DfcVectorSupport.AVAILABLE && ignoredHandle == null) {
                throw new AssertionError(
                        "DfcVectorSupport.AVAILABLE is true but speciesHandle() is null; "
                                + "the probe set AVAILABLE without binding the species MH");
            }
            if (!DfcVectorSupport.AVAILABLE && ignoredHandle != null) {
                throw new AssertionError(
                        "DfcVectorSupport.AVAILABLE is false but speciesHandle() is non-null; "
                                + "we'd leak a handle into a vector-disabled JVM");
            }
        } catch (Throwable t) {
            throw new AssertionError("DfcVectorSupport probe threw: " + t, t);
        }
    }

    /**
     * If the probe declared the Vector API available, the species had better
     * report a sane lane count. Anything outside [2, 4, 8, 16] would mean the
     * platform reported a non-standard double width and our SIMD codegen
     * (when added in B7's full implementation) would emit broken offsets.
     */
    private static void verifyLaneCountSanity() {
        if (!DfcVectorSupport.AVAILABLE) {
            if (DfcVectorSupport.PREFERRED_LANES != 0) {
                throw new AssertionError(
                        "PREFERRED_LANES should be 0 when AVAILABLE=false, got "
                                + DfcVectorSupport.PREFERRED_LANES);
            }
            return;
        }
        int lanes = DfcVectorSupport.PREFERRED_LANES;
        if (lanes != 2 && lanes != 4 && lanes != 8 && lanes != 16) {
            throw new AssertionError(
                    "PREFERRED_LANES out of expected range {2,4,8,16}: " + lanes);
        }
    }

    /**
     * Compile a small arithmetic-only DF and verify {@code compute} returns
     * stable, well-defined values. This is the bare-minimum smoke test for
     * the codegen path; the real per-DF parity work lives in
     * {@link ParitySelfTest} and {@code RouterParityCommand}.
     */
    private static void verifyArithmeticCompileStability() {
        DensityFunction poly = DensityFunctions.add(
                DensityFunctions.mul(
                        DensityFunctions.add(DensityFunctions.constant(0.5), DensityFunctions.zero()),
                        DensityFunctions.constant(2.0)),
                DensityFunctions.constant(-0.25));
        DensityFunction compiled = Compiler.compile(poly);
        if (!(compiled instanceof CompiledDensityFunction cdf)) {
            throw new AssertionError(
                    "expected CompiledDensityFunction, got " + compiled.getClass().getName());
        }
        Random rand = new Random(0x12345678L);
        for (int i = 0; i < 64; i++) {
            int x = rand.nextInt(-1024, 1025);
            int y = rand.nextInt(-64, 321);
            int z = rand.nextInt(-1024, 1025);
            double v = cdf.compute(new ConstCtx(x, y, z));
            // poly = (0.5 + 0) * 2.0 + (-0.25) = 0.75
            if (Double.compare(v, 0.75) != 0) {
                throw new AssertionError(
                        "compute returned " + v + " (expected 0.75) at "
                                + "x=" + x + " y=" + y + " z=" + z);
            }
        }
    }

    private record ConstCtx(int x, int y, int z) implements DensityFunction.FunctionContext {
        @Override public int blockX() { return x; }
        @Override public int blockY() { return y; }
        @Override public int blockZ() { return z; }
    }
}
