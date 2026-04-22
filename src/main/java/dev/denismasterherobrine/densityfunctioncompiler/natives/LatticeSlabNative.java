package dev.denismasterherobrine.densityfunctioncompiler.natives;

/**
 * Marker type: slab batching is implemented in {@link dev.denismasterherobrine.densityfunctioncompiler.compiler.codegen.Codegen}
 * ({@code fillArray} → per-Y coordinate fill + {@link dev.denismasterherobrine.dfcnatives.DfcNativeBridge#normalNoiseStackBatch};
 * see {@link dev.denismasterherobrine.densityfunctioncompiler.compiler.ir.SlabNativeBatchPlan}).
 */
public final class LatticeSlabNative {

    private LatticeSlabNative() {}

    /** Unused; slab path is always emitted from {@code Codegen} when {@link SlabNativeBatchPlan} applies. */
    public static boolean tryNativeSlabFill() {
        return false;
    }
}
