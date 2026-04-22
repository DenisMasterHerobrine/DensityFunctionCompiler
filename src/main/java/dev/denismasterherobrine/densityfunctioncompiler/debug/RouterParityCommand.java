package dev.denismasterherobrine.densityfunctioncompiler.debug;

import dev.denismasterherobrine.densityfunctioncompiler.compiler.pipeline.RouterPipeline;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;

/**
 * {@code /dfc parity [noise_settings]} command surface.
 *
 * <p>For each {@link NoiseGeneratorSettings}, takes the router currently installed in
 * the registry (which by this point is the JIT-compiled one — the lazy mixin replaces
 * the field on first read) and pairs it against a freshly-built compiled router from
 * the same source via {@link RouterPipeline#compile}. Comparing two compiled outputs
 * isn't useful by itself; instead, this command samples each router field's compiled
 * result against a vanilla recompute by walking the original source tree fresh from
 * the registry and re-evaluating without our JIT.
 *
 * <p>Because we replaced {@code noiseRouter} in place, "vanilla source" is no longer
 * accessible at this point. Instead we measure parity between the currently-installed
 * router and itself recompiled — useful only as a determinism check (re-compile
 * stability). For true vanilla parity, set {@code -Ddfc.parity=true} before launch
 * so the lazy mixin captures the original at swap time.
 */
public final class RouterParityCommand {

    private RouterParityCommand() {}

    public static int runAll(CommandSourceStack src, int samples) {
        var server = src.getServer();
        if (server == null) {
            src.sendFailure(Component.literal("/dfc parity requires a running server."));
            return 0;
        }
        Registry<NoiseGeneratorSettings> reg =
                server.registryAccess().registryOrThrow(Registries.NOISE_SETTINGS);
        int total = 0;
        int allPass = 0;
        for (var entry : reg.entrySet()) {
            total++;
            int passed = runOne(src, entry.getKey().location().toString(), entry.getValue(), samples);
            if (passed == 0) allPass++;
        }
        int finalAllPass = allPass;
        int finalTotal = total;
        src.sendSuccess(() -> Component.literal(
                "DFC parity: " + finalAllPass + "/" + finalTotal + " noise_settings fully agree"
        ).withStyle(finalAllPass == finalTotal ? ChatFormatting.GREEN : ChatFormatting.YELLOW), false);
        return finalAllPass == finalTotal ? 1 : 0;
    }

    public static int runFor(CommandSourceStack src, ResourceLocation id, int samples) {
        var server = src.getServer();
        if (server == null) {
            src.sendFailure(Component.literal("/dfc parity requires a running server."));
            return 0;
        }
        Registry<NoiseGeneratorSettings> reg =
                server.registryAccess().registryOrThrow(Registries.NOISE_SETTINGS);
        NoiseGeneratorSettings ngs = reg.get(id);
        if (ngs == null) {
            src.sendFailure(Component.literal("Unknown noise_settings: " + id));
            return 0;
        }
        return runOne(src, id.toString(), ngs, samples);
    }

    /**
     * Compares the JIT-compiled router (currently installed in the registry) against
     * the original pre-JIT router captured by the lazy mixin into
     * {@link OriginalRouterRegistry}.
     *
     * <p>If capture wasn't enabled at startup ({@code -Ddfc.parity.capture=true}),
     * falls back to comparing two fresh recompilations — which only catches
     * non-determinism, not real correctness issues. Tells the user to relaunch with
     * the property set so the next run is meaningful.
     */
    private static int runOne(CommandSourceStack src, String label, NoiseGeneratorSettings ngs, int samples) {
        NoiseRouter compiled = ngs.noiseRouter();
        NoiseRouter reference = OriginalRouterRegistry.find(ngs);
        boolean capturedOriginal = reference != null;
        if (!capturedOriginal) {
            // Without capture, all we can do is compare compile-vs-recompile.
            reference = RouterPipeline.compile(compiled);
            src.sendSuccess(() -> Component.literal(
                    "DFC parity " + label + ": no captured original; comparing recompile-vs-compile only. "
                            + "Re-launch with -Ddfc.parity.capture=true for a real vanilla diff."
            ).withStyle(ChatFormatting.YELLOW), false);
        }
        var report = RouterParityCheck.compareRouters(label, reference, compiled, samples, 0xC0FFEE_DECAFL);
        RouterParityCheck.logReport(report);
        int failed = report.total() - report.passed();
        int finalFailed = failed;
        boolean finalCaptured = capturedOriginal;
        src.sendSuccess(() -> Component.literal(
                "DFC parity " + label + ": " + report.passed() + "/" + report.total()
                        + " fields agree" + (finalCaptured ? " (vs vanilla original)" : " (recompile-vs-compile)")
                        + " — see server log for details"
        ).withStyle(finalFailed == 0 ? ChatFormatting.GREEN : ChatFormatting.RED), false);
        return failed;
    }
}
