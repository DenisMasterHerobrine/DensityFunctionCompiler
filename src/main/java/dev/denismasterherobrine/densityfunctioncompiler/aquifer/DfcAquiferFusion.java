package dev.denismasterherobrine.densityfunctioncompiler.aquifer;

import dev.denismasterherobrine.densityfunctioncompiler.config.DfcConfig;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Option B: last-value cache keyed by (density function ref, block position) on the
 * current thread, matching repeated {@code df.compute(sameContext)} in a tight loop.
 * <p>Enabled only with {@link DfcConfig#experimentalAquiferFusion()}. Safe to leave off:
 * a wrong hit would be a parity bug; the single-entry cache only helps back-to-back
 * identical (df, x, y, z) calls.
 */
public final class DfcAquiferFusion {

    private static final ThreadLocal<Slot> SLOT = ThreadLocal.withInitial(Slot::new);

    private DfcAquiferFusion() {}

    public static double computeCached(DensityFunction df, DensityFunction.FunctionContext ctx) {
        if (!DfcConfig.experimentalAquiferFusion()) {
            return df.compute(ctx);
        }
        int x = ctx.blockX();
        int y = ctx.blockY();
        int z = ctx.blockZ();
        Slot s = SLOT.get();
        if (s.matches(df, x, y, z)) {
            return s.value;
        }
        double v = df.compute(ctx);
        s.set(df, x, y, z, v);
        return v;
    }

    private static final class Slot {
        DensityFunction df;
        int x;
        int y;
        int z;
        double value;
        boolean has;

        void set(DensityFunction df, int x, int y, int z, double value) {
            this.df = df;
            this.x = x;
            this.y = y;
            this.z = z;
            this.value = value;
            this.has = true;
        }

        boolean matches(DensityFunction df, int x, int y, int z) {
            return this.has
                    && this.df == df
                    && this.x == x
                    && this.y == y
                    && this.z == z;
        }
    }
}
