package dev.denismasterherobrine.densityfunctioncompiler.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * NeoForge common config for experimental DFC paths. Performance-sensitive defaults
 * are {@code false}: prior universal bytecode / mixin hooks on hot paths caused
 * large regressions (see {@code Codegen#emitInvoke} and aquifer redirect notes).
 */
public final class DfcConfig {

    public static final ModConfigSpec COMMON_SPEC;
    public static final ModConfigSpec.BooleanValue CACHE_WRAPPER_DIRECT_READ;
    public static final ModConfigSpec.BooleanValue EXPERIMENTAL_AQUIFER_FUSION;
    public static final ModConfigSpec.BooleanValue BEARDIFIER_SPECIALIZE;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.comment("DensityFunctionCompiler experimental features (safe defaults: off).");

        CACHE_WRAPPER_DIRECT_READ = b
                .comment("Reserved: chunk-cache direct read from compiled code (not active; mixins exist for future targeted use).")
                .define("cacheWrapperDirectRead", false);

        EXPERIMENTAL_AQUIFER_FUSION = b
                .comment("Reserved: aquifer compute de-duplication (mixin not loaded by default; enable when re-registered).")
                .define("experimentalAquiferFusion", false);

        BEARDIFIER_SPECIALIZE = b
                .comment("Reserved for future Beardifier IR specialization; currently has no effect.")
                .define("beardifierSpecialize", false);

        COMMON_SPEC = b.build();
    }

    public static boolean cacheWrapperDirectRead() {
        return CACHE_WRAPPER_DIRECT_READ.get();
    }

    public static boolean experimentalAquiferFusion() {
        return EXPERIMENTAL_AQUIFER_FUSION.get();
    }

    public static boolean beardifierSpecialize() {
        return BEARDIFIER_SPECIALIZE.get();
    }

    private DfcConfig() {}
}
