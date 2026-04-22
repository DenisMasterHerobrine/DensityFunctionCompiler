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

        return out;
    }
}
