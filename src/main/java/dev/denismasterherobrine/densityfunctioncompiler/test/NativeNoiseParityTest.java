package dev.denismasterherobrine.densityfunctioncompiler.test;

import dev.denismasterherobrine.densityfunctioncompiler.DensityFunctionCompiler;
import dev.denismasterherobrine.densityfunctioncompiler.mixin.noise.ImprovedNoiseAccessor;
import dev.denismasterherobrine.dfcnatives.DfcNativeBridge;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;

/**
 * Compares vanilla {@link ImprovedNoise#noise} to {@code dfc-natives} for a single-octave NormalNoise stack
 * and exercises the batch API. Skips when the native library is not bundled.
 */
public final class NativeNoiseParityTest {

    private NativeNoiseParityTest() {}

    public static void verify() {
        if (!DfcNativeBridge.isAvailable()) {
            DensityFunctionCompiler.LOGGER.info("NativeNoiseParityTest: skipped (dfc-natives not loaded)");
            return;
        }
        RandomSource rnd = RandomSource.create(42L);
        ImprovedNoise in = new ImprovedNoise(rnd);
        ImprovedNoiseAccessor acc = (ImprovedNoiseAccessor) (Object) in;
        byte[] perm = acc.dfc$getPermutation();
        double[] orig = new double[] { acc.dfc$getXo(), acc.dfc$getYo(), acc.dfc$getZo() };

        long h = DfcNativeBridge.allocNormalNoiseStack(
                1.0,
                1,
                1.0,
                new double[] { 1.0 },
                new double[] { 1.0 },
                perm,
                orig,
                0,
                1.0,
                new double[0],
                new double[0],
                new byte[0],
                new double[0]);
        if (h == 0L) {
            throw new AssertionError("native alloc failed");
        }
        try {
            for (int i = 0; i < 64; i++) {
                double x = rnd.nextDouble() * 500 - 250;
                double y = rnd.nextDouble() * 500 - 250;
                double z = rnd.nextDouble() * 500 - 250;
                double jv = in.noise(x, y, z);
                double nv = DfcNativeBridge.normalNoiseStackSample1(h, x, y, z);
                double d = Math.abs(jv - nv);
                if (d > 1e-9 && d > Math.ulp(jv) * 8) {
                    throw new AssertionError("noise mismatch at (" + x + "," + y + "," + z + "): java=" + jv + " native=" + nv);
                }
            }
            int n = 16;
            double[] xs = new double[n];
            double[] ys = new double[n];
            double[] zs = new double[n];
            double[] outs = new double[n];
            for (int i = 0; i < n; i++) {
                xs[i] = rnd.nextDouble() * 100;
                ys[i] = rnd.nextDouble() * 100;
                zs[i] = rnd.nextDouble() * 100;
            }
            DfcNativeBridge.normalNoiseStackBatch(h, xs, ys, zs, outs, n, DfcNativeBridge.useAvx2Path());
            for (int i = 0; i < n; i++) {
                double e = DfcNativeBridge.normalNoiseStackSample1(h, xs[i], ys[i], zs[i]);
                if (outs[i] != e) {
                    double d = Math.abs(outs[i] - e);
                    if (d > 1e-9) {
                        throw new AssertionError("batch mismatch at " + i);
                    }
                }
            }
        } finally {
            DfcNativeBridge.releaseNormalNoiseStack(h);
        }
        DensityFunctionCompiler.LOGGER.info("NativeNoiseParityTest: OK (single-octave + batch)");
    }
}
