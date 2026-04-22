package dev.denismasterherobrine.densityfunctioncompiler.debug;

import dev.denismasterherobrine.densityfunctioncompiler.compiler.Compiler;
import dev.denismasterherobrine.densityfunctioncompiler.compiler.pipeline.RouterPipeline;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import org.objectweb.asm.util.ASMifier;
import org.objectweb.asm.util.TraceClassVisitor;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Random;

/**
 * Implementation of {@code /dfc dump}. Prints overall stats, plus — when given a
 * {@code noise_settings} ID — the IR / bytecode / parity diff for one of its router
 * fields.
 */
public final class DfcDumper {

    private DfcDumper() {}

    /** Print high-level aggregate stats only. */
    public static void dumpAll(CommandSourceStack src) {
        var stats = RouterPipeline.snapshotStats();
        src.sendSuccess(() -> Component.literal(
                ("DFC dump: %d compiled roots, %d unique IR nodes, %d hidden classes alive, "
                        + "%d node references collapsed by CSE, %d helper methods emitted, "
                        + "%d optimizer rewrite passes, %d noises inlined (%d octaves unrolled)")
                        .formatted(stats.rootsCompiled(), stats.uniqueNodes(),
                                stats.classesAlive(), stats.savedByCse(),
                                stats.helpersEmitted(), stats.optimizerRewrites(),
                                stats.noisesInlined(), stats.octavesInlined())), false);
    }

    /**
     * Dump one router field of one {@code noise_settings} entry. {@code field} may be
     * {@code "finalDensity"}, {@code "temperature"}, etc; see {@link NoiseRouter}'s
     * record component names.
     */
    public static int dumpRouterField(CommandSourceStack src, ResourceLocation settingsId, String field) {
        var server = src.getServer();
        if (server == null) {
            src.sendFailure(Component.literal("/dfc dump requires a running server."));
            return 0;
        }
        Registry<NoiseGeneratorSettings> settings =
                server.registryAccess().registryOrThrow(Registries.NOISE_SETTINGS);
        NoiseGeneratorSettings ngs = settings.get(settingsId);
        if (ngs == null) {
            src.sendFailure(Component.literal("Unknown noise_settings: " + settingsId));
            return 0;
        }
        DensityFunction picked = pickField(ngs.noiseRouter(), field);
        if (picked == null) {
            src.sendFailure(Component.literal("Unknown router field: " + field));
            return 0;
        }

        Compiler.Result r = Compiler.compileWithDetail(picked);
        if (r == null) {
            src.sendFailure(Component.literal("Compilation failed for " + settingsId + "/" + field));
            return 0;
        }

        // 1. IR DAG
        String ir = IRPrinter.print(r.root(), r.refs(), r.pool());
        // 2. ASM disassembly
        StringWriter sw = new StringWriter();
        try {
            new org.objectweb.asm.ClassReader(r.bytecode()).accept(
                    new TraceClassVisitor(null, new ASMifier(), new PrintWriter(sw)),
                    org.objectweb.asm.ClassReader.SKIP_DEBUG | org.objectweb.asm.ClassReader.EXPAND_FRAMES);
        } catch (Throwable t) {
            sw.append("(asmifier failed: ").append(t.toString()).append(")");
        }
        // 3. Parity sample
        ParitySample par = sampleParity(picked, r.compiled(), 1024, 0xC0FFEEL);

        src.sendSuccess(() -> Component.literal(
                "DFC dump %s/%s — IR nodes: %d, CSE saved: %d, helpers: %d, range [%.4f, %.4f]"
                        .formatted(settingsId, field, r.uniqueNodes(), r.cseSavings(),
                                r.helpersEmitted(), r.minValue(), r.maxValue())), false);
        src.sendSuccess(() -> Component.literal(
                "Parity sample (1024 random points): max abs diff = %.3e, mean abs diff = %.3e"
                        .formatted(par.maxAbsDiff(), par.meanAbsDiff())), false);

        // The IR / ASM dumps go to the server log only — they're far too long for chat.
        org.slf4j.LoggerFactory.getLogger("DFC").info(
                "Dump for {}/{}\n--- IR ---\n{}\n--- ASMified bytecode ---\n{}",
                settingsId, field, ir, sw.toString());
        src.sendSuccess(() -> Component.literal(
                "Full IR + bytecode dump written to server log."), false);
        return 1;
    }

    private static DensityFunction pickField(NoiseRouter r, String name) {
        return switch (name) {
            case "barrier" -> r.barrierNoise();
            case "fluidLevelFloodedness" -> r.fluidLevelFloodednessNoise();
            case "fluidLevelSpread" -> r.fluidLevelSpreadNoise();
            case "lava" -> r.lavaNoise();
            case "temperature" -> r.temperature();
            case "vegetation" -> r.vegetation();
            case "continents" -> r.continents();
            case "erosion" -> r.erosion();
            case "depth" -> r.depth();
            case "ridges" -> r.ridges();
            case "initialDensityWithoutJaggedness" -> r.initialDensityWithoutJaggedness();
            case "finalDensity" -> r.finalDensity();
            case "veinToggle" -> r.veinToggle();
            case "veinRidged" -> r.veinRidged();
            case "veinGap" -> r.veinGap();
            default -> null;
        };
    }

    public record ParitySample(double maxAbsDiff, double meanAbsDiff, int samples) {}

    public record BenchResult(double nsPerCallVanilla, double nsPerCallCompiled, double speedup) {}

    /**
     * Crude in-game microbench: measures the average time per {@code compute(ctx)} call
     * for both the original and the compiled variant of one noise_settings router field.
     * Not JMH-grade but good enough to spot order-of-magnitude regressions.
     */
    public static int benchRouterField(CommandSourceStack src, ResourceLocation settingsId, String field) {
        var server = src.getServer();
        if (server == null) {
            src.sendFailure(Component.literal("/dfc bench requires a running server."));
            return 0;
        }
        Registry<NoiseGeneratorSettings> settings =
                server.registryAccess().registryOrThrow(Registries.NOISE_SETTINGS);
        NoiseGeneratorSettings ngs = settings.get(settingsId);
        if (ngs == null) {
            src.sendFailure(Component.literal("Unknown noise_settings: " + settingsId));
            return 0;
        }
        DensityFunction picked = pickField(ngs.noiseRouter(), field);
        if (picked == null) {
            src.sendFailure(Component.literal("Unknown router field: " + field));
            return 0;
        }
        DensityFunction compiled = Compiler.compile(picked);
        if (compiled == picked) {
            src.sendFailure(Component.literal("Compilation declined for this DF."));
            return 0;
        }

        BenchResult br = bench(picked, compiled, 200_000);
        src.sendSuccess(() -> Component.literal(
                "DFC bench %s/%s : vanilla=%.1f ns/call, compiled=%.1f ns/call, speedup=%.2fx"
                        .formatted(settingsId, field, br.nsPerCallVanilla(), br.nsPerCallCompiled(), br.speedup())),
                false);
        return 1;
    }

    public static BenchResult bench(DensityFunction original, DensityFunction compiled, int callsPerPhase) {
        Random rnd = new Random(0xBAD_C0FFEEL);
        DensityFunction.SinglePointContext[] ctxs = new DensityFunction.SinglePointContext[1024];
        for (int i = 0; i < ctxs.length; i++) {
            ctxs[i] = new DensityFunction.SinglePointContext(
                    rnd.nextInt(8192) - 4096, rnd.nextInt(384) - 64, rnd.nextInt(8192) - 4096);
        }

        // Warm up both — JIT needs samples before it inlines.
        warmup(original, ctxs, 50_000);
        warmup(compiled, ctxs, 50_000);

        long tA = timeIt(original, ctxs, callsPerPhase);
        long tB = timeIt(compiled, ctxs, callsPerPhase);

        double nsA = tA / (double) callsPerPhase;
        double nsB = tB / (double) callsPerPhase;
        return new BenchResult(nsA, nsB, nsA / nsB);
    }

    private static void warmup(DensityFunction df, DensityFunction.SinglePointContext[] ctxs, int n) {
        double sink = 0;
        for (int i = 0; i < n; i++) {
            sink += df.compute(ctxs[i & (ctxs.length - 1)]);
        }
        if (sink == 1234.5678) System.out.println("unreachable"); // prevent DCE
    }

    private static long timeIt(DensityFunction df, DensityFunction.SinglePointContext[] ctxs, int n) {
        double sink = 0;
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            sink += df.compute(ctxs[i & (ctxs.length - 1)]);
        }
        long t1 = System.nanoTime();
        if (sink == 1234.5678) System.out.println("unreachable");
        return t1 - t0;
    }

    public static ParitySample sampleParity(DensityFunction original, DensityFunction compiled,
                                            int samples, long seed) {
        Random rnd = new Random(seed);
        double maxDiff = 0;
        double sumDiff = 0;
        int n = 0;
        for (int i = 0; i < samples; i++) {
            int x = rnd.nextInt(8192) - 4096;
            int y = rnd.nextInt(384) - 64;
            int z = rnd.nextInt(8192) - 4096;
            DensityFunction.SinglePointContext ctx = new DensityFunction.SinglePointContext(x, y, z);
            double a, b;
            try {
                a = original.compute(ctx);
                b = compiled.compute(ctx);
            } catch (Throwable t) {
                continue;
            }
            if (Double.isNaN(a) && Double.isNaN(b)) continue;
            double d = Math.abs(a - b);
            if (Double.isNaN(d) || Double.isInfinite(d)) continue;
            if (d > maxDiff) maxDiff = d;
            sumDiff += d;
            n++;
        }
        return new ParitySample(maxDiff, n > 0 ? sumDiff / n : Double.NaN, n);
    }
}
