package dev.denismasterherobrine.densityfunctioncompiler.debug;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.denismasterherobrine.densityfunctioncompiler.DensityFunctionCompiler;
import dev.denismasterherobrine.densityfunctioncompiler.compiler.pipeline.RouterPipeline;
import dev.denismasterherobrine.densityfunctioncompiler.test.DfcRuntimeHelpersTest;
import dev.denismasterherobrine.densityfunctioncompiler.test.CoordDepTest;
import dev.denismasterherobrine.densityfunctioncompiler.test.GlobalClassCacheTest;
import dev.denismasterherobrine.densityfunctioncompiler.test.LatticePlanTest;
import dev.denismasterherobrine.densityfunctioncompiler.test.MapAllSessionTest;
import dev.denismasterherobrine.densityfunctioncompiler.test.ParitySelfTest;
import dev.denismasterherobrine.densityfunctioncompiler.test.VanillaDensityFunctionCoverage;
import dev.denismasterherobrine.densityfunctioncompiler.test.VectorParityTest;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * {@code /dfc} debug commands. Three subcommands:
 * <ul>
 *   <li>{@code /dfc stats} — aggregate compilation counters (incl. global class cache).</li>
 *   <li>{@code /dfc cachetest} — Tier 4/5 sanity (class cache + {@code CoordDep}).</li>
 *   <li>{@code /dfc dump} — short summary written to chat.</li>
 *   <li>{@code /dfc dump &lt;noise_settings&gt; &lt;field&gt;} — IR / bytecode / parity
 *       sample for one router slot, written to the server log.</li>
 * </ul>
 */
public final class DfcCommand {
    private DfcCommand() {}

    /**
     * Render a byte count in the smallest human unit that keeps the magnitude
     * under three digits — {@code 412 B} / {@code 38.4 KiB} / {@code 1.4 MiB}.
     * Used for the class-cache "bytes saved" display on {@code /dfc stats}.
     */
    private static String humanBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double k = bytes / 1024.0;
        if (k < 1024) return String.format("%.1f KiB", k);
        double m = k / 1024.0;
        if (m < 1024) return String.format("%.1f MiB", m);
        return String.format("%.1f GiB", m / 1024.0);
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("dfc")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("stats").executes(ctx -> {
                    var stats = RouterPipeline.snapshotStats();
                    double noiseRate = RouterPipeline.noiseInlineRate();
                    double blendedRate = RouterPipeline.blendedInlineRate();
                    var src = ctx.getSource();
                    src.sendSuccess(() -> Component.literal(
                            ("DFC: %d roots compiled, %d unique IR nodes, %d hidden classes alive, "
                                    + "%d helpers emitted, %d optimizer rewrite passes, "
                                    + "%d noises inlined (%d octaves unrolled), "
                                    + "%d blended roots (%d blended octaves in bytecode), "
                                    + "global class cache: %d hits, %d codegen misses")
                                    .formatted(stats.rootsCompiled(), stats.uniqueNodes(),
                                            stats.classesAlive(), stats.helpersEmitted(),
                                            stats.optimizerRewrites(),
                                            stats.noisesInlined(), stats.octavesInlined(),
                                            stats.blendedInlined(), stats.blendedOctavesEmitted(),
                                            stats.globalClassCacheHits(), stats.globalCodegenCacheMisses())),
                            false);
                    src.sendSuccess(() -> Component.literal(
                            ("DFC inline: noise %.1f%% (%d failures), blended %.1f%% (%d failures), "
                                    + "%d octaves skipped; class cache saved ~%s bytecode "
                                    + "across %d shared instances")
                                    .formatted(noiseRate * 100.0, stats.noiseMixinFailures(),
                                            blendedRate * 100.0, stats.blendedMixinFailures(),
                                            stats.octavesSkipped(),
                                            humanBytes(stats.globalClassCacheBytesSaved()),
                                            stats.globalClassCacheInstancesShared())),
                            false);
                    long totalLatticeRoots = stats.latticePlansEmitted() + stats.latticeFallbacks();
                    double latticeRate = totalLatticeRoots == 0 ? 0.0
                            : (stats.latticePlansEmitted() * 100.0) / totalLatticeRoots;
                    boolean vectorOn = dev.denismasterherobrine.densityfunctioncompiler
                            .compiler.vector.DfcVectorSupport.AVAILABLE;
                    int vectorLanes = dev.denismasterherobrine.densityfunctioncompiler
                            .compiler.vector.DfcVectorSupport.PREFERRED_LANES;
                    src.sendSuccess(() -> Component.literal(
                            ("DFC fast paths: cell-lattice %d / %d roots (%.1f%%), "
                                    + "vector API %s%s")
                                    .formatted(stats.latticePlansEmitted(), totalLatticeRoots,
                                            latticeRate,
                                            vectorOn ? "enabled" : "disabled",
                                            vectorOn ? " (preferred " + vectorLanes + " lanes)" : "")),
                            false);
                    return 1;
                }))
                .then(Commands.literal("cachetest").executes(ctx -> {
                    try {
                        GlobalClassCacheTest.verify();
                        CoordDepTest.verify();
                        MapAllSessionTest.verify();
                        DfcRuntimeHelpersTest.verify();
                        LatticePlanTest.verify();
                        VectorParityTest.verify();
                        ctx.getSource().sendSuccess(
                                () -> Component.literal("DFC: global class cache + CoordDep + MapAllSession "
                                        + "+ runtime helpers + LatticePlan + VectorParity: OK"), false);
                    } catch (Throwable t) {
                        DensityFunctionCompiler.LOGGER.error("DFC cachetest failed", t);
                        ctx.getSource().sendFailure(Component.literal("cachetest: " + t.getMessage()));
                        return 0;
                    }
                    return 1;
                }))
                .then(Commands.literal("dump")
                        .executes(ctx -> {
                            DfcDumper.dumpAll(ctx.getSource());
                            return 1;
                        })
                        .then(Commands.argument("noise_settings", ResourceLocationArgument.id())
                                .then(Commands.argument("field", StringArgumentType.string())
                                        .executes(ctx -> {
                                            ResourceLocation id = ResourceLocationArgument.getId(ctx, "noise_settings");
                                            String field = StringArgumentType.getString(ctx, "field");
                                            return DfcDumper.dumpRouterField(ctx.getSource(), id, field);
                                        }))))
                .then(Commands.literal("bench")
                        .then(Commands.argument("noise_settings", ResourceLocationArgument.id())
                                .then(Commands.argument("field", StringArgumentType.string())
                                        .executes(ctx -> {
                                            ResourceLocation id = ResourceLocationArgument.getId(ctx, "noise_settings");
                                            String field = StringArgumentType.getString(ctx, "field");
                                            return DfcDumper.benchRouterField(ctx.getSource(), id, field);
                                        }))))
                .then(Commands.literal("selftest").executes(ctx -> {
                    var src = ctx.getSource();
                    src.sendSuccess(() -> Component.literal("DFC: running parity self-test..."), false);
                    var arith = ParitySelfTest.runArithmeticSubset();
                    var aColor = arith.failures().isEmpty() ? ChatFormatting.GREEN : ChatFormatting.RED;
                    src.sendSuccess(() -> Component.literal(
                            "DFC arithmetic self-test: %d/%d cases passed".formatted(arith.passed(), arith.total()))
                            .withStyle(aColor), false);
                    for (String f : arith.failures()) {
                        src.sendSuccess(() -> Component.literal("  fail: " + f).withStyle(ChatFormatting.RED), false);
                    }
                    var noise = ParitySelfTest.runNoiseSubset(src);
                    var nColor = noise.failures().isEmpty() ? ChatFormatting.GREEN : ChatFormatting.RED;
                    src.sendSuccess(() -> Component.literal(
                            "DFC noise self-test: %d/%d cases passed".formatted(noise.passed(), noise.total()))
                            .withStyle(nColor), false);
                    for (String f : noise.failures()) {
                        src.sendSuccess(() -> Component.literal("  noise fail: " + f).withStyle(ChatFormatting.RED), false);
                    }
                    var blended = ParitySelfTest.runBlendedNoiseParity();
                    var bColor = blended.failures().isEmpty() ? ChatFormatting.GREEN : ChatFormatting.RED;
                    src.sendSuccess(() -> Component.literal(
                            "DFC BlendedNoise bit-parity: %d/%d cases passed".formatted(blended.passed(), blended.total()))
                            .withStyle(bColor), false);
                    for (String f : blended.failures()) {
                        src.sendSuccess(() -> Component.literal("  blended fail: " + f).withStyle(ChatFormatting.RED), false);
                    }
                    var facCov = VanillaDensityFunctionCoverage.runFactoryBattery();
                    var fc = facCov.failures().isEmpty() ? ChatFormatting.GREEN : ChatFormatting.RED;
                    src.sendSuccess(() -> Component.literal(
                            "DFC factory IR invoke audit: %d/%d".formatted(facCov.passed(), facCov.casesRun()))
                            .withStyle(fc), false);
                    for (String f : facCov.failures()) {
                        src.sendSuccess(() -> Component.literal("  coverage fail: " + f).withStyle(ChatFormatting.RED), false);
                    }
                    var regCov = ParitySelfTest.runRegistryInvokeCoverage(ctx.getSource());
                    var rc = regCov.failures().isEmpty() ? ChatFormatting.GREEN : ChatFormatting.RED;
                    src.sendSuccess(() -> Component.literal(
                            "DFC registry IR invoke audit: %d/%d".formatted(regCov.passed(), regCov.casesRun()))
                            .withStyle(rc), false);
                    for (String f : regCov.failures()) {
                        src.sendSuccess(() -> Component.literal("  registry fail: " + f).withStyle(ChatFormatting.RED), false);
                    }
                    boolean allOk = arith.failures().isEmpty() && noise.failures().isEmpty()
                            && blended.failures().isEmpty()
                            && facCov.failures().isEmpty() && regCov.failures().isEmpty();
                    return allOk ? 1 : 0;
                }))
                .then(Commands.literal("parity")
                        .executes(ctx -> RouterParityCommand.runAll(ctx.getSource(), 1024))
                        .then(Commands.argument("noise_settings", ResourceLocationArgument.id())
                                .executes(ctx -> {
                                    ResourceLocation id = ResourceLocationArgument.getId(ctx, "noise_settings");
                                    return RouterParityCommand.runFor(ctx.getSource(), id, 1024);
                                })));

        dispatcher.register(root);
    }
}
