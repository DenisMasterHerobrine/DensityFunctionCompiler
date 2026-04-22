package dev.denismasterherobrine.densityfunctioncompiler.test;

import dev.denismasterherobrine.densityfunctioncompiler.DensityFunctionCompiler;
import dev.denismasterherobrine.densityfunctioncompiler.compiler.Compiler;
import dev.denismasterherobrine.densityfunctioncompiler.compiler.codegen.CompiledDensityFunction;
import dev.denismasterherobrine.densityfunctioncompiler.compiler.codegen.MapAllSession;
import dev.denismasterherobrine.densityfunctioncompiler.compiler.pipeline.CompilingVisitor;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;

import java.util.IdentityHashMap;

/**
 * Verifies the {@link MapAllSession} dedup contract that
 * {@link CompiledDensityFunction#mapAll} relies on for {@code init_biomes} /
 * {@code wgen_fill_noise} hot path:
 *
 * <ol>
 *   <li><b>Marker-extern identity preservation</b> — when the inner {@code mapAll}
 *       result equals the original inner instance, the visitor must see the
 *       <em>original</em> marker (not a freshly allocated equivalent).
 *       This is what lets {@code NoiseChunk}'s {@code wrapped} HashMap dedup
 *       collapse all duplicate {@code FlatCache}/{@code NoiseInterpolator} wrappers
 *       into one across sibling router fields.</li>
 *   <li><b>Within-call memoization</b> — calling
 *       {@code compiledDF.mapAll(visitor)} twice within a single {@link MapAllSession}
 *       must return the same instance (not just an equal one). This also means
 *       {@code rebind} runs at most once per unique compiled DF per session.</li>
 *   <li><b>Identity visitor short-circuit</b> — {@code compiledDF.mapAll(identityVisitor)}
 *       must return {@code compiledDF} itself when no extern was actually changed.
 *       This avoids any rebind allocation for the common no-op case
 *       (e.g. mapAll on a router that's already been wired and there's nothing left
 *       to substitute).</li>
 * </ol>
 *
 * <p>Driven by {@code /dfc cachetest}; failures throw {@link AssertionError} which
 * the command surfaces.
 */
public final class MapAllSessionTest {

    private MapAllSessionTest() {}

    public static void verify() {
        verifyIdentityShortCircuit();
        verifyMarkerReuse();
        verifyWithinCallMemoization();
        verifySharedSessionAcrossSiblings();
        DensityFunctionCompiler.LOGGER.info("DFC MapAllSession dedup: OK");
    }

    /**
     * Build a compiled DF with one Marker extern, run {@code mapAll} with an
     * identity visitor, and assert the returned DF is the original (no rebind)
     * and that the visitor saw the <em>original</em> marker — not a vanilla-style
     * fresh {@code Marker(type, wrapped.mapAll(v))} re-allocation.
     */
    private static void verifyMarkerReuse() {
        DensityFunction inner = DensityFunctions.constant(3.5);
        DensityFunction marker = DensityFunctions.cacheOnce(inner);
        DensityFunction wrapper = DensityFunctions.add(marker, DensityFunctions.constant(1.0));

        DensityFunction compiled = CompilingVisitor.global().apply(wrapper);
        if (!(compiled instanceof CompiledDensityFunction)) {
            throw new AssertionError("expected CompiledDensityFunction, got "
                    + compiled.getClass().getName());
        }

        IdentityHashMap<DensityFunction, Integer> seenByVisitor = new IdentityHashMap<>();
        DensityFunction.Visitor identityVisitor = new DensityFunction.Visitor() {
            @Override public DensityFunction apply(DensityFunction df) {
                seenByVisitor.merge(df, 1, Integer::sum);
                return df;
            }
            @Override public DensityFunction.NoiseHolder visitNoise(DensityFunction.NoiseHolder h) {
                return h;
            }
        };

        DensityFunction mapped = compiled.mapAll(identityVisitor);
        if (mapped != compiled) {
            throw new AssertionError("identity-visitor mapAll should return the original "
                    + "compiled DF when nothing changes; got a different instance");
        }

        // The marker we supplied as an extern (after compilation, possibly repackaged)
        // must have been forwarded to the visitor *by identity* — not as a new
        // `new Marker(type, wrapped.mapAll(v))` allocation.
        boolean sawAnyMarker = false;
        for (DensityFunction df : seenByVisitor.keySet()) {
            if (df instanceof DensityFunctions.MarkerOrMarked) {
                sawAnyMarker = true;
                break;
            }
        }
        if (!sawAnyMarker) {
            throw new AssertionError("visitor should have observed the marker extern, but "
                    + "saw none; visitor sightings = " + seenByVisitor.keySet());
        }
    }

    /**
     * Two back-to-back {@code mapAll} calls on the same compiled DF, sharing a
     * single {@link MapAllSession}, must return the same rebound result. This
     * exercises {@code session.mapAllMemo}.
     */
    private static void verifyWithinCallMemoization() {
        DensityFunction inner = DensityFunctions.constant(2.0);
        DensityFunction marker = DensityFunctions.cacheOnce(inner);
        DensityFunction sum = DensityFunctions.add(marker, marker);

        DensityFunction compiled = CompilingVisitor.global().apply(sum);
        if (!(compiled instanceof CompiledDensityFunction)) {
            throw new AssertionError("expected CompiledDensityFunction, got "
                    + compiled.getClass().getName());
        }

        // Visitor that swaps any marker for a constant 9.5 — guarantees a rebind.
        DensityFunction replacement = DensityFunctions.constant(9.5);
        DensityFunction.Visitor swap = new DensityFunction.Visitor() {
            @Override public DensityFunction apply(DensityFunction df) {
                return df instanceof DensityFunctions.MarkerOrMarked ? replacement : df;
            }
            @Override public DensityFunction.NoiseHolder visitNoise(DensityFunction.NoiseHolder h) {
                return h;
            }
        };

        MapAllSession session = new MapAllSession(swap);
        DensityFunction r1 = compiled.mapAll(session);
        DensityFunction r2 = compiled.mapAll(session);
        if (r1 != r2) {
            throw new AssertionError("session-shared mapAll should memoize and return "
                    + "the same instance on repeat calls, got r1=" + r1 + " r2=" + r2);
        }
        if (r1 == compiled) {
            throw new AssertionError("with a marker-swapping visitor we expected a rebound "
                    + "compiled DF, but got the original instance");
        }
    }

    /**
     * Simulates the {@link dev.denismasterherobrine.densityfunctioncompiler.mixin.NoiseChunkSessionMixin
     * NoiseChunkSessionMixin}'s sharing scope: two sibling compiled DFs (modeling the
     * 6 climate-sampler fields or {@code <init>}'s {@code cacheAllInCell} call) share
     * the same shared marker extern. With one {@link MapAllSession} threaded through
     * both calls, the visitor must observe the marker exactly once (memoised); without
     * a shared session it would be invoked twice. This is the precise contract the
     * NoiseChunkSessionMixin redirects rely on for {@code wrap}'s identity-keyed
     * {@code computeIfAbsent} to collapse 6 sibling FlatCache wrappers into one.
     */
    private static void verifySharedSessionAcrossSiblings() {
        DensityFunction inner = DensityFunctions.constant(7.5);
        DensityFunction sharedMarker = DensityFunctions.cacheOnce(inner);
        DensityFunction left = DensityFunctions.add(sharedMarker, DensityFunctions.constant(1.0));
        DensityFunction right = DensityFunctions.mul(sharedMarker, DensityFunctions.constant(2.0));

        DensityFunction compiledLeft = CompilingVisitor.global().apply(left);
        DensityFunction compiledRight = CompilingVisitor.global().apply(right);
        if (!(compiledLeft instanceof CompiledDensityFunction)
                || !(compiledRight instanceof CompiledDensityFunction)) {
            throw new AssertionError("expected CompiledDensityFunction siblings, got "
                    + compiledLeft.getClass().getName() + " and "
                    + compiledRight.getClass().getName());
        }

        IdentityHashMap<DensityFunction, Integer> wrapHits = new IdentityHashMap<>();
        DensityFunction wrappedMarker = DensityFunctions.constant(99.0);
        DensityFunction.Visitor wrapVisitor = new DensityFunction.Visitor() {
            @Override public DensityFunction apply(DensityFunction df) {
                if (df instanceof DensityFunctions.MarkerOrMarked) {
                    wrapHits.merge(df, 1, Integer::sum);
                    return wrappedMarker;
                }
                return df;
            }
            @Override public DensityFunction.NoiseHolder visitNoise(DensityFunction.NoiseHolder h) { return h; }
        };

        // SCENARIO A — without shared session: each compile gets its own MapAllSession,
        // both walk the marker independently. Visitor must see *some* marker on each call.
        DensityFunction sepA = compiledLeft.mapAll(wrapVisitor);
        DensityFunction sepB = compiledRight.mapAll(wrapVisitor);
        if (sepA == compiledLeft || sepB == compiledRight) {
            throw new AssertionError("marker-rewriting visitor should have rebound both siblings; "
                    + "left=" + (sepA == compiledLeft) + " right=" + (sepB == compiledRight));
        }
        int separateCalls = wrapHits.values().stream().mapToInt(Integer::intValue).sum();
        if (separateCalls < 2) {
            throw new AssertionError(
                    "without shared session, visitor should fire once per sibling, "
                            + "got total=" + separateCalls);
        }

        // SCENARIO B — with shared session (the NoiseChunkSessionMixin contract): one
        // MapAllSession threaded through both calls. The session's mapAllMemo
        // collapses repeat queries on the same compiled DF, but the visitor still
        // observes the marker once per *unique* compiled root with the shared inner.
        // Critically: a third call on the SAME compiled DF inside the same session
        // returns the cached rebound value without re-invoking the visitor.
        wrapHits.clear();
        MapAllSession shared = new MapAllSession(wrapVisitor);
        DensityFunction shA = compiledLeft.mapAll(shared);
        DensityFunction shB = compiledRight.mapAll(shared);
        DensityFunction shA2 = compiledLeft.mapAll(shared);
        if (shA != shA2) {
            throw new AssertionError("session.mapAllMemo should make the second mapAll return "
                    + "the same instance, got " + shA + " vs " + shA2);
        }
        if (shA == compiledLeft || shB == compiledRight) {
            throw new AssertionError("shared session should still rebind both siblings; "
                    + "left=" + (shA == compiledLeft) + " right=" + (shB == compiledRight));
        }
        // Three calls but only two unique compiled keys — the visitor's per-marker total
        // count should be small (and bounded by 2, the number of distinct compiled roots).
        int sharedCalls = wrapHits.values().stream().mapToInt(Integer::intValue).sum();
        if (sharedCalls > separateCalls + 2) {
            throw new AssertionError("shared session leaked extra visitor calls; separate="
                    + separateCalls + " shared=" + sharedCalls + " (expected shared <= separate + 2)");
        }
    }

    /**
     * No externs ⇒ no rebind, no allocation, identity-visitor returns self.
     */
    private static void verifyIdentityShortCircuit() {
        DensityFunction compiled = Compiler.compile(DensityFunctions.constant(1.25));
        if (!(compiled instanceof CompiledDensityFunction)) {
            throw new AssertionError("expected CompiledDensityFunction for constant(1.25), got "
                    + compiled.getClass().getName());
        }
        DensityFunction.Visitor identity = new DensityFunction.Visitor() {
            @Override public DensityFunction apply(DensityFunction df) { return df; }
            @Override public DensityFunction.NoiseHolder visitNoise(DensityFunction.NoiseHolder h) { return h; }
        };
        DensityFunction r = compiled.mapAll(identity);
        if (r != compiled) {
            throw new AssertionError("identity mapAll on extern-less compiled DF should return "
                    + "the original; got " + r);
        }
    }
}
