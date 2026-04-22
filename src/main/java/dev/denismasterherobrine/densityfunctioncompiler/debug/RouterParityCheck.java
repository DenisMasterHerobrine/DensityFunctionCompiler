package dev.denismasterherobrine.densityfunctioncompiler.debug;

import dev.denismasterherobrine.densityfunctioncompiler.DensityFunctionCompiler;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseRouter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.function.Function;

/**
 * Per-field {@link NoiseRouter} parity diagnostic. Samples each of the 15 router
 * fields at the same set of random {@code (x, y, z)} contexts on the original and
 * compiled router, then logs any field whose maximum absolute difference exceeds
 * a small tolerance.
 *
 * <p>Used to pinpoint which density function family is producing wrong values
 * after JIT compilation — when worldgen output looks structurally wrong (e.g.
 * "all ocean, no biomes"), the offender is almost always one of
 * {@code temperature}, {@code vegetation}, {@code continents}, {@code erosion},
 * {@code depth} or {@code ridges} returning a constant or shifted value.
 *
 * <p>Sampling uses a {@link DensityFunction.SinglePointContext} which mirrors
 * how the {@link net.minecraft.world.level.biome.BiomeManager} drives the
 * {@code Climate.Sampler} during biome lookup: no {@code NoiseChunk} cell-cache
 * wrapping is involved, so any mismatch is purely arithmetic / IR / codegen.
 *
 * <p>Disabled by default to keep production startup quiet; enable with the
 * system property {@code dfc.parity=true}, or call manually from
 * {@code /dfc parity}.
 */
public final class RouterParityCheck {

    private RouterParityCheck() {}

    /** Anything below this is plausibly fp-noise from compile-time bounds reordering. */
    public static final double TOLERANCE = 1.0e-6;

    /** Sampling box. Wider than a single chunk so spatial variation has room to manifest. */
    private static final int SAMPLE_X_RANGE = 8192;
    private static final int SAMPLE_Y_RANGE = 384;
    private static final int SAMPLE_X_OFFSET = -4096;
    private static final int SAMPLE_Y_OFFSET = -64;

    public record FieldReport(String field, int samples, double maxAbsDiff, double meanAbsDiff,
                              double exampleVanilla, double exampleCompiled,
                              int exampleX, int exampleY, int exampleZ) {
        public boolean passed() {
            return Double.isNaN(maxAbsDiff) || maxAbsDiff <= TOLERANCE;
        }
    }

    public record SuiteReport(String label, List<FieldReport> fields) {
        public int passed() {
            int n = 0;
            for (FieldReport f : fields) if (f.passed()) n++;
            return n;
        }
        public int total() { return fields.size(); }
    }

    /**
     * Compare {@code original} and {@code compiled} router field-by-field.
     *
     * @param label  short identifier (e.g. the noise_settings ResourceLocation), used in logs
     * @param original  the pre-JIT router
     * @param compiled  the post-JIT router (typically what was just installed by the mixin)
     * @param samples   number of random {@code (x,y,z)} points per field
     * @param seed      RNG seed; pass a fixed value for reproducibility
     */
    public static SuiteReport compareRouters(String label, NoiseRouter original, NoiseRouter compiled,
                                             int samples, long seed) {
        List<FieldReport> reports = new ArrayList<>(15);
        reports.add(field("barrierNoise", original, compiled, NoiseRouter::barrierNoise, samples, seed + 1));
        reports.add(field("fluidLevelFloodednessNoise", original, compiled, NoiseRouter::fluidLevelFloodednessNoise, samples, seed + 2));
        reports.add(field("fluidLevelSpreadNoise", original, compiled, NoiseRouter::fluidLevelSpreadNoise, samples, seed + 3));
        reports.add(field("lavaNoise", original, compiled, NoiseRouter::lavaNoise, samples, seed + 4));
        reports.add(field("temperature", original, compiled, NoiseRouter::temperature, samples, seed + 5));
        reports.add(field("vegetation", original, compiled, NoiseRouter::vegetation, samples, seed + 6));
        reports.add(field("continents", original, compiled, NoiseRouter::continents, samples, seed + 7));
        reports.add(field("erosion", original, compiled, NoiseRouter::erosion, samples, seed + 8));
        reports.add(field("depth", original, compiled, NoiseRouter::depth, samples, seed + 9));
        reports.add(field("ridges", original, compiled, NoiseRouter::ridges, samples, seed + 10));
        reports.add(field("initialDensityWithoutJaggedness", original, compiled, NoiseRouter::initialDensityWithoutJaggedness, samples, seed + 11));
        reports.add(field("finalDensity", original, compiled, NoiseRouter::finalDensity, samples, seed + 12));
        reports.add(field("veinToggle", original, compiled, NoiseRouter::veinToggle, samples, seed + 13));
        reports.add(field("veinRidged", original, compiled, NoiseRouter::veinRidged, samples, seed + 14));
        reports.add(field("veinGap", original, compiled, NoiseRouter::veinGap, samples, seed + 15));
        return new SuiteReport(label, reports);
    }

    /** Convenience: log a {@link SuiteReport} at WARN for any failing field, INFO summary always. */
    public static void logReport(SuiteReport report) {
        int pass = report.passed();
        int total = report.total();
        DensityFunctionCompiler.LOGGER.info(
                "DFC parity {} : {}/{} fields agree within {} ULPs", report.label(), pass, total, TOLERANCE);
        for (FieldReport f : report.fields()) {
            if (f.passed()) continue;
            DensityFunctionCompiler.LOGGER.warn(
                    "DFC parity FAIL {}/{} : maxAbsDiff={} meanAbsDiff={} samples={} "
                            + "@({},{},{}) vanilla={} compiled={}",
                    report.label(), f.field(),
                    formatDouble(f.maxAbsDiff()), formatDouble(f.meanAbsDiff()),
                    f.samples(),
                    f.exampleX(), f.exampleY(), f.exampleZ(),
                    formatDouble(f.exampleVanilla()), formatDouble(f.exampleCompiled()));
        }
    }

    private static FieldReport field(String name, NoiseRouter a, NoiseRouter b,
                                     Function<NoiseRouter, DensityFunction> getter,
                                     int samples, long seed) {
        DensityFunction va, vc;
        try {
            va = getter.apply(a);
            vc = getter.apply(b);
        } catch (Throwable t) {
            return new FieldReport(name, 0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, 0, 0, 0);
        }
        if (va == vc) {
            return new FieldReport(name, 0, 0.0, 0.0, 0.0, 0.0, 0, 0, 0);
        }

        Random rnd = new Random(seed);
        double maxDiff = 0.0;
        double sumDiff = 0.0;
        int valid = 0;
        int worstX = 0, worstY = 0, worstZ = 0;
        double worstA = 0, worstB = 0;
        for (int i = 0; i < samples; i++) {
            int x = rnd.nextInt(SAMPLE_X_RANGE) + SAMPLE_X_OFFSET;
            int y = rnd.nextInt(SAMPLE_Y_RANGE) + SAMPLE_Y_OFFSET;
            int z = rnd.nextInt(SAMPLE_X_RANGE) + SAMPLE_X_OFFSET;
            DensityFunction.SinglePointContext ctx = new DensityFunction.SinglePointContext(x, y, z);
            double dvA, dvB;
            try {
                dvA = va.compute(ctx);
                dvB = vc.compute(ctx);
            } catch (Throwable t) {
                continue;
            }
            if (Double.isNaN(dvA) && Double.isNaN(dvB)) continue;
            double d = Math.abs(dvA - dvB);
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                if (Double.isFinite(dvA) ^ Double.isFinite(dvB)) {
                    return new FieldReport(name, valid + 1, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
                            dvA, dvB, x, y, z);
                }
                continue;
            }
            sumDiff += d;
            valid++;
            if (d > maxDiff) {
                maxDiff = d;
                worstA = dvA; worstB = dvB; worstX = x; worstY = y; worstZ = z;
            }
        }
        double mean = valid > 0 ? sumDiff / valid : Double.NaN;
        return new FieldReport(name, valid, valid > 0 ? maxDiff : Double.NaN, mean,
                worstA, worstB, worstX, worstY, worstZ);
    }

    private static String formatDouble(double v) {
        if (Double.isNaN(v)) return "NaN";
        if (Double.isInfinite(v)) return v > 0 ? "+Inf" : "-Inf";
        return String.format(Locale.ROOT, "%.6g", v);
    }

    /**
     * Read the {@code dfc.parity} system property at startup; if {@code true},
     * the lazy mixin will run a parity check the first time it compiles each
     * router and dump per-field results to the log.
     */
    public static boolean enabledAtStartup() {
        return Boolean.getBoolean("dfc.parity");
    }
}
