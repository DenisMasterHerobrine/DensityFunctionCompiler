package dev.denismasterherobrine.densityfunctioncompiler.compiler.codegen;

import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-compilation collector that hands out stable indices for every "extern" reference
 * the IR captures: noise samplers, opaque DensityFunctions (mod-provided, Markers,
 * Beardifier, EndIslands), spline ASTs, and double constants.
 *
 * <p>Identity-keyed: two textually identical IR references that point to the same
 * {@link NormalNoise} instance share an index, while two distinct instances stay
 * separate. This matches the runtime semantics of vanilla DensityFunctions (where
 * {@code NormalNoise} doesn't override equals).
 *
 * <p>{@link #intern(double)} doubles use value equality though — there's no point in
 * duplicating the literal {@code 0.0}.
 *
 * <p>The pool is finalised by {@link #finishConstants()}, {@link #finishNoises()} etc.,
 * which return the snapshot arrays the generated class needs in its constructor.
 */
public final class ConstantPool {

    private final List<Double> constants = new ArrayList<>();
    private final Map<Long, Integer> constantIndex = new java.util.HashMap<>();

    private final List<NormalNoise> noises = new ArrayList<>();
    private final IdentityHashMap<NormalNoise, Integer> noiseIndex = new IdentityHashMap<>();

    private final List<DensityFunction> externs = new ArrayList<>();
    private final IdentityHashMap<DensityFunction, Integer> externIndex = new IdentityHashMap<>();

    private final List<Object> splines = new ArrayList<>();

    /** Intern a double, returning its slot in the {@code constants} array. */
    public int intern(double value) {
        long bits = Double.doubleToRawLongBits(value);
        Integer idx = constantIndex.get(bits);
        if (idx != null) return idx;
        int next = constants.size();
        constants.add(value);
        constantIndex.put(bits, next);
        return next;
    }

    /** Intern a NormalNoise by identity. */
    public int internNoise(NormalNoise noise) {
        Integer idx = noiseIndex.get(noise);
        if (idx != null) return idx;
        int next = noises.size();
        noises.add(noise);
        noiseIndex.put(noise, next);
        return next;
    }

    /** Intern an opaque DensityFunction (Marker, Invoke, EndIslands, etc.) by identity. */
    public int internExtern(DensityFunction df) {
        Integer idx = externIndex.get(df);
        if (idx != null) return idx;
        int next = externs.size();
        externs.add(df);
        externIndex.put(df, next);
        return next;
    }

    /** Append a spline blob (no deduplication; splines compare by structure not identity). */
    public int internSpline(Object spline) {
        int next = splines.size();
        splines.add(spline);
        return next;
    }

    public double[] finishConstants() {
        double[] out = new double[constants.size()];
        for (int i = 0; i < out.length; i++) out[i] = constants.get(i);
        return out;
    }

    public NormalNoise[] finishNoises() {
        return noises.toArray(new NormalNoise[0]);
    }

    public DensityFunction[] finishExterns() {
        return externs.toArray(new DensityFunction[0]);
    }

    public Object[] finishSplines() {
        return splines.toArray();
    }

    public int constantCount()  { return constants.size(); }
    public int noiseCount()     { return noises.size(); }
    public int externCount()    { return externs.size(); }
    public int splineCount()    { return splines.size(); }

    public DensityFunction extern(int idx) { return externs.get(idx); }
    public NormalNoise noise(int idx)      { return noises.get(idx); }
    public double constant(int idx)        { return constants.get(idx); }
}
