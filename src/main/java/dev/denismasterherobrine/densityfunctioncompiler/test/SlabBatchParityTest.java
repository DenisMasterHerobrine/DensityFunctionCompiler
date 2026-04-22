package dev.denismasterherobrine.densityfunctioncompiler.test;

import dev.denismasterherobrine.densityfunctioncompiler.DensityFunctionCompiler;
import dev.denismasterherobrine.densityfunctioncompiler.compiler.ir.CellLatticeOption;
import dev.denismasterherobrine.densityfunctioncompiler.compiler.ir.IRNode;
import dev.denismasterherobrine.densityfunctioncompiler.compiler.ir.SlabNativeBatchPlan;

import java.util.Optional;

/**
 * Tests for {@link SlabNativeBatchPlan} analysis (compile-time slab batch eligibility).
 */
public final class SlabBatchParityTest {

    private SlabBatchParityTest() {}

    public static void verify() {
        verifySlabSlotsForLatticePlusInlinedNoise();
        verifyUnsafeCoordsRejectPlan();
        verifyArithmeticLatticeHasNoSlabSlots();
        DensityFunctionCompiler.LOGGER.info("SlabBatchParityTest: OK");
    }

    /**
     * Y-only hoisted chain ({@link CellLatticeOption#MIN_HOIST_SIZE}+ nodes) plus an
     * {@link IRNode.InlinedNoise} on block coordinates should yield a non-empty slab plan.
     */
    private static void verifySlabSlotsForLatticePlusInlinedNoise() {
        IRNode grad = new IRNode.YClampedGradient(-64, 320, 0.0, 1.0);
        IRNode mul = new IRNode.Bin(IRNode.BinOp.MUL, grad, new IRNode.Const(2.0));
        IRNode squared = new IRNode.Unary(IRNode.UnaryOp.SQUARE, mul);
        IRNode clamped = new IRNode.Clamp(squared, 0.0, 1.0);
        IRNode neg = new IRNode.Unary(IRNode.UnaryOp.NEG, clamped);
        IRNode noise = new IRNode.InlinedNoise(0, IRNode.BlockX.INSTANCE, IRNode.BlockY.INSTANCE,
                IRNode.BlockZ.INSTANCE, 1.0);
        IRNode root = new IRNode.Bin(IRNode.BinOp.ADD, neg, noise);

        CellLatticeOption.LatticePlan plan = CellLatticeOption.analyze(root).orElseThrow(
                () -> new AssertionError("expected lattice plan"));
        if (plan.hoistAxis() != CellLatticeOption.Axis.Y_ONLY) {
            throw new AssertionError("expected Y_ONLY, got " + plan.hoistAxis());
        }
        Optional<SlabNativeBatchPlan> slab = SlabNativeBatchPlan.analyze(root, plan, 1, 0);
        if (slab.isEmpty()) {
            throw new AssertionError("expected SlabNativeBatchPlan for lattice + InlinedNoise");
        }
        if (slab.get().slots().size() != 1 || !(slab.get().slots().get(0) instanceof SlabNativeBatchPlan.NormalSlot)) {
            throw new AssertionError("expected one NormalSlot, got " + slab.get().slots());
        }
    }

    /** Coordinate subgraph containing {@link IRNode.Invoke} must not batch. */
    private static void verifyUnsafeCoordsRejectPlan() {
        IRNode grad = new IRNode.YClampedGradient(-64, 320, 0.0, 1.0);
        IRNode mul = new IRNode.Bin(IRNode.BinOp.MUL, grad, new IRNode.Const(2.0));
        IRNode squared = new IRNode.Unary(IRNode.UnaryOp.SQUARE, mul);
        IRNode clamped = new IRNode.Clamp(squared, 0.0, 1.0);
        IRNode neg = new IRNode.Unary(IRNode.UnaryOp.NEG, clamped);
        IRNode noise = new IRNode.InlinedNoise(0, new IRNode.Invoke(0), IRNode.BlockY.INSTANCE,
                IRNode.BlockZ.INSTANCE, 1.0);
        IRNode root = new IRNode.Bin(IRNode.BinOp.ADD, neg, noise);

        CellLatticeOption.LatticePlan plan = CellLatticeOption.analyze(root).orElseThrow();
        Optional<SlabNativeBatchPlan> slab = SlabNativeBatchPlan.analyze(root, plan, 1, 0);
        if (slab.isPresent()) {
            throw new AssertionError("Invoke in coordX should reject slab batching");
        }
    }

    /** Pure arithmetic lattice (no {@link IRNode.InlinedNoise} in {@code lattice_inner}) → no slab slots. */
    private static void verifyArithmeticLatticeHasNoSlabSlots() {
        IRNode grad = new IRNode.YClampedGradient(-64, 320, 0.0, 1.0);
        IRNode mul = new IRNode.Bin(IRNode.BinOp.MUL, grad, new IRNode.Const(2.0));
        IRNode squared = new IRNode.Unary(IRNode.UnaryOp.SQUARE, mul);
        IRNode clamped = new IRNode.Clamp(squared, 0.0, 1.0);
        IRNode neg = new IRNode.Unary(IRNode.UnaryOp.NEG, clamped);
        IRNode root = new IRNode.Bin(IRNode.BinOp.ADD, neg, IRNode.BlockX.INSTANCE);

        CellLatticeOption.LatticePlan plan = CellLatticeOption.analyze(root).orElseThrow();
        Optional<SlabNativeBatchPlan> slab = SlabNativeBatchPlan.analyze(root, plan, 1, 0);
        if (slab.isPresent()) {
            throw new AssertionError("expected empty slab plan for arithmetic-only lattice inner");
        }
    }

}
