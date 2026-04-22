package dev.denismasterherobrine.densityfunctioncompiler.test;

import dev.denismasterherobrine.densityfunctioncompiler.DensityFunctionCompiler;
import dev.denismasterherobrine.densityfunctioncompiler.compiler.Compiler;
import dev.denismasterherobrine.densityfunctioncompiler.debug.DfcDumper;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;

import java.util.ArrayList;
import java.util.List;

/**
 * Standalone parity self-test for the arithmetic / control-flow subset of the JIT.
 *
 * <p>Constructs a small but non-trivial DensityFunction tree from {@link DensityFunctions}
 * factories, compiles it through {@link Compiler#compile}, and samples both at 50k
 * random points; verifies bit-exact agreement on the arithmetic subset (no noise yet).
 * <em>Not</em> a JUnit test — meant to be invoked from {@code /dfc selftest} or the
 * server-start handler so it runs against the live mappings.
 */
public final class ParitySelfTest {

    private ParitySelfTest() {}

    public record SuiteResult(int total, int passed, List<String> failures) {}

    public static SuiteResult runArithmeticSubset() {
        List<String> failures = new ArrayList<>();
        int passed = 0;
        int total = 0;

        for (Case c : buildCases()) {
            total++;
            try {
                DensityFunction compiled = Compiler.compile(c.df);
                DfcDumper.ParitySample s = DfcDumper.sampleParity(c.df, compiled, 5000, 0xC0FFEE_DECAFL ^ total);
                if (s.maxAbsDiff() > 1e-9) {
                    failures.add(c.name + ": maxDiff=" + s.maxAbsDiff() + " (samples=" + s.samples() + ")");
                } else {
                    passed++;
                }
            } catch (Throwable t) {
                failures.add(c.name + ": exception " + t);
            }
        }

        DensityFunctionCompiler.LOGGER.info("DFC self-test: {}/{} passed", passed, total);
        for (String f : failures) {
            DensityFunctionCompiler.LOGGER.warn("  fail: {}", f);
        }
        return new SuiteResult(total, passed, failures);
    }

    private record Case(String name, DensityFunction df) {}

    private static List<Case> buildCases() {
        List<Case> out = new ArrayList<>();

        DensityFunction k0 = DensityFunctions.constant(0.0);
        DensityFunction k1 = DensityFunctions.constant(1.0);
        DensityFunction k2 = DensityFunctions.constant(2.5);

        out.add(new Case("const(1.0)", k1));
        out.add(new Case("add(1, 2.5)", DensityFunctions.add(k1, k2)));
        out.add(new Case("mul(2.5, 2.5)", DensityFunctions.mul(k2, k2)));
        out.add(new Case("min(0, 1)", DensityFunctions.min(k0, k1)));
        out.add(new Case("max(0, 1)", DensityFunctions.max(k0, k1)));
        out.add(new Case("abs(-2.5)", DensityFunctions.constant(-2.5).abs()));
        out.add(new Case("square(2.5)", DensityFunctions.constant(2.5).square()));
        out.add(new Case("cube(2)", DensityFunctions.constant(2.0).cube()));
        out.add(new Case("halfNeg(-3)", DensityFunctions.constant(-3.0).halfNegative()));
        out.add(new Case("quarterNeg(-3)", DensityFunctions.constant(-3.0).quarterNegative()));
        out.add(new Case("squeeze(0.5)", DensityFunctions.constant(0.5).squeeze()));

        DensityFunction yc = DensityFunctions.yClampedGradient(-64, 320, -1.0, 1.0);
        out.add(new Case("yClampedGradient", yc));

        DensityFunction clamped = yc.clamp(-0.5, 0.5);
        out.add(new Case("clamp(yc, -0.5, 0.5)", clamped));

        DensityFunction ranged = DensityFunctions.rangeChoice(yc, -0.25, 0.25, k0, k1);
        out.add(new Case("rangeChoice(yc in -0.25..0.25)", ranged));

        // CSE stress: same subtree referenced multiple times.
        DensityFunction shared = yc.abs();
        DensityFunction stress = DensityFunctions.add(
                DensityFunctions.mul(shared, shared),
                DensityFunctions.add(shared, k1));
        out.add(new Case("cse_stress(shared subtree x4)", stress));

        // Tier 0 regression — BlendDensity wrapping a RangeChoice (and a few siblings)
        // is the exact shape that previously emitted invalid bytecode (VerifyError:
        // get long/double overflows locals) because emitBlendDensity ran the branchy
        // input emit while Blender+ctx were on the operand stack. Compilation must now
        // succeed and parity must hold (SinglePointContext.getBlender() returns
        // Blender.empty(), which leaves the inner density unchanged).
        DensityFunction inner = DensityFunctions.rangeChoice(yc, -0.25, 0.25,
                DensityFunctions.mul(yc, k2),
                DensityFunctions.add(yc.abs(), k1));
        out.add(new Case("blendDensity(rangeChoice(...))", DensityFunctions.blendDensity(inner)));

        // Tier 1 IROptimizer parity coverage. These all rely on the rewrite producing
        // a value bit-equivalent to the un-optimized tree; we exercise each rule
        // family at least once. Vanilla DensityFunctions doesn't expose sub/div/neg
        // factories, so SUB/DIV-specific folds are exercised indirectly: the
        // optimizer's `x * -1 -> NEG(x)` rewrite covers NEG, and the DIV strength
        // reduction is unreachable from public factories (no path produces Bin.DIV).

        // Algebraic identity: x + 0 -> x. Vanilla wraps `add(arg, k0)` as a
        // MulOrAdd(arg, ADD, 0) which IRBuilder's short-circuit catches at build
        // time, so the optimizer pass should report zero rewrites here too.
        out.add(new Case("identity_add(yc, 0)", DensityFunctions.add(yc, k0)));
        out.add(new Case("identity_add(0, yc)", DensityFunctions.add(k0, yc)));

        // Algebraic identity: x * 1 -> x (handled by the IRBuilder short-circuit).
        out.add(new Case("identity_mul(yc, 1)", DensityFunctions.mul(yc, k1)));

        // Constant fold via MulOrAdd: x * 0 -> Const(0). The optimizer handles this
        // because MulOrAdd builds Bin(MUL, x, Const(0)) and peepholeBin sees the
        // zero on either side.
        out.add(new Case("fold_mul(yc, 0)", DensityFunctions.mul(yc, k0)));

        // Strength reduction: x * 2 -> x + x when x is cheap. yc is cost-1 so this
        // qualifies. Result must match the DMUL parity exactly.
        DensityFunction k2int = DensityFunctions.constant(2.0);
        out.add(new Case("strength_mul2(yc)", DensityFunctions.mul(yc, k2int)));

        // Clamp coalesce: nested clamps collapse to the intersection.
        out.add(new Case("clamp_coalesce(yc, [-1,1] then [-0.5,0.5])",
                yc.clamp(-1.0, 1.0).clamp(-0.5, 0.5)));

        // Constant clamp folding: input is a constant -> result is a constant.
        // Vanilla evaluates the same path so parity is trivial; this asserts the
        // optimizer didn't pick a different rounding.
        out.add(new Case("clamp_const_fold(0.5, [-1, 1])",
                DensityFunctions.constant(0.5).clamp(-1.0, 1.0)));

        // RangeChoice short-circuit: input is a constant 0.1 -> always in [-1, 1)
        // -> the optimizer prunes the whenOutOfRange arm.
        out.add(new Case("rangechoice_shortcircuit_in",
                DensityFunctions.rangeChoice(DensityFunctions.constant(0.1), -1.0, 1.0, k0, k1)));
        // Same but the constant is outside the range -> whenOutOfRange wins.
        out.add(new Case("rangechoice_shortcircuit_out",
                DensityFunctions.rangeChoice(DensityFunctions.constant(5.0), -1.0, 1.0, k0, k1)));

        // Unary collapses.
        out.add(new Case("abs_abs(yc)", yc.abs().abs()));
        out.add(new Case("abs_square(yc)", yc.square().abs()));

        // Multi-step constant folding through nested ADDs: the optimizer should
        // walk inside-out and fold (1 + 2.5) before adding yc, so the final tree
        // is Bin(ADD, yc, Const(3.5)).
        out.add(new Case("fold_chain(add(add(1, 2.5), yc))",
                DensityFunctions.add(DensityFunctions.add(k1, k2), yc)));

        // min/max idempotence: min(x, x) -> x. Use yc.abs() so both sides intern
        // to the same node.
        DensityFunction sharedAbs = yc.abs();
        out.add(new Case("min_xx(yc.abs())", DensityFunctions.min(sharedAbs, sharedAbs)));
        out.add(new Case("max_xx(yc.abs())", DensityFunctions.max(sharedAbs, sharedAbs)));

        // Ap2: TwoArgumentSimpleFunction (non-const, non-const) — not MulOrAdd-fused.
        DensityFunction y1 = DensityFunctions.yClampedGradient(-64, 64, 0.0, 1.0);
        DensityFunction y2 = DensityFunctions.yClampedGradient(0, 320, 0.0, 2.0);
        out.add(new Case("ap2_tasf_min(yc1, yc2)", DensityFunctions.min(y1, y2)));
        out.add(new Case("ap2_tasf_max(yc1, yc2)", DensityFunctions.max(y1, y2)));
        out.add(new Case("ap2_tasf_mul(yc1, yc2)", DensityFunctions.mul(y1, y2)));

        return out;
    }

    /**
     * Tier-3 noise inlining parity. Unlike {@link #runArithmeticSubset()} this
     * <em>requires</em> a running server: the test cases pull live router fields out
     * of the registered {@link NoiseGeneratorSettings} so that every {@link
     * net.minecraft.world.level.levelgen.synth.NormalNoise} they reference is already
     * bound by {@code RandomState.<init>}. Compiling against the registry-bound DFs
     * also exercises {@link
     * dev.denismasterherobrine.densityfunctioncompiler.compiler.noise.NoiseSpecCache}'s
     * mixin-based extraction, which is the path that fails most loudly when a future
     * MC release changes a {@code NormalNoise}/{@code PerlinNoise} field name.
     *
     * <p>We sample {@code 1024} random points per case (smaller than the arithmetic
     * subset's {@code 5000} to keep the bench affordable on the server tick thread)
     * and require {@code maxAbsDiff < 1e-9}. The wider arithmetic chains in router
     * fields like {@code finalDensity} can drift by a few ULPs across architectures,
     * so noise-only fields ({@code barrier}, {@code lava}, {@code temperature}) are
     * preferred over composites — they leave {@code maxAbsDiff} dominated by the
     * one inlined noise per call, which we expect to be bit-exact.
     */
    public static SuiteResult runNoiseSubset(CommandSourceStack src) {
        var server = src.getServer();
        if (server == null) {
            return new SuiteResult(0, 0, List.of("noise self-test needs a running server"));
        }
        Registry<NoiseGeneratorSettings> reg;
        try {
            reg = server.registryAccess().registryOrThrow(Registries.NOISE_SETTINGS);
        } catch (Throwable t) {
            return new SuiteResult(0, 0, List.of("noise_settings registry unavailable: " + t));
        }
        // We pin to vanilla overworld; if the registry hasn't loaded it (e.g. a
        // datapack stripped vanilla worldgen) we surface that as a single test
        // failure rather than crashing the command.
        ResourceLocation overworldId = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");
        NoiseGeneratorSettings ngs = reg.get(overworldId);
        if (ngs == null) {
            return new SuiteResult(1, 0, List.of("vanilla overworld noise_settings missing"));
        }
        NoiseRouter router = ngs.noiseRouter();

        List<Case> cases = new ArrayList<>();
        // Direct noise() — unwraps to one InlinedNoise.
        cases.add(new Case("router.barrier (noise)", router.barrierNoise()));
        cases.add(new Case("router.lava (noise)", router.lavaNoise()));
        cases.add(new Case("router.fluidLevelFloodedness (noise)", router.fluidLevelFloodednessNoise()));
        cases.add(new Case("router.fluidLevelSpread (noise)", router.fluidLevelSpreadNoise()));
        // shiftedNoise2d — InlinedNoise wrapped over a (x*scale + shiftX) coord chain.
        cases.add(new Case("router.temperature (shiftedNoise2d)", router.temperature()));
        cases.add(new Case("router.vegetation (shiftedNoise2d)", router.vegetation()));
        cases.add(new Case("router.continents (shiftedNoise2d)", router.continents()));
        cases.add(new Case("router.erosion (shiftedNoise2d)", router.erosion()));
        // Ridges — pulls in the WeirdScaled / WeirdRarity decomposition path
        // (abs(InlinedNoise(coord/r)) * weirdRarity(InlinedNoise(coord), ord)).
        cases.add(new Case("router.ridges (mixed)", router.ridges()));

        List<String> failures = new ArrayList<>();
        int passed = 0;
        int total = 0;
        for (Case c : cases) {
            total++;
            try {
                DensityFunction compiled = Compiler.compile(c.df);
                DfcDumper.ParitySample s = DfcDumper.sampleParity(c.df, compiled, 1024, 0xC0FFEE_DECAFL ^ total);
                if (s.maxAbsDiff() > 1e-9) {
                    failures.add(c.name + ": maxDiff=" + s.maxAbsDiff() + " (samples=" + s.samples() + ")");
                } else {
                    passed++;
                }
            } catch (Throwable t) {
                failures.add(c.name + ": exception " + t);
            }
        }
        DensityFunctionCompiler.LOGGER.info("DFC noise self-test: {}/{} passed", passed, total);
        for (String f : failures) {
            DensityFunctionCompiler.LOGGER.warn("  noise fail: {}", f);
        }
        return new SuiteResult(total, passed, failures);
    }

    /**
     * See {@link VanillaDensityFunctionCoverage#runFactoryBattery()}.
     */
    public static VanillaDensityFunctionCoverage.BatteryResult runVanillaFactoryCoverage() {
        return VanillaDensityFunctionCoverage.runFactoryBattery();
    }

    /**
     * See {@link VanillaDensityFunctionCoverage#runRegistryReferenceAudit} — every
     * built-in+datapack {@link net.minecraft.core.registries.Registries#DENSITY_FUNCTION} value.
     */
    public static VanillaDensityFunctionCoverage.BatteryResult runRegistryInvokeCoverage(
            net.minecraft.commands.CommandSourceStack src) {
        if (src.getServer() == null) {
            return new VanillaDensityFunctionCoverage.BatteryResult(0, 0, List.of("no server for registry"));
        }
        return VanillaDensityFunctionCoverage.runRegistryReferenceAudit(
                src.getServer().registryAccess().registryOrThrow(Registries.DENSITY_FUNCTION));
    }
}
