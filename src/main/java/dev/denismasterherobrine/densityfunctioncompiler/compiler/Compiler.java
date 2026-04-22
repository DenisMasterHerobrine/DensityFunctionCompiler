package dev.denismasterherobrine.densityfunctioncompiler.compiler;

import dev.denismasterherobrine.densityfunctioncompiler.DensityFunctionCompiler;
import dev.denismasterherobrine.densityfunctioncompiler.compiler.codegen.Codegen;
import dev.denismasterherobrine.densityfunctioncompiler.compiler.codegen.CompiledDensityFunction;
import dev.denismasterherobrine.densityfunctioncompiler.compiler.codegen.ConstantPool;
import dev.denismasterherobrine.densityfunctioncompiler.compiler.codegen.HiddenClassLoader;
import dev.denismasterherobrine.densityfunctioncompiler.compiler.codegen.Splitter;
import dev.denismasterherobrine.densityfunctioncompiler.compiler.ir.Bounds;
import dev.denismasterherobrine.densityfunctioncompiler.compiler.ir.IRBuilder;
import dev.denismasterherobrine.densityfunctioncompiler.compiler.ir.IRNode;
import dev.denismasterherobrine.densityfunctioncompiler.compiler.ir.IROptimizer;
import dev.denismasterherobrine.densityfunctioncompiler.compiler.ir.NoiseExpander;
import dev.denismasterherobrine.densityfunctioncompiler.compiler.ir.RefCount;
import dev.denismasterherobrine.densityfunctioncompiler.compiler.noise.BlendedNoiseSpec;
import dev.denismasterherobrine.densityfunctioncompiler.compiler.pipeline.CompilingVisitor;
import dev.denismasterherobrine.densityfunctioncompiler.compiler.pipeline.RouterPipeline;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Public facade for the JIT compiler. {@link #compile(DensityFunction)} takes any
 * {@link DensityFunction} and returns a {@link CompiledDensityFunction} that's
 * behaviourally identical (within the usual {@code ULP} bounds for floating-point
 * arithmetic), or — if compilation fails for any reason — the original function.
 *
 * <p>The compiler is intentionally fail-soft: a single broken DensityFunction (e.g. an
 * unrecognised mod-provided node we can't currently inline) must never break worldgen.
 * On failure we log a warning and fall back to the original instance, which the visitor
 * cache still memoises as the "compiled" answer so we don't keep retrying.
 */
public final class Compiler {

    private static final AtomicInteger CLASS_NAME_COUNTER = new AtomicInteger();

    private Compiler() {}

    /** Compile {@code df}, descending recursively into its children via {@link CompilingVisitor}. */
    public static DensityFunction compile(DensityFunction df) {
        Result r = compileWithDetail(df);
        return r == null ? df : r.compiled();
    }

    /**
     * Variant exposing the intermediate compilation state — used by the {@code /dfc dump}
     * command to print IR / bytecode without recompiling. Returns {@code null} on failure
     * (caller should fall back to the original DensityFunction).
     */
    public static Result compileWithDetail(DensityFunction df) {
        try {
            ConstantPool pool = new ConstantPool();
            IRBuilder builder = new IRBuilder(pool, CompilingVisitor.global());
            IRNode root = builder.build(df);

            // Peephole pass: constant folding, algebraic identities, RangeChoice
            // short-circuiting, cost-aware strength reduction. Runs before Bounds /
            // RefCount / Splitter so downstream stages see the post-rewrite DAG.
            // Every rewritten node is re-interned through the same IRBuilder, so
            // hash-consing / CSE stay consistent.
            IROptimizer.Result optResult = IROptimizer.optimize(root, builder, pool);
            root = optResult.root();
            int optimizerRewrites = optResult.rewrites();

            // Tier 3 — noise inlining pass. Rewrites every Noise / ShiftedNoise /
            // ShiftA / ShiftB / Shift / WeirdScaled into InlinedNoise / WeirdRarity
            // form so the codegen can unroll their per-octave loops with baked-in
            // amplitudes / input factors (see NoiseExpander javadoc). The expander
            // exposes coordinate sub-trees as first-class IR, so we re-run the
            // optimizer to fold the newly visible (x*scale + shift)*INPUT_FACTOR
            // chains and to CSE shared coordinates.
            NoiseExpander.Result noiseResult = NoiseExpander.expand(root, builder, pool);
            root = noiseResult.root();
            int noisesSpecialized = noiseResult.noisesSpecialized();
            int octavesUnrolled = noiseResult.octavesUnrolled();
            if (noisesSpecialized > 0) {
                IROptimizer.Result postNoise = IROptimizer.optimize(root, builder, pool);
                root = postNoise.root();
                optimizerRewrites += postNoise.rewrites();
            }

            int uniqueNodes = builder.internedCount();
            int cseSavings = builder.cseSavings();
            RefCount.Result rc = RefCount.compute(root);

            double minVal;
            double maxVal;
            try {
                minVal = Bounds.min(root, pool);
                maxVal = Bounds.max(root, pool);
            } catch (RuntimeException bx) {
                minVal = df.minValue();
                maxVal = df.maxValue();
            }

            Set<IRNode> extracted = Splitter.plan(root, rc, pool);

            // Use _ rather than $ in the per-class suffix: hidden class bytecode that
            // contains a `$` in its own name confuses NeoForge's ModuleClassLoader into
            // treating the prefix as an outer class, which then fails to load and the
            // JVM rejects defineHiddenClass with NoClassDefFoundError.
            String className = "dev/denismasterherobrine/densityfunctioncompiler/compiler/codegen/CompiledDF_"
                    + CLASS_NAME_COUNTER.getAndIncrement();
            Codegen.Result emitResult = Codegen.emit(className, root, rc, extracted, pool, minVal, maxVal);
            byte[] bytecode = emitResult.bytecode();
            int helpersEmitted = emitResult.helpersEmitted();

            HiddenClassLoader.DefineResult dr = HiddenClassLoader.defineWithLookup(bytecode);
            Class<? extends CompiledDensityFunction> cls = dr.cls();
            MethodHandles.Lookup lookup = dr.lookup();

            // Resolve every helper$N to its MethodHandle. The MH's MethodType must match
            // the call-site descriptor in the generated bytecode exactly (Codegen.HELPER_DESC),
            // i.e. (CompiledDensityFunction, FunctionContext) -> double — not the hidden
            // subclass type, so that both the descriptor and the runtime check stay free
            // of self-references.
            MethodHandle[] helperHandles;
            try {
                helperHandles = new MethodHandle[helpersEmitted];
                MethodType helperType = MethodType.methodType(
                        double.class,
                        CompiledDensityFunction.class,
                        DensityFunction.FunctionContext.class);
                for (int i = 0; i < helpersEmitted; i++) {
                    helperHandles[i] = lookup.findStatic(cls, Codegen.helperName(i), helperType);
                }
            } catch (NoSuchMethodException | IllegalAccessException e) {
                throw new RuntimeException("Failed to resolve helper MethodHandles for "
                        + className + " (" + helpersEmitted + " helpers expected)", e);
            }

            // Resolve the subclass constructor as a MethodHandle and asType-coerce its
            // return to the supertype. This is what CompiledDensityFunction.rebind calls
            // via invokeExact when a visitor remaps externs (e.g. NoiseChunk swapping
            // inner Markers with cell caches). We have to do this here rather than at
            // first rebind because findConstructor needs the post-define Lookup with full
            // access to the hidden class — every call afterwards is just an invokeExact.
            MethodHandle ctorMH;
            try {
                // Constructor parameter order matches CompiledDensityFunction's superclass
                // ctor exactly. The second Object[] (after splines) carries the per-octave
                // ImprovedNoise payload — see ConstantPool.finishNoiseOctaves.
                MethodType ctorType = MethodType.methodType(void.class,
                        double[].class, NormalNoise[].class, Object[].class, Object[].class,
                        DensityFunction[].class,
                        double.class, double.class,
                        MethodHandle[].class, MethodHandle.class);
                ctorMH = lookup.findConstructor(cls, ctorType)
                        .asType(MethodType.methodType(CompiledDensityFunction.class,
                                double[].class, NormalNoise[].class, Object[].class, Object[].class,
                                DensityFunction[].class,
                                double.class, double.class,
                                MethodHandle[].class, MethodHandle.class));
            } catch (NoSuchMethodException | IllegalAccessException e) {
                throw new RuntimeException("Failed to resolve constructor MethodHandle for "
                        + className, e);
            }

            CompiledDensityFunction compiled;
            try {
                compiled = (CompiledDensityFunction) ctorMH.invokeExact(
                        pool.finishConstants(),
                        pool.finishNoises(),
                        pool.finishSplines(),
                        pool.finishNoiseOctaves(),
                        pool.finishExterns(),
                        minVal, maxVal,
                        helperHandles, ctorMH);
            } catch (Throwable t) {
                throw new RuntimeException("Failed to instantiate " + className, t);
            }

            RouterPipeline.recordCompiledRoot(uniqueNodes, cseSavings);
            RouterPipeline.recordHelpers(helpersEmitted);
            RouterPipeline.recordOptimizerRewrites(optimizerRewrites);
            RouterPipeline.recordNoiseInline(noisesSpecialized, octavesUnrolled);
            RouterPipeline.recordBlendedInline(pool.blendedNoiseSpecCount(), countBlendedNonNullOctaves(pool));

            return new Result(compiled, root, rc, pool, bytecode, className,
                    uniqueNodes, cseSavings, helpersEmitted, optimizerRewrites,
                    noisesSpecialized, octavesUnrolled,
                    minVal, maxVal);
        } catch (Throwable t) {
            DensityFunctionCompiler.LOGGER.warn(
                    "Compilation failed for {} ({}): {} — falling back to vanilla evaluator",
                    df.getClass().getSimpleName(),
                    System.identityHashCode(df),
                    t.toString(), t);
            return null;
        }
    }

    private static long countBlendedNonNullOctaves(ConstantPool pool) {
        long t = 0;
        for (int i = 0; i < pool.blendedNoiseSpecCount(); i++) {
            BlendedNoiseSpec s = pool.blendedNoiseSpec(i);
            for (var x : s.mainOctaves()) {
                if (x != null) t++;
            }
            for (var x : s.minLimitOctaves()) {
                if (x != null) t++;
            }
            for (var x : s.maxLimitOctaves()) {
                if (x != null) t++;
            }
        }
        return t;
    }

    /** Diagnostic snapshot of one compile() call. */
    public record Result(
            CompiledDensityFunction compiled,
            IRNode root,
            RefCount.Result refs,
            ConstantPool pool,
            byte[] bytecode,
            String classInternalName,
            int uniqueNodes,
            int cseSavings,
            int helpersEmitted,
            int optimizerRewrites,
            int noisesSpecialized,
            int octavesUnrolled,
            double minValue,
            double maxValue) {}
}
