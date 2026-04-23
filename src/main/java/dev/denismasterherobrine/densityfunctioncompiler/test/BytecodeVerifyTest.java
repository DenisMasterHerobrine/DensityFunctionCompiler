package dev.denismasterherobrine.densityfunctioncompiler.test;

import dev.denismasterherobrine.densityfunctioncompiler.DensityFunctionCompiler;
import dev.denismasterherobrine.densityfunctioncompiler.compiler.Compiler;
import dev.denismasterherobrine.densityfunctioncompiler.compiler.codegen.CompiledDensityFunction;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.CheckClassAdapter;

import java.io.PrintWriter;

/**
 * Runs ASM's {@link CheckClassAdapter#verify(ClassReader, ClassLoader, boolean, PrintWriter)}
 * on bytecode produced by {@link Compiler#compileWithDetail} for shapes that historically
 * tripped the JVM verifier (RangeChoice / FastRangeChoice branch merges, lattice slab locals,
 * BlendDensity stack).
 */
public final class BytecodeVerifyTest {

    private BytecodeVerifyTest() {}

    public static void verify() {
        DensityFunction k0 = DensityFunctions.constant(0.0);
        DensityFunction k1 = DensityFunctions.constant(1.0);
        DensityFunction k2 = DensityFunctions.constant(2.5);
        DensityFunction yc = DensityFunctions.yClampedGradient(-64, 320, -1.0, 1.0);

        verifyCase(DensityFunctions.rangeChoice(yc, -0.25, 0.25, k0, k1), "rangeChoice_flat_arms");

        DensityFunction nestedIn = DensityFunctions.rangeChoice(yc, -0.25, 0.25, k0, k1);
        DensityFunction nestedOut = DensityFunctions.rangeChoice(yc, 0.5, 1.0, k2, k0);
        verifyCase(DensityFunctions.rangeChoice(yc, -5.0, 5.0, nestedIn, nestedOut), "nested_rangeChoice");

        DensityFunction innerProbe = DensityFunctions.rangeChoice(yc, -1.0, 0.0, k0, k2);
        verifyCase(DensityFunctions.rangeChoice(innerProbe, 0.5, 2.0, k1, k0), "rangeChoice_on_rangeChoice_input");

        DensityFunction inner = DensityFunctions.rangeChoice(yc, -0.25, 0.25,
                DensityFunctions.mul(yc, k2),
                DensityFunctions.add(yc.abs(), k1));
        verifyCase(DensityFunctions.blendDensity(inner), "blendDensity_nested_rangeChoice");

        DensityFunction shared = yc.abs();
        DensityFunction cseStress = DensityFunctions.add(
                DensityFunctions.mul(shared, shared),
                DensityFunctions.add(shared, k1));
        verifyCase(cseStress, "cse_stress");

        DensityFunction yChain = DensityFunctions.add(
                DensityFunctions.mul(
                        DensityFunctions.yClampedGradient(-64, 320, 0.0, 1.0),
                        DensityFunctions.constant(2.0)),
                DensityFunctions.constant(0.5));
        DensityFunction squared = DensityFunctions.mul(yChain, yChain);
        verifyCase(DensityFunctions.add(DensityFunctions.constant(1.0), squared), "lattice_y_chain");

        DensityFunctionCompiler.LOGGER.info("BytecodeVerifyTest: OK");
    }

    private static void verifyCase(DensityFunction df, String name) {
        Compiler.Result r = Compiler.compileWithDetail(df);
        if (r == null) {
            throw new AssertionError("compilation failed for case " + name);
        }
        ClassLoader loader = CompiledDensityFunction.class.getClassLoader();
        try {
            CheckClassAdapter.verify(
                    new ClassReader(r.bytecode()),
                    loader,
                    false,
                    new PrintWriter(System.err, true));
        } catch (RuntimeException e) {
            throw new AssertionError("bytecode verification failed for " + name, e);
        }
    }
}
