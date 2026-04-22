package dev.denismasterherobrine.densityfunctioncompiler.test;

import dev.denismasterherobrine.densityfunctioncompiler.cache.DfcCacheFastPath;
import dev.denismasterherobrine.densityfunctioncompiler.cache.DfcCellCacheAccess;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;

/**
 * Smoke tests for {@link DfcCacheFastPath#computeWithOptionalDirectRead}.
 */
public final class CacheFastPathTest {

    private CacheFastPathTest() {}

    public static void run() {
        hitPath();
        missFallsThrough();
    }

    private static void hitPath() {
        var ctx = new DensityFunction.SinglePointContext(1, 2, 3);
        DensityFunction plain = DensityFunctions.constant(99.0);
        var fast = new FastDf(42.0);
        fast.primeHit(ctx, 7.5);
        double r = DfcCacheFastPath.computeWithOptionalDirectRead(fast, ctx);
        if (Double.doubleToRawLongBits(r) != Double.doubleToRawLongBits(7.5)) {
            throw new AssertionError("expected direct read hit");
        }
        double s = DfcCacheFastPath.computeWithOptionalDirectRead(plain, ctx);
        if (Double.doubleToRawLongBits(s) != Double.doubleToRawLongBits(99.0)) {
            throw new AssertionError("expected full compute");
        }
    }

    private static void missFallsThrough() {
        var ctx = new DensityFunction.SinglePointContext(0, 0, 0);
        var fast = new FastDf(-1.0);
        double r = DfcCacheFastPath.computeWithOptionalDirectRead(fast, ctx);
        if (Double.doubleToRawLongBits(r) != Double.doubleToRawLongBits(-1.0)) {
            throw new AssertionError("expected compute fallback on cache miss");
        }
    }

    private static final class FastDf implements DensityFunction, DfcCellCacheAccess {
        private final double computeValue;
        private long lastPos = Long.MIN_VALUE;
        private double cached;

        FastDf(double computeValue) {
            this.computeValue = computeValue;
        }

        void primeHit(FunctionContext context, double v) {
            long p = (long) context.blockX() << 32 | (context.blockZ() & 0xffffffffL);
            this.lastPos = p;
            this.cached = v;
        }

        @Override
        public double compute(FunctionContext context) {
            return computeValue;
        }

        @Override
        public void fillArray(double[] array, ContextProvider contextProvider) {}

        @Override
        public DensityFunction mapAll(Visitor visitor) {
            return this;
        }

        @Override
        public double minValue() {
            return Math.min(computeValue, cached);
        }

        @Override
        public double maxValue() {
            return Math.max(computeValue, cached);
        }

        @Override
        public KeyDispatchDataCodec<? extends DensityFunction> codec() {
            throw new UnsupportedOperationException("DFC test double");
        }

        @Override
        public double dfc$tryDirectRead(FunctionContext context) {
            long p = (long) context.blockX() << 32 | (context.blockZ() & 0xffffffffL);
            if (p == lastPos) {
                return cached;
            }
            return DfcCacheFastPath.CACHE_MISS;
        }
    }
}
