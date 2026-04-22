package dev.denismasterherobrine.densityfunctioncompiler.compiler.noise;

import dev.denismasterherobrine.densityfunctioncompiler.DensityFunctionCompiler;
import dev.denismasterherobrine.densityfunctioncompiler.mixin.noise.NormalNoiseAccessor;
import dev.denismasterherobrine.densityfunctioncompiler.mixin.noise.PerlinNoiseAccessor;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Process-wide cache of {@link NoiseSpec} instances keyed by {@link NormalNoise}
 * identity. Each {@code NormalNoise} occurs in many density functions across many
 * compiled noise routers, so the per-spec build (a couple of mixin reads, an array
 * walk, a few {@link Math#pow} calls) only happens once per unique sampler.
 *
 * <p>Identity-keyed because vanilla {@code NormalNoise} doesn't override
 * {@link Object#equals}, and identical noise parameters can produce instances that
 * are observably different (different permutation tables from different RandomState
 * forks). This matches how {@link
 * dev.denismasterherobrine.densityfunctioncompiler.compiler.codegen.ConstantPool#internNoise}
 * stores noise references downstream.
 *
 * <p>Thread-safety: a {@link ConcurrentHashMap} allows concurrent {@link #specFor}
 * lookups; each key is built at most once (failed builds are cached as a sentinel
 * so we do not keep retrying under contention).
 *
 * <p>Stats are exposed through the static atomics so {@code /dfc stats} and
 * {@link dev.denismasterherobrine.densityfunctioncompiler.compiler.pipeline.RouterPipeline}
 * can surface them without taking the cache lock.
 */
public final class NoiseSpecCache {

    /** Total number of {@link NoiseSpec} instances built (lifetime). */
    public static final AtomicLong SPECS_BUILT = new AtomicLong();
    /** Sum of active octaves across every built spec (lifetime). */
    public static final AtomicLong OCTAVES_ACTIVE = new AtomicLong();
    /** Sum of skipped (null/zero-amplitude) octaves across every built spec. */
    public static final AtomicLong OCTAVES_SKIPPED = new AtomicLong();
    /** Number of spec builds that hit a mixin-binding failure and bailed. */
    public static final AtomicLong MIXIN_BIND_FAILURES = new AtomicLong();

    private static final Object FAILED = new Object();

    private static final ConcurrentHashMap<NormalNoise, Object> CACHE = new ConcurrentHashMap<>();

    private NoiseSpecCache() {}

    /**
     * Returns the cached spec for {@code noise}, building (and caching) it on first
     * call. Returns {@code null} when the spec cannot be built (mixin-binding
     * failure, malformed PerlinNoise state); callers are expected to fall back to
     * the legacy un-inlined emission path in that case.
     */
    public static NoiseSpec specFor(NormalNoise noise) {
        Object v = CACHE.computeIfAbsent(noise, k -> {
            NoiseSpec built = build(k);
            if (built == null) {
                return FAILED;
            }
            SPECS_BUILT.incrementAndGet();
            OCTAVES_ACTIVE.addAndGet(built.totalActiveOctaves());
            return built;
        });
        if (v == FAILED) {
            return null;
        }
        return (NoiseSpec) v;
    }

    /** For tests / {@code /dfc reload}: drop everything we've cached. */
    public static void clear() {
        CACHE.clear();
    }

    /**
     * Hard ceiling on the per-noise active-octave budget that may be unrolled inline.
     * A {@code NormalNoise} with {@code first.activeOctaves + second.activeOctaves}
     * exceeding this number would, on its own, blow past half of {@link
     * dev.denismasterherobrine.densityfunctioncompiler.compiler.codegen.Splitter#METHOD_BUDGET_BYTES}
     * — at which point the splitter still has to evict something but the inlined
     * noise can't itself be hoisted into a helper without bringing along its octave
     * fields. The threshold is sized against the per-octave estimate in {@link
     * dev.denismasterherobrine.densityfunctioncompiler.compiler.codegen.SizeEstimator}
     * (~65 bytes per octave): {@code 25_000 / 65 ≈ 384}. Vanilla noises top out
     * around 16 octaves combined, so this is purely a defensive cap for mod-defined
     * noises with pathological octave counts.
     */
    public static final int MAX_INLINEABLE_OCTAVES = 384;

    /**
     * Returns {@code true} if the given spec is small enough to safely inline. A
     * {@code false} return tells {@link
     * dev.denismasterherobrine.densityfunctioncompiler.compiler.ir.NoiseExpander}
     * to keep emitting the legacy {@code INVOKEVIRTUAL NormalNoise.getValue} path
     * for this noise, preserving correctness when the inlined form would exceed the
     * per-method bytecode budget.
     */
    public static boolean shouldInline(NoiseSpec spec) {
        if (spec == null) return false;
        return spec.totalActiveOctaves() <= MAX_INLINEABLE_OCTAVES;
    }

    private static NoiseSpec build(NormalNoise noise) {
        NormalNoiseAccessor nn;
        try {
            nn = (NormalNoiseAccessor) (Object) noise;
        } catch (ClassCastException ex) {
            // Mixin didn't apply (development env without -Dmixin.debug=true,
            // or a third-party agent stripped our class transformations).
            DensityFunctionCompiler.LOGGER.warn(
                    "NormalNoiseAccessor mixin not applied to {} — falling back to legacy noise emission",
                    System.identityHashCode(noise));
            MIXIN_BIND_FAILURES.incrementAndGet();
            return null;
        }
        PerlinNoise first = nn.dfc$getFirst();
        PerlinNoise second = nn.dfc$getSecond();
        double valueFactor = nn.dfc$getValueFactor();
        if (first == null || second == null) {
            // Defensive: NormalNoise's ctor never leaves these null, but if a mod
            // ever subclasses with a degenerate state we'd rather fall back than
            // NPE inside generated bytecode.
            return null;
        }
        NoiseSpec.PerlinSpec firstSpec = buildPerlin(first, /* inputCoordScale */ 1.0);
        NoiseSpec.PerlinSpec secondSpec = buildPerlin(second, NoiseSpec.INPUT_FACTOR);
        if (firstSpec == null || secondSpec == null) return null;
        return new NoiseSpec(valueFactor, firstSpec, secondSpec);
    }

    private static NoiseSpec.PerlinSpec buildPerlin(PerlinNoise pn, double inputCoordScale) {
        PerlinNoiseAccessor pa;
        try {
            pa = (PerlinNoiseAccessor) (Object) pn;
        } catch (ClassCastException ex) {
            DensityFunctionCompiler.LOGGER.warn(
                    "PerlinNoiseAccessor mixin not applied to {} — falling back to legacy noise emission",
                    System.identityHashCode(pn));
            MIXIN_BIND_FAILURES.incrementAndGet();
            return null;
        }
        ImprovedNoise[] octaves = pa.dfc$getNoiseLevels();
        DoubleList amplitudes = pa.dfc$getAmplitudes();
        double lowestFreqInputFactor = pa.dfc$getLowestFreqInputFactor();
        double lowestFreqValueFactor = pa.dfc$getLowestFreqValueFactor();

        if (octaves == null || amplitudes == null || octaves.length != amplitudes.size()) {
            // Vanilla invariant: noiseLevels.length == amplitudes.size(). If a mod
            // has tampered with this, fall back rather than emit corrupt bytecode.
            return null;
        }

        // Vanilla loop: for i in [0, octaves.length): if octaves[i] != null, accumulate
        // amplitudes[i] * d3 * d2 where d2 (= valueFactor for octave i) is
        // lowestFreqValueFactor * 0.5^i and d1 (= inputFactor for octave i) is
        // lowestFreqInputFactor * 2^i. d1/d2 are stepped UNCONDITIONALLY each iteration,
        // so the per-octave constants are computed against the original index even
        // when an octave is skipped.
        List<ImprovedNoise> active = new ArrayList<>(octaves.length);
        List<Double> inputFactors = new ArrayList<>(octaves.length);
        List<Double> ampValueFactors = new ArrayList<>(octaves.length);
        int skipped = 0;
        for (int i = 0; i < octaves.length; i++) {
            ImprovedNoise oct = octaves[i];
            double amp = amplitudes.getDouble(i);
            // Match vanilla's "improvednoise != null" guard exactly: a non-null entry
            // with an apparently-zero amplitude is still emitted (vanilla would too,
            // contributing 0.0). We strip only the null entries.
            if (oct == null) {
                skipped++;
                continue;
            }
            double inputFactor = lowestFreqInputFactor * Math.pow(2.0, i);
            double valueFactor = lowestFreqValueFactor * Math.pow(0.5, i);
            active.add(oct);
            inputFactors.add(inputFactor);
            ampValueFactors.add(amp * valueFactor);
        }
        OCTAVES_SKIPPED.addAndGet(skipped);

        ImprovedNoise[] activeArr = active.toArray(new ImprovedNoise[0]);
        double[] inputArr = new double[inputFactors.size()];
        double[] ampArr = new double[ampValueFactors.size()];
        for (int i = 0; i < inputArr.length; i++) {
            inputArr[i] = inputFactors.get(i);
            ampArr[i] = ampValueFactors.get(i);
        }
        return new NoiseSpec.PerlinSpec(activeArr, inputArr, ampArr, inputCoordScale);
    }
}
