package dev.denismasterherobrine.densityfunctioncompiler.natives;

/**
 * Codegen always emits JNI fast paths where applicable. If {@code dfc-natives} failed to load,
 * handles stay zero and bytecode falls back to Java at runtime.
 */
public final class CodegenNativeNoise {

    public static boolean enabled() {
        return true;
    }

    public static boolean emitNativeOps() {
        return true;
    }

    private CodegenNativeNoise() {}
}
