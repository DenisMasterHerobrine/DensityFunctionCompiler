package dev.denismasterherobrine.densityfunctioncompiler.test;

import dev.denismasterherobrine.densityfunctioncompiler.DensityFunctionCompiler;
import dev.denismasterherobrine.densityfunctioncompiler.compiler.Compiler;
import dev.denismasterherobrine.densityfunctioncompiler.debug.DfcDumper;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;

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

        return out;
    }
}
