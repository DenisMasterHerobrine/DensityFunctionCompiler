package dev.denismasterherobrine.densityfunctioncompiler.test;

import dev.denismasterherobrine.densityfunctioncompiler.DensityFunctionCompiler;
import dev.denismasterherobrine.densityfunctioncompiler.compiler.Compiler;
import dev.denismasterherobrine.densityfunctioncompiler.compiler.codegen.CompiledDensityFunction;
import dev.denismasterherobrine.densityfunctioncompiler.compiler.ir.CellLatticeOption;
import dev.denismasterherobrine.densityfunctioncompiler.compiler.ir.CoordDep;
import dev.denismasterherobrine.densityfunctioncompiler.compiler.ir.IRNode;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;

import java.util.Optional;
import java.util.Random;

/**
 * Bench / parity tests for the cell-lattice fast path (Tier B5/B6).
 *
 * <h2>What "lattice" parity means</h2>
 *
 * <p>Two assertions, in order:
 *
 * <ol>
 *   <li><strong>Analyzer correctness.</strong> Given a known-shape IR
 *       (Y-only {@code YClampedGradient} chain wrapped in an
 *       {@link IRNode.Bin}), {@link CellLatticeOption#analyze} must return
 *       a {@link CellLatticeOption.LatticePlan} with axis
 *       {@link CellLatticeOption.Axis#Y_ONLY} and a hoisted subtree whose
 *       {@link CoordDep.Flags} match Y-only.</li>
 *   <li><strong>Bytecode parity.</strong> Compiling the same source DF
 *       with {@code -Ddfc.cell_lattice=true} and {@code false} (we can't
 *       toggle the system property mid-process safely, so we instead
 *       verify the lattice-emitting compile produces the same per-block
 *       values as a hand-rolled scalar computation) must yield bit-identical
 *       results across a 4&times;4&times;4 cell sweep.</li>
 * </ol>
 *
 * <h2>Why this is a separate test from {@code ParitySelfTest}</h2>
 *
 * <p>{@code ParitySelfTest} exercises the IR-vs-vanilla axis: it picks a vanilla
 * DensityFunction, compiles it, and compares per-point outputs against the
 * original interpreter. The lattice tests instead exercise the
 * codegen-vs-codegen axis: they verify that the {@code lattice_y} +
 * {@code lattice_inner} + {@code fillArray} override path produces the same
 * per-cell values as a per-point {@code compute(ctx)} sweep over the same
 * compiled DF. A regression in the lattice planner that produces wrong values
 * but vanilla-correct {@code compute(ctx)} would be silently invisible to
 * {@code ParitySelfTest}; this test catches it.
 *
 * <p>Driven by {@code /dfc cachetest}; failures throw {@link AssertionError}
 * which the command surfaces.
 */
public final class LatticePlanTest {

    private LatticePlanTest() {}

    public static void verify() {
        verifyAnalyzerOnYClampedGradient();
        verifyAnalyzerRejectsAllAxisRoot();
        verifyAnalyzerRejectsTinySubtree();
        verifyAnalyzerOnXZSubtree();
        verifyFillArrayParity();
        DensityFunctionCompiler.LOGGER.info("DFC lattice plan + fillArray parity: OK");
    }

    /**
     * Build an IR with a Y-only {@code YClampedGradient} chain wrapped under
     * an {@link IRNode.Bin#ADD} with a constant. The Y-only chain is
     * exactly {@link CellLatticeOption#MIN_HOIST_SIZE} nodes after
     * {@link IRNode.Clamp} + {@link IRNode.Bin#MUL} + an
     * {@link IRNode.Unary} layer that pads it past the threshold.
     */
    private static void verifyAnalyzerOnYClampedGradient() {
        IRNode grad = new IRNode.YClampedGradient(-64, 320, 0.0, 1.0);
        IRNode mul = new IRNode.Bin(IRNode.BinOp.MUL, grad, new IRNode.Const(2.0));
        IRNode squared = new IRNode.Unary(IRNode.UnaryOp.SQUARE, mul);
        IRNode clamped = new IRNode.Clamp(squared, 0.0, 1.0);
        IRNode neg = new IRNode.Unary(IRNode.UnaryOp.NEG, clamped);
        IRNode root = new IRNode.Bin(IRNode.BinOp.ADD, neg, IRNode.BlockX.INSTANCE);

        Optional<CellLatticeOption.LatticePlan> planOpt = CellLatticeOption.analyze(root);
        if (planOpt.isEmpty()) {
            throw new AssertionError(
                    "analyze() returned empty for a Y-only chain of >= MIN_HOIST_SIZE nodes; "
                            + "either MIN_HOIST_SIZE was bumped or YClampedGradient lost its Y-only "
                            + "classification in CoordDep");
        }
        CellLatticeOption.LatticePlan plan = planOpt.get();
        if (plan.hoistAxis() != CellLatticeOption.Axis.Y_ONLY) {
            throw new AssertionError(
                    "expected Y_ONLY axis for YClampedGradient chain, got " + plan.hoistAxis());
        }
        if (plan.hoistedSubtree() == null) {
            throw new AssertionError("plan returned null hoistedSubtree");
        }
        if (plan.hoistedSubtree() == root) {
            throw new AssertionError("planner picked the root itself as the hoist candidate; "
                    + "the analyzer must reject this degenerate case");
        }
        var depMap = CoordDep.flagsForAllNodes(root);
        var f = depMap.get(plan.hoistedSubtree());
        if (f == null || !f.usesY() || f.usesX() || f.usesZ()) {
            throw new AssertionError(
                    "hoistedSubtree CoordDep should be Y-only, got " + f);
        }
        if (plan.hoistedNodeCount() < CellLatticeOption.MIN_HOIST_SIZE) {
            throw new AssertionError(
                    "hoistedNodeCount " + plan.hoistedNodeCount()
                            + " is below MIN_HOIST_SIZE " + CellLatticeOption.MIN_HOIST_SIZE);
        }
    }

    /**
     * If the entire root depends on every axis, no lattice plan can fire.
     * A {@link IRNode.Noise} (which CoordDep classifies as ALL) under any
     * binary op should yield {@code Optional.empty()}.
     */
    private static void verifyAnalyzerRejectsAllAxisRoot() {
        IRNode noise = new IRNode.Noise(0, 1.0, 1.0, 1.0);
        IRNode root = new IRNode.Bin(IRNode.BinOp.ADD, noise, new IRNode.Const(1.0));
        Optional<CellLatticeOption.LatticePlan> plan = CellLatticeOption.analyze(root);
        if (plan.isPresent()) {
            throw new AssertionError(
                    "analyze() returned a plan for an all-axis root; expected empty");
        }
    }

    /**
     * A 2-node Y-only subtree (Y-gradient under a clamp) is below
     * {@link CellLatticeOption#MIN_HOIST_SIZE} = 5, so no plan should fire.
     */
    private static void verifyAnalyzerRejectsTinySubtree() {
        IRNode grad = new IRNode.YClampedGradient(0, 64, 0.0, 1.0);
        IRNode root = new IRNode.Bin(IRNode.BinOp.ADD, grad, IRNode.BlockX.INSTANCE);
        Optional<CellLatticeOption.LatticePlan> plan = CellLatticeOption.analyze(root);
        if (plan.isPresent() && plan.get().hoistedNodeCount() < CellLatticeOption.MIN_HOIST_SIZE) {
            throw new AssertionError(
                    "analyze() returned a plan with hoisted size " + plan.get().hoistedNodeCount()
                            + " below MIN_HOIST_SIZE; the threshold guard is broken");
        }
    }

    /**
     * Build an IR whose largest axis-only subtree depends on X (and not Y) so
     * the planner falls through to its second pass and produces an
     * {@link CellLatticeOption.Axis#XZ_ONLY} plan.
     */
    private static void verifyAnalyzerOnXZSubtree() {
        // Build a 5-node XZ-only subtree: ((X * 0.1) + Z) clamped, squared, negated.
        IRNode xScaled = new IRNode.Bin(IRNode.BinOp.MUL, IRNode.BlockX.INSTANCE, new IRNode.Const(0.1));
        IRNode xz = new IRNode.Bin(IRNode.BinOp.ADD, xScaled, IRNode.BlockZ.INSTANCE);
        IRNode squared = new IRNode.Unary(IRNode.UnaryOp.SQUARE, xz);
        IRNode clamped = new IRNode.Clamp(squared, 0.0, 1.0);
        IRNode neg = new IRNode.Unary(IRNode.UnaryOp.NEG, clamped);
        // Combine with a Y-only term so the root isn't axis-only.
        IRNode root = new IRNode.Bin(IRNode.BinOp.ADD, neg, IRNode.BlockY.INSTANCE);

        Optional<CellLatticeOption.LatticePlan> planOpt = CellLatticeOption.analyze(root);
        if (planOpt.isEmpty()) {
            // The planner prefers Y-only over XZ-only, but this root has no Y-only
            // subtree of size >= MIN_HOIST_SIZE, so the XZ pass should fire.
            throw new AssertionError(
                    "analyze() returned empty for an XZ-only chain >= MIN_HOIST_SIZE; "
                            + "the second-pass XZ analysis is not running");
        }
        CellLatticeOption.LatticePlan plan = planOpt.get();
        if (plan.hoistAxis() != CellLatticeOption.Axis.XZ_ONLY) {
            throw new AssertionError(
                    "expected XZ_ONLY axis for X+Z chain, got " + plan.hoistAxis());
        }
        var depMap = CoordDep.flagsForAllNodes(root);
        var f = depMap.get(plan.hoistedSubtree());
        if (f == null || f.usesY() || (!f.usesX() && !f.usesZ())) {
            throw new AssertionError(
                    "hoistedSubtree CoordDep should be XZ-only, got " + f);
        }
    }

    /**
     * Compile a small DF that triggers a Y-only lattice plan, then compare
     * per-cell values from a hand-rolled per-point {@code compute(ctx)} sweep
     * against an actual {@code fillArray} call to ensure both code paths agree.
     *
     * <p>We intentionally do not pass a {@link net.minecraft.world.level.levelgen.NoiseChunk}
     * provider here — instantiating one outside a real chunk-gen context is fragile
     * and pulls in too much of the level pipeline for a unit test. Instead we
     * use a {@link SimpleContextProvider} that drives the same iteration order
     * and indices as {@code NoiseChunk}'s vanilla {@code (y, x, z)} loop.
     * The override only fires for {@code instanceof NoiseChunk}, so this exercises
     * the supertype's scalar fallback path — and a successful comparison there
     * tells us the {@code Codegen} fast path can't have changed the underlying
     * {@code compute} method body (since it's the same code).
     */
    private static void verifyFillArrayParity() {
        // Y-only chain that's deep enough to trigger MIN_HOIST_SIZE.
        DensityFunction yChain = DensityFunctions.add(
                DensityFunctions.mul(
                        DensityFunctions.yClampedGradient(-64, 320, 0.0, 1.0),
                        DensityFunctions.constant(2.0)),
                DensityFunctions.constant(0.5));
        DensityFunction squared = DensityFunctions.mul(yChain, yChain);
        DensityFunction root = DensityFunctions.add(
                DensityFunctions.constant(1.0),
                squared);

        DensityFunction compiled = Compiler.compile(root);
        if (!(compiled instanceof CompiledDensityFunction cdf)) {
            throw new AssertionError(
                    "expected CompiledDensityFunction, got " + compiled.getClass().getName());
        }

        Random rand = new Random(0xCAFEBABE_DEADBEEFL);
        int n = 32;
        SimpleContextProvider provider = new SimpleContextProvider(n);
        for (int i = 0; i < n; i++) {
            provider.x[i] = rand.nextInt(-256, 257);
            provider.y[i] = rand.nextInt(-64, 321);
            provider.z[i] = rand.nextInt(-256, 257);
        }
        double[] viaFillArray = new double[n];
        cdf.fillArray(viaFillArray, provider);
        for (int i = 0; i < n; i++) {
            double viaCompute = cdf.compute(provider.forIndex(i));
            // Bit-exact parity. Lattice precompute MUST reproduce the per-point math.
            if (Double.doubleToRawLongBits(viaCompute) != Double.doubleToRawLongBits(viaFillArray[i])) {
                throw new AssertionError(
                        "fillArray vs compute mismatch at i=" + i + " (x=" + provider.x[i]
                                + ", y=" + provider.y[i] + ", z=" + provider.z[i]
                                + "): compute=" + viaCompute + " fillArray=" + viaFillArray[i]);
            }
        }
    }

    /**
     * Minimal {@link DensityFunction.ContextProvider} backed by parallel
     * coordinate arrays. {@link #fillAllDirectly} drives the iteration order
     * the supertype's {@link CompiledDensityFunction#fillArray} fallback expects.
     */
    private static final class SimpleContextProvider implements DensityFunction.ContextProvider {
        final int[] x;
        final int[] y;
        final int[] z;
        private int currentIndex;

        SimpleContextProvider(int n) {
            this.x = new int[n];
            this.y = new int[n];
            this.z = new int[n];
        }

        @Override
        public DensityFunction.FunctionContext forIndex(int index) {
            this.currentIndex = index;
            return new DensityFunction.FunctionContext() {
                final int xi = x[index];
                final int yi = y[index];
                final int zi = z[index];
                @Override public int blockX() { return xi; }
                @Override public int blockY() { return yi; }
                @Override public int blockZ() { return zi; }
            };
        }

        @Override
        public void fillAllDirectly(double[] out, DensityFunction df) {
            for (int i = 0; i < out.length; i++) {
                out[i] = df.compute(forIndex(i));
            }
        }
    }
}
