package dev.denismasterherobrine.densityfunctioncompiler.debug;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.denismasterherobrine.densityfunctioncompiler.compiler.pipeline.RouterPipeline;
import dev.denismasterherobrine.densityfunctioncompiler.test.ParitySelfTest;
import dev.denismasterherobrine.densityfunctioncompiler.test.VanillaDensityFunctionCoverage;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * {@code /dfc} debug commands. Three subcommands:
 * <ul>
 *   <li>{@code /dfc stats} — aggregate compilation counters.</li>
 *   <li>{@code /dfc dump} — short summary written to chat.</li>
 *   <li>{@code /dfc dump &lt;noise_settings&gt; &lt;field&gt;} — IR / bytecode / parity
 *       sample for one router slot, written to the server log.</li>
 * </ul>
 */
public final class DfcCommand {
    private DfcCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("dfc")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("stats").executes(ctx -> {
                    var stats = RouterPipeline.snapshotStats();
                    ctx.getSource().sendSuccess(() -> Component.literal(
                            ("DFC: %d roots compiled, %d unique IR nodes, %d hidden classes alive, "
                                    + "%d helpers emitted, %d optimizer rewrite passes, "
                                    + "%d noises inlined (%d octaves unrolled)")
                                    .formatted(stats.rootsCompiled(), stats.uniqueNodes(),
                                            stats.classesAlive(), stats.helpersEmitted(),
                                            stats.optimizerRewrites(),
                                            stats.noisesInlined(), stats.octavesInlined())),
                            false);
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
