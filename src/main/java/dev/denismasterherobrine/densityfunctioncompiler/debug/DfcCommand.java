package dev.denismasterherobrine.densityfunctioncompiler.debug;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.denismasterherobrine.densityfunctioncompiler.compiler.pipeline.RouterPipeline;
import dev.denismasterherobrine.densityfunctioncompiler.test.ParitySelfTest;
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
                                    + "%d helpers emitted, %d optimizer rewrite passes")
                                    .formatted(stats.rootsCompiled(), stats.uniqueNodes(),
                                            stats.classesAlive(), stats.helpersEmitted(),
                                            stats.optimizerRewrites())),
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
                    var result = ParitySelfTest.runArithmeticSubset();
                    var color = result.failures().isEmpty() ? ChatFormatting.GREEN : ChatFormatting.RED;
                    src.sendSuccess(() -> Component.literal(
                            "DFC self-test: %d/%d cases passed".formatted(result.passed(), result.total()))
                            .withStyle(color), false);
                    for (String f : result.failures()) {
                        src.sendSuccess(() -> Component.literal("  fail: " + f).withStyle(ChatFormatting.RED), false);
                    }
                    return result.failures().isEmpty() ? 1 : 0;
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
