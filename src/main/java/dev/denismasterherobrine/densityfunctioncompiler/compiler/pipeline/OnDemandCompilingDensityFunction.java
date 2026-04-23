package dev.denismasterherobrine.densityfunctioncompiler.compiler.pipeline;

import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * A {@link NoiseRouter} field that defers {@link CompilingVisitor#apply} until the first
 * time it is used. The wired source is post-{@code mapAll(NoiseWiringHelper)} on
 * {@link net.minecraft.world.level.levelgen.RandomState} &mdash; the same as batch
 * {@link RouterPipeline#compile}.
 *
 * <p><strong>Rebinding ({@code mapAll}):</strong> a naive implementation that wrapped
 * every remapped sub-root in a new on-demand instance multiplied compiles and hidden
 * classes in hot paths (NoiseChunk, chunk gen) that walk the tree many times. We
 * therefore call {@link #ensureResolved()} before any {@code mapAll} and delegate
 * to the already-compiled tree, matching {@link
 * dev.denismasterherobrine.densityfunctioncompiler.compiler.codegen.CompiledDensityFunction}
 * semantics: one compile per top-level field, then rebinds run on the compiled root.
 */
public final class OnDemandCompilingDensityFunction implements DensityFunction {

    private final DensityFunction wired;

    private volatile DensityFunction resolved;

    public OnDemandCompilingDensityFunction(DensityFunction wired) {
        this.wired = wired;
    }

    public DensityFunction wired() {
        return wired;
    }

    private void ensureResolved() {
        if (resolved != null) {
            return;
        }
        synchronized (this) {
            if (resolved == null) {
                resolved = CompilingVisitor.global().apply(wired);
            }
        }
    }

    @Override
    public double compute(FunctionContext functionContext) {
        ensureResolved();
        return resolved.compute(functionContext);
    }

    @Override
    public void fillArray(double[] output, ContextProvider contextProvider) {
        ensureResolved();
        resolved.fillArray(output, contextProvider);
    }

    @Override
    public DensityFunction mapAll(Visitor visitor) {
        // Never allocate another OnDemand here: high-churn rebinds would each get a
        // first-use compile, exploding /dfc roots and world-load time.
        ensureResolved();
        return resolved.mapAll(visitor);
    }

    @Override
    public double minValue() {
        ensureResolved();
        return resolved.minValue();
    }

    @Override
    public double maxValue() {
        ensureResolved();
        return resolved.maxValue();
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        if (resolved != null) {
            return resolved.codec();
        }
        return wired.codec();
    }
}
