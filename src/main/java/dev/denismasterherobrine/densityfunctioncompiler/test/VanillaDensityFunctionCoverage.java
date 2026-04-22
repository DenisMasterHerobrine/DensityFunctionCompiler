package dev.denismasterherobrine.densityfunctioncompiler.test;

import dev.denismasterherobrine.densityfunctioncompiler.compiler.McDensityFunctionClassNames;
import dev.denismasterherobrine.densityfunctioncompiler.compiler.codegen.ConstantPool;
import dev.denismasterherobrine.densityfunctioncompiler.compiler.ir.IRNode;
import dev.denismasterherobrine.densityfunctioncompiler.compiler.ir.IRBuilder;
import dev.denismasterherobrine.densityfunctioncompiler.compiler.ir.IrTreeSupport;
import dev.denismasterherobrine.densityfunctioncompiler.compiler.pipeline.CompilingVisitor;
import net.minecraft.core.Registry;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.synth.BlendedNoise;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Audits that {@link IRNode.Invoke} targets are expected (identity placeholders,
 * unhandled vanilla helpers) rather than silent {@link IRBuilder} fallthrough of a
 * vanilla {@code DensityFunction} we forgot to case-match.
 */
public final class VanillaDensityFunctionCoverage {

    private VanillaDensityFunctionCoverage() {}

    public static Set<Class<?>> invokeExternClasses(IRNode root, ConstantPool pool) {
        Set<Class<?>> out = new LinkedHashSet<>();
        IrTreeSupport.visitUnique(root, n -> {
            if (n instanceof IRNode.Invoke iv) {
                out.add(pool.extern(iv.externIndex()).getClass());
            }
        });
        return out;
    }

    /**
     * @return error strings for each {@code net.minecraft.…} class referenced by
     * {@link IRNode.Invoke} that is not in the allow-list of intentional externs.
     */
    public static List<String> findUnexpectedVanillaInvokes(IRNode root, ConstantPool pool) {
        List<String> issues = new ArrayList<>();
        for (Class<?> c : invokeExternClasses(root, pool)) {
            if (!c.getName().startsWith("net.minecraft.")) {
                continue;
            }
            if (isIntentionalVanillaInvoke(c)) {
                continue;
            }
            issues.add("unexpected vanilla IRNode.Invoke: " + c.getName());
        }
        return issues;
    }

    public static boolean isIntentionalVanillaInvoke(Class<?> c) {
        if (c == DensityFunctions.BlendAlpha.class) {
            return true;
        }
        if (c == DensityFunctions.BlendOffset.class) {
            return true;
        }
        if (c == DensityFunctions.BeardifierMarker.class) {
            return true;
        }
        if (c == DensityFunctions.Marker.class) {
            return true;
        }
        if (McDensityFunctionClassNames.DENSITY_FUNCTIONS_END_ISLAND.equals(c.getName())) {
            return true;
        }
        if ("net.minecraft.world.level.levelgen.Beardifier".equals(c.getName())) {
            return true;
        }
        return false;
    }

    public record BatteryResult(int casesRun, int passed, List<String> failures) {}

    /**
     * Build IR for hand-assembled trees (no server). Fails a case if build throws or an
     * unexpected {@code net.minecraft.…} {@link IRNode.Invoke} appears.
     */
    public static BatteryResult runFactoryBattery() {
        List<String> failures = new ArrayList<>();
        int run = 0;
        int passed = 0;
        CompilingVisitor vis = CompilingVisitor.global();
        for (DensityFunction df : buildFactoryDfs()) {
            run++;
            String label = df.getClass().getName();
            try {
                ConstantPool pool = new ConstantPool();
                IRBuilder b = new IRBuilder(pool, vis);
                IRNode root = b.build(df);
                List<String> inv = findUnexpectedVanillaInvokes(root, pool);
                if (!inv.isEmpty()) {
                    failures.add(label + ": " + String.join(", ", inv));
                } else {
                    passed++;
                }
            } catch (Throwable t) {
                failures.add(label + ": " + t);
            }
        }
        return new BatteryResult(run, passed, failures);
    }

    private static List<DensityFunction> buildFactoryDfs() {
        List<DensityFunction> d = new ArrayList<>();
        DensityFunction k0 = DensityFunctions.constant(0.0);
        DensityFunction k1 = DensityFunctions.constant(1.0);
        DensityFunction k2 = DensityFunctions.constant(2.0);
        d.add(k1);
        d.add(DensityFunctions.add(k1, k2));
        d.add(DensityFunctions.yClampedGradient(-64, 320, 0.0, 1.0));
        d.add(DensityFunctions.add(k1, k1).clamp(0.0, 1.0));
        d.add(DensityFunctions.rangeChoice(k0, 0.0, 0.5, k1, k2));
        d.add(DensityFunctions.add(k1, k1).abs());
        d.add(BlendedNoise.createUnseeded(0.1, 0.2, 0.3, 0.4, 2.0));
        d.add(DensityFunctions.endIslands(0L));
        d.add(new DensityFunctions.Marker(DensityFunctions.Marker.Type.Cache2D, k1));
        d.add(DensityFunctions.blendDensity(k1));
        DensityFunction g1 = DensityFunctions.yClampedGradient(-64, 64, 0.0, 1.0);
        DensityFunction g2 = DensityFunctions.yClampedGradient(0, 320, 0.0, 2.0);
        d.add(DensityFunctions.min(g1, g2));
        d.add(DensityFunctions.max(g1, g2));
        d.add(DensityFunctions.mul(g1, g2));
        return d;
    }

    private static final int MAX_REGISTRY_INVOKE_AUDIT = 4096;

    /**
     * For each registry entry (first {@code MAX_REGISTRY_INVOKE_AUDIT} holders) runs
     * {@link IRBuilder#build} and records unwrap/build failures or unexpected
     * {@link IRNode.Invoke} targets.
     */

    public static BatteryResult runRegistryReferenceAudit(Registry<DensityFunction> registry) {
        List<String> failures = new ArrayList<>();
        int run = 0;
        int passed = 0;
        CompilingVisitor vis = CompilingVisitor.global();
        int n = 0;
        for (var h : registry.holders().toList()) {
            if (n++ >= MAX_REGISTRY_INVOKE_AUDIT) {
                break;
            }
            run++;
            DensityFunction df;
            try {
                df = h.value();
            } catch (Throwable t) {
                failures.add(h.key().location() + " (unwrap): " + t);
                continue;
            }
            try {
                ConstantPool pool = new ConstantPool();
                IRNode root = new IRBuilder(pool, vis).build(df);
                List<String> inv = findUnexpectedVanillaInvokes(root, pool);
                if (!inv.isEmpty()) {
                    failures.add(h.key().location() + ": " + String.join(", ", inv));
                } else {
                    passed++;
                }
            } catch (Throwable t) {
                failures.add(h.key().location() + " (build): " + t);
            }
        }
        return new BatteryResult(run, passed, failures);
    }
}
