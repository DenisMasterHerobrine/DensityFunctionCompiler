package dev.denismasterherobrine.densityfunctioncompiler.compiler.codegen;

import dev.denismasterherobrine.densityfunctioncompiler.compiler.noise.BlendedNoiseSpec;
import dev.denismasterherobrine.densityfunctioncompiler.compiler.ir.CellLatticeOption;
import dev.denismasterherobrine.densityfunctioncompiler.compiler.ir.IRNode;
import dev.denismasterherobrine.densityfunctioncompiler.compiler.ir.RefCount;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * ASM emitter for the IR.
 *
 * <p>Layout of the generated class:
 * <pre>
 * public final class CompiledDF_N extends CompiledDensityFunction {
 *     public CompiledDF_N(double[] c, NormalNoise[] n, Object[] s, DensityFunction[] e,
 *                        double mn, double mx, MethodHandle[] hh, MethodHandle ctorMH) {
 *         super(c, n, s, e, mn, mx, hh, ctorMH);
 *     }
 *     public double compute(FunctionContext ctx) { ... straight-line bytecode ... }
 *     private static double helper_0(CompiledDensityFunction self, FunctionContext ctx) { ... }
 *     private static double helper_1(CompiledDensityFunction self, FunctionContext ctx) { ... }
 *     ...
 * }
 * </pre>
 *
 * <p>Note the absence of any {@code rebind} override or any opcode mentioning the
 * generated class's own internal name (i.e. no {@code NEW CompiledDF_N},
 * {@code INVOKESTATIC CompiledDF_N.helper_K}, etc.). Hidden classes are forbidden
 * from referring to themselves symbolically — the JVM rejects {@code defineHiddenClass}
 * with {@code NoClassDefFoundError} when the constant pool contains a CONSTANT_Class_info
 * matching the class's own name. We work around this by:
 * <ul>
 *   <li>Helper methods using {@code CompiledDensityFunction} (the supertype) for their
 *       {@code self} parameter rather than the hidden subclass.</li>
 *   <li>Helper call sites loading a {@link java.lang.invoke.MethodHandle} from the
 *       inherited {@code helperHandles[]} field and using {@code INVOKEVIRTUAL
 *       MethodHandle.invokeExact} — signature-polymorphic so the verifier doesn't
 *       enforce arg types, and the descriptor names only the supertype.</li>
 *   <li>The MH array being populated by {@link Compiler} after {@code defineHiddenClass}
 *       returns: the post-define {@link java.lang.invoke.MethodHandles.Lookup} can
 *       resolve {@code helper_N} on the new class without any symbolic name reference.</li>
 *   <li>Skipping the {@code rebind} override entirely (a hidden class cannot emit
 *       {@code NEW SelfClass}); the supertype's {@code rebind} instead routes
 *       through a {@link java.lang.invoke.MethodHandle} bound to the subclass
 *       constructor (passed in via the trailing {@code MethodHandle} ctor arg),
 *       which lets visitor-driven extern remaps reach inner Markers — critical
 *       for the {@code NoiseChunk} cell-cache wraps that vanilla worldgen
 *       depends on for both correctness and performance.</li>
 * </ul>
 *
 * <p>The {@code compute} method follows a stack-scheduled emission discipline:
 * <ul>
 *   <li>Block coordinates are loaded into local slots once at method entry: blockX -&gt; slot 2,
 *       blockY -&gt; slot 3, blockZ -&gt; slot 4 (each int).</li>
 *   <li>Spilled IR nodes (refcount &ge; 2) are computed once into a freshly allocated double
 *       slot and reloaded with {@code DLOAD} on subsequent uses.</li>
 *   <li>Single-use IR nodes leave their result on the operand stack — no store/reload.</li>
 *   <li>Nodes in the {@link Splitter}-supplied {@code extracted} set become standalone
 *       {@code helper_N} static methods on the same class. Call sites become a single
 *       {@code MethodHandle.invokeExact} dispatch, slashing the parent method's bytecode
 *       size and letting HotSpot inline the helpers back at runtime when they're hot.</li>
 * </ul>
 */
public final class Codegen {

    /**
     * Hard cap on the number of helper methods per generated class. The class
     * file's method count is u2, so the absolute limit is 65535; we cap much
     * lower because a class with thousands of methods is a sign that the IR
     * is pathologically large and the JIT will hate it anyway.
     */
    public static final int MAX_HELPERS = 1024;

    private static final String COMPILED_BASE_INTERNAL =
            Type.getInternalName(CompiledDensityFunction.class);
    private static final String NORMAL_NOISE_INTERNAL = "net/minecraft/world/level/levelgen/synth/NormalNoise";
    static final String IMPROVED_NOISE_INTERNAL = "net/minecraft/world/level/levelgen/synth/ImprovedNoise";
    private static final String DENSITY_FUNCTION_INTERNAL = "net/minecraft/world/level/levelgen/DensityFunction";
    private static final String DENSITY_FUNCTION_DESC = "L" + DENSITY_FUNCTION_INTERNAL + ";";
    private static final String FUNCTION_CONTEXT_INTERNAL =
            "net/minecraft/world/level/levelgen/DensityFunction$FunctionContext";
    private static final String CONTEXT_PROVIDER_INTERNAL =
            "net/minecraft/world/level/levelgen/DensityFunction$ContextProvider";
    private static final String METHOD_HANDLE_INTERNAL = "java/lang/invoke/MethodHandle";
    private static final String METHOD_HANDLE_ARRAY_DESC = "[Ljava/lang/invoke/MethodHandle;";
    private static final String OBJECT_ARRAY_DESC = "[Ljava/lang/Object;";
    private static final String IMPROVED_NOISE_DESC = "L" + IMPROVED_NOISE_INTERNAL + ";";
    private static final String RUNTIME_INTERNAL =
            "dev/denismasterherobrine/densityfunctioncompiler/compiler/runtime/Runtime";
    private static final String MTH_INTERNAL = "net/minecraft/util/Mth";
    private static final String NOISE5_DESC = "(DDDDD)D";

    /**
     * Constructor descriptor used both by {@link #emitConstructor} and by
     * {@link dev.denismasterherobrine.densityfunctioncompiler.compiler.Compiler}
     * reflection. The trailing {@code MethodHandle} is the bound constructor
     * itself, threaded through so {@link CompiledDensityFunction#rebind} can
     * allocate fresh instances without {@code NEW SelfClass} (which hidden
     * classes are forbidden from emitting).
     *
     * <p>The {@code Object[]} after the splines array is the per-noise per-octave
     * {@link net.minecraft.world.level.levelgen.synth.ImprovedNoise} payload —
     * see {@link CompiledDensityFunction#noiseOctaves}. The generated subclass
     * unloads it into its own typed final fields in its constructor body.
     */
    public static final String CTOR_DESC =
            "([D[L" + NORMAL_NOISE_INTERNAL + ";[Ljava/lang/Object;[Ljava/lang/Object;[L"
                    + DENSITY_FUNCTION_INTERNAL + ";DD" + METHOD_HANDLE_ARRAY_DESC
                    + "L" + METHOD_HANDLE_INTERNAL + ";)V";

    /**
     * Helper static methods all share this descriptor — first arg is the supertype
     * rather than the hidden class itself so the call-site descriptor never names
     * a hidden class (the JVM rejects hidden-class self-references in the constant
     * pool).
     */
    public static final String HELPER_DESC =
            "(L" + COMPILED_BASE_INTERNAL + ";L" + FUNCTION_CONTEXT_INTERNAL + ";)D";

    /**
     * When {@code true} (default), helper call sites are emitted as
     * {@code INVOKEDYNAMIC} bound to {@link CompiledDensityFunction#bootstrapHelper};
     * when {@code false}, the legacy {@code helperHandles[idx].invokeExact} sequence
     * is emitted instead. The fallback path stays in place for one release as
     * insurance against unforeseen {@link LinkageError}s on hidden-class indy bsm
     * resolution; opt-out with {@code -Ddfc.indy_helpers=false}.
     */
    public static final boolean INDY_HELPERS_ENABLED =
            !"false".equalsIgnoreCase(System.getProperty("dfc.indy_helpers", "true"));

    /**
     * When {@code true} (default), {@link CellLatticeOption#analyze} runs and, if it
     * finds a worthwhile axis-only hoist, the codegen emits the {@code lattice_y} /
     * {@code lattice_inner} helpers plus a {@code fillArray} override that drives the
     * NoiseChunk triple loop with the precomputed Y-slab cached once per Y position.
     * Opt-out with {@code -Ddfc.cell_lattice=false}; the scalar
     * {@link CompiledDensityFunction#fillArray} fallback then stays in effect and is
     * exercised by {@code ParitySelfTest}.
     *
     * <p>The lattice path uses {@code INVOKEDYNAMIC + ConstantCallSite} for the
     * helper dispatch unconditionally — a hidden class cannot {@code INVOKESTATIC}
     * its own static methods symbolically, and we don't want to extend the
     * {@code helperHandles[]} array's contract for this. When
     * {@link #INDY_HELPERS_ENABLED} is false the existing {@code helper_<idx>} sites
     * still go through the legacy MH dispatch; only {@code lattice_y} /
     * {@code lattice_inner} ride indy.
     */
    public static final boolean CELL_LATTICE_ENABLED =
            !"false".equalsIgnoreCase(System.getProperty("dfc.cell_lattice", "true"));

    /** Internal name of {@code net.minecraft.world.level.levelgen.NoiseChunk} (vanilla). */
    static final String NOISE_CHUNK_INTERNAL = "net/minecraft/world/level/levelgen/NoiseChunk";
    /** Reference desc for a {@code NoiseChunk}. */
    static final String NOISE_CHUNK_DESC = "L" + NOISE_CHUNK_INTERNAL + ";";

    /** Method name of the cell-lattice Y-only helper. */
    public static final String LATTICE_Y_NAME = "lattice_y";
    /** Method name of the cell-lattice inner helper (takes precomputed Y as 3rd arg). */
    public static final String LATTICE_INNER_NAME = "lattice_inner";
    /** {@code (CompiledDensityFunction, FunctionContext, double) -> double} */
    public static final String LATTICE_INNER_DESC =
            "(L" + COMPILED_BASE_INTERNAL + ";L" + FUNCTION_CONTEXT_INTERNAL + ";D)D";

    /** Internal name of {@link CompiledDensityFunction}, used by the indy bsm handle. */
    private static final String BOOTSTRAP_OWNER = COMPILED_BASE_INTERNAL;
    /**
     * ASM {@link Handle} pointing at {@link CompiledDensityFunction#bootstrapHelper}.
     *
     * <p>The bsm signature is the standard 3-arg shape — Lookup, invokedName,
     * invokedType — with no extra static bsm args. The helper's identity is encoded
     * entirely in the {@code invokedName} string at the call site (e.g.
     * {@code "helper_5"}), which keeps the same bsm reusable for the cell-lattice
     * helpers ({@code "lattice_y"}, {@code "lattice_inner"}) introduced by Phase 2 —
     * those use a different {@link java.lang.invoke.MethodType} but the same lookup
     * mechanism.
     */
    static final Handle HELPER_BSM = new Handle(
            Opcodes.H_INVOKESTATIC,
            BOOTSTRAP_OWNER,
            "bootstrapHelper",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
            false);

    private Codegen() {}

    public static Result emit(String classInternalName, IRNode root,
                              RefCount.Result rc, Set<IRNode> extracted, ConstantPool pool,
                              double minVal, double maxVal) {

        // Hidden classes lose their declared name once defined: the JVM does NOT resolve
        // symbolic INVOKESTATIC self-references (it goes through the classloader, which
        // can't find the unnamed hidden class and throws NoClassDefFoundError). We
        // sidestep this by routing helper calls through MethodHandle.invokeExact off
        // the inherited `helperMHs` field — see HelperRegistry.emitHelperCall.
        //
        // ASM's stock getCommonSuperClass calls Class.forName with the system class loader,
        // which would also choke on the in-progress class if frame computation ever
        // needed to merge a self-typed value. Override to short-circuit those merges.
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                boolean isSelf1 = type1.equals(classInternalName);
                boolean isSelf2 = type2.equals(classInternalName);
                if (isSelf1 && isSelf2) return classInternalName;
                if (isSelf1) return super.getCommonSuperClass(COMPILED_BASE_INTERNAL, type2);
                if (isSelf2) return super.getCommonSuperClass(type1, COMPILED_BASE_INTERNAL);
                return super.getCommonSuperClass(type1, type2);
            }

            @Override
            protected ClassLoader getClassLoader() {
                return CompiledDensityFunction.class.getClassLoader();
            }
        };
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                classInternalName, null, COMPILED_BASE_INTERNAL, null);

        // Declare per-noise per-octave ImprovedNoise fields so the inlined emission
        // path can GETFIELD them by name rather than going through the inherited
        // Object[] noiseOctaves with AALOAD+CHECKCAST on every call.
        emitNoiseFields(cw, pool);
        // ext_i copies externs[i] for fast child dispatch (avoids aaload on nested markers).
        emitExternFields(cw, pool);
        emitConstructor(cw, classInternalName, pool);
        // Note: rebind() is implemented in the supertype using the constructor MethodHandle
        // we thread through; we deliberately do NOT emit a rebind override here because that
        // would require a `NEW classInternalName` instruction, which hidden classes cannot
        // emit (the JVM rejects defineHiddenClass when the constant pool names the hidden
        // class itself).

        HelperRegistry helpers = new HelperRegistry(cw, classInternalName, pool, rc, extracted);
        emitCompute(cw, classInternalName, root, helpers);
        helpers.drain();

        // Lattice plan (Tier B5+B6). Computed AFTER the regular helper drain so the
        // helper indices we hand out for `lattice_y` / `lattice_inner` don't fight with
        // the per-spill helper index allocator. The plan is purely a function of the IR
        // shape, so any same-fingerprint cache hit will receive an identical plan and
        // the helpers we emit now stay in lock-step with the cached bytecode.
        boolean latticeEmitted = false;
        if (CELL_LATTICE_ENABLED && !(root instanceof IRNode.Const)) {
            var planOpt = CellLatticeOption.analyze(root);
            if (planOpt.isPresent()) {
                CellLatticeOption.LatticePlan plan = planOpt.get();
                emitLatticeYHelper(cw, classInternalName, plan, helpers);
                emitLatticeInnerHelper(cw, classInternalName, root, plan, helpers);
                emitLatticeFillArrayOverride(cw, classInternalName);
                latticeEmitted = true;
            }
        }

        if (!latticeEmitted && root instanceof IRNode.Const c) {
            emitConstRootFillArrayOverride(cw, c.value());
        }

        cw.visitEnd();
        return new Result(cw.toByteArray(), helpers.emittedCount(), latticeEmitted);
    }

    /**
     * Bytecode + count of regular helper methods generated + whether a cell-lattice
     * fast path was emitted. {@code latticeEmitted} is purely diagnostic — it is
     * surfaced through {@link dev.denismasterherobrine.densityfunctioncompiler.compiler.pipeline.RouterPipeline}
     * so {@code /dfc stats} can report "lattice plans: K / N roots".
     */
    public record Result(byte[] bytecode, int helpersEmitted, boolean latticeEmitted) {}

    /* --------------------------------------------------------------------- */
    /* Constructor                                                           */
    /* --------------------------------------------------------------------- */

    /**
     * Declare a {@code private final ImprovedNoise} field for every active octave on
     * every interned NoiseSpec. The flat naming convention is {@code noise_S_B_O}
     * where {@code S} is the spec pool index, {@code B} is {@code 0} (first branch)
     * or {@code 1} (second branch), and {@code O} is the active-octave index inside
     * that branch. The constructor's PUTFIELD stream populates them in the same order
     * the {@link ConstantPool#finishNoiseOctaves()} payload uses.
     */
    private static void emitNoiseFields(ClassWriter cw, ConstantPool pool) {
        int specCount = pool.noiseSpecCount();
        for (int s = 0; s < specCount; s++) {
            var spec = pool.noiseSpec(s);
            int firstCount = spec.first().activeOctaves().length;
            int secondCount = spec.second().activeOctaves().length;
            for (int o = 0; o < firstCount; o++) {
                cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
                        noiseFieldName(s, 0, o), IMPROVED_NOISE_DESC, null, null).visitEnd();
            }
            for (int o = 0; o < secondCount; o++) {
                cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
                        noiseFieldName(s, 1, o), IMPROVED_NOISE_DESC, null, null).visitEnd();
            }
        }
        int bCount = pool.blendedNoiseSpecCount();
        for (int b = 0; b < bCount; b++) {
            for (int o = 0; o < BlendedNoiseSpec.MAIN_OCTAVES; o++) {
                cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
                        blendedFieldName(b, 0, o), IMPROVED_NOISE_DESC, null, null).visitEnd();
            }
            for (int o = 0; o < BlendedNoiseSpec.LIMIT_OCTAVES; o++) {
                cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
                        blendedFieldName(b, 1, o), IMPROVED_NOISE_DESC, null, null).visitEnd();
            }
            for (int o = 0; o < BlendedNoiseSpec.LIMIT_OCTAVES; o++) {
                cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
                        blendedFieldName(b, 2, o), IMPROVED_NOISE_DESC, null, null).visitEnd();
            }
        }
    }

    /** Stable per-octave field name used by both {@link #emitNoiseFields} and the codegen. */
    static String noiseFieldName(int specIdx, int branch, int activeOctaveIdx) {
        return "noise_" + specIdx + "_" + branch + "_" + activeOctaveIdx;
    }

    /**
     * Per-octave field for {@link dev.denismasterherobrine.densityfunctioncompiler.compiler.ir.IRNode.InlinedBlendedNoise}:
     * section {@code 0} = main 0..7, {@code 1} = min limit 0..15, {@code 2} = max limit 0..15.
     */
    static String blendedFieldName(int blendedSpecIdx, int section, int subIndex) {
        String tag = section == 0 ? "m" : (section == 1 ? "a" : "b");
        return "blnd_" + blendedSpecIdx + "_" + tag + "_" + subIndex;
    }

    /**
     * One {@code private final} reference per {@link ConstantPool#extern(int)} index.
     * Populated in {@link #emitConstructor} from the same {@code DensityFunction[]}
     * passed to {@code super}; mirrors {@code externs[i]} and stays correct across
     * {@link CompiledDensityFunction#rebind} (fresh instance, constructor re-runs).
     */
    static String externFieldName(int index) {
        return "ext_" + index;
    }

    private static void emitExternFields(ClassWriter cw, ConstantPool pool) {
        int n = pool.externCount();
        for (int i = 0; i < n; i++) {
            cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
                    externFieldName(i), DENSITY_FUNCTION_DESC, null, null).visitEnd();
        }
    }

    private static void emitConstructor(ClassWriter cw, String classInternalName, ConstantPool pool) {
        // (double[], NormalNoise[], Object[], Object[], DensityFunction[], double, double,
        //  MethodHandle[], MethodHandle)
        // Slot layout: this=0, constants=1, noises=2, splines=3, noiseOctaves=4,
        // externs=5, minValue=6/7, maxValue=8/9, helperHandles=10, constructorMH=11.
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", CTOR_DESC, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitVarInsn(Opcodes.ALOAD, 5);
        mv.visitVarInsn(Opcodes.DLOAD, 6);
        mv.visitVarInsn(Opcodes.DLOAD, 8);
        mv.visitVarInsn(Opcodes.ALOAD, 10);
        mv.visitVarInsn(Opcodes.ALOAD, 11);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, COMPILED_BASE_INTERNAL, "<init>", CTOR_DESC, false);

        for (int i = 0; i < pool.externCount(); i++) {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitVarInsn(Opcodes.ALOAD, 5);
            ldcIntStatic(mv, i);
            mv.visitInsn(Opcodes.AALOAD);
            mv.visitFieldInsn(Opcodes.PUTFIELD, classInternalName, externFieldName(i), DENSITY_FUNCTION_DESC);
        }

        // Populate per-octave fields from the noiseOctaves[] payload. Layout matches
        // ConstantPool.finishNoiseOctaves(): per-spec, first branch then second
        // branch, active octaves only.
        int cursor = 0;
        int specCount = pool.noiseSpecCount();
        for (int s = 0; s < specCount; s++) {
            var spec = pool.noiseSpec(s);
            int firstCount = spec.first().activeOctaves().length;
            int secondCount = spec.second().activeOctaves().length;
            for (int o = 0; o < firstCount; o++) {
                emitOctavePutfield(mv, classInternalName, s, 0, o, cursor++);
            }
            for (int o = 0; o < secondCount; o++) {
                emitOctavePutfield(mv, classInternalName, s, 1, o, cursor++);
            }
        }
        for (int b = 0; b < pool.blendedNoiseSpecCount(); b++) {
            for (int o = 0; o < BlendedNoiseSpec.MAIN_OCTAVES; o++) {
                emitBlendedPutfield(mv, classInternalName, b, 0, o, cursor++);
            }
            for (int o = 0; o < BlendedNoiseSpec.LIMIT_OCTAVES; o++) {
                emitBlendedPutfield(mv, classInternalName, b, 1, o, cursor++);
            }
            for (int o = 0; o < BlendedNoiseSpec.LIMIT_OCTAVES; o++) {
                emitBlendedPutfield(mv, classInternalName, b, 2, o, cursor++);
            }
        }

        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** Single AALOAD+CHECKCAST+PUTFIELD pair for one per-octave field. */
    private static void emitOctavePutfield(MethodVisitor mv, String classInternalName,
                                           int specIdx, int branch, int activeOctaveIdx, int payloadIdx) {
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        // noiseOctaves is constructor-arg slot 4.
        mv.visitVarInsn(Opcodes.ALOAD, 4);
        ldcIntStatic(mv, payloadIdx);
        mv.visitInsn(Opcodes.AALOAD);
        mv.visitTypeInsn(Opcodes.CHECKCAST, IMPROVED_NOISE_INTERNAL);
        mv.visitFieldInsn(Opcodes.PUTFIELD, classInternalName,
                noiseFieldName(specIdx, branch, activeOctaveIdx), IMPROVED_NOISE_DESC);
    }

    private static void emitBlendedPutfield(MethodVisitor mv, String classInternalName,
                                        int bIdx, int section, int sub, int payloadIdx) {
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 4);
        ldcIntStatic(mv, payloadIdx);
        mv.visitInsn(Opcodes.AALOAD);
        mv.visitTypeInsn(Opcodes.CHECKCAST, IMPROVED_NOISE_INTERNAL);
        mv.visitFieldInsn(Opcodes.PUTFIELD, classInternalName,
                blendedFieldName(bIdx, section, sub), IMPROVED_NOISE_DESC);
    }

    /** Static-context twin of {@code EmitState.ldcInt} for the constructor body. */
    private static void ldcIntStatic(MethodVisitor mv, int v) {
        if (v >= -1 && v <= 5) mv.visitInsn(Opcodes.ICONST_0 + v);
        else if (v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE) mv.visitIntInsn(Opcodes.BIPUSH, v);
        else if (v >= Short.MIN_VALUE && v <= Short.MAX_VALUE) mv.visitIntInsn(Opcodes.SIPUSH, v);
        else mv.visitLdcInsn(v);
    }

    /* --------------------------------------------------------------------- */
    /* compute(FunctionContext)                                              */
    /* --------------------------------------------------------------------- */

    /**
     * Slot conventions inside compute() and every helper:
     * <pre>
     *   slot 0  this / self (object reference)
     *   slot 1  ctx (FunctionContext)
     *   slot 2  blockX (int)
     *   slot 3  blockY (int)
     *   slot 4  blockZ (int)
     *   slot 5+ rolling allocator for spilled doubles / float scratch
     * </pre>
     */
    private static void emitCompute(ClassWriter cw, String classInternalName, IRNode root,
                                    HelperRegistry helpers) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "compute",
                "(L" + FUNCTION_CONTEXT_INTERNAL + ";)D", null, null);
        mv.visitCode();
        emitCoordPrologue(mv);

        EmitState st = new EmitState(mv, classInternalName, helpers, false);
        st.emit(root);

        mv.visitInsn(Opcodes.DRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /**
     * Constant root: override {@code fillArray} with {@link java.util.Arrays#fill} only, so
     * non-const compiled DFs keep the default single-call fill path (no per-fill overhead).
     */
    private static void emitConstRootFillArrayOverride(ClassWriter cw, double constValue) {
        String desc = "([DL" + CONTEXT_PROVIDER_INTERNAL + ";)V";
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "fillArray", desc, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        Label hasBuf = new Label();
        mv.visitJumpInsn(Opcodes.IFNONNULL, hasBuf);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitLabel(hasBuf);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitInsn(Opcodes.ARRAYLENGTH);
        Label nonEmpty = new Label();
        mv.visitJumpInsn(Opcodes.IFGT, nonEmpty);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitLabel(nonEmpty);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitLdcInsn(constValue);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Arrays", "fill", "([DD)V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /* --------------------------------------------------------------------- */
    /* Cell-lattice fast path (Tier B5+B6)                                   */
    /* --------------------------------------------------------------------- */

    /**
     * Emit the {@code lattice_y} static helper — exactly one method body that
     * computes the {@link CellLatticeOption.LatticePlan#hoistedSubtree() hoisted
     * Y-only subtree} given the same {@code (self, ctx)} signature as a regular
     * helper. Called by the {@code fillArray} override once per Y-position to
     * cache the per-Y value before the (x, z) inner loops.
     *
     * <p>Same {@link HelperRegistry} instance is reused so the lattice helper's
     * per-helper child extractions thread back through the same pool that the
     * regular helpers use — no double emission of the same extracted subtree.
     */
    private static void emitLatticeYHelper(ClassWriter cw, String classInternalName,
                                           CellLatticeOption.LatticePlan plan,
                                           HelperRegistry helpers) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                LATTICE_Y_NAME, HELPER_DESC, null, null);
        mv.visitCode();
        emitCoordPrologue(mv);
        EmitState st = new EmitState(mv, classInternalName, helpers, /* castSelfForSubclassNoiseFields */ true);
        st.emit(plan.hoistedSubtree());
        mv.visitInsn(Opcodes.DRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /**
     * Emit the {@code lattice_inner} static helper — same body as {@code compute},
     * but the hoisted Y-only subtree is replaced everywhere with the precomputed
     * value passed in as the third method parameter. The resulting body is the
     * "inner expression" reused {@code cellWidth × cellWidth} times per Y-slab in
     * the {@link #emitLatticeFillArrayOverride fillArray override}.
     *
     * <p>Slot layout:
     * <ul>
     *   <li>0 — {@code self} (CompiledDensityFunction)</li>
     *   <li>1 — {@code ctx}  (FunctionContext)</li>
     *   <li>2-3 — {@code yPrecomputed} (double; the third method parameter)</li>
     * </ul>
     *
     * <p>The shared {@link #emitCoordPrologue} writes int blockX/Y/Z to slots
     * 2/3/4, which would clobber the high half of {@code yPrecomputed}. We
     * therefore copy {@code yPrecomputed} into slots 5/6 first, then run the
     * prologue, then preinstall a spill mapping {@code hoistedSubtree → 5} on
     * the {@link EmitState} so every {@code emit()} call that encounters the
     * hoisted node loads the cached double instead of recomputing it. This is
     * the precompute-cache contract we built {@code preinstallSpill} for.
     */
    private static void emitLatticeInnerHelper(ClassWriter cw, String classInternalName,
                                               IRNode root,
                                               CellLatticeOption.LatticePlan plan,
                                               HelperRegistry helpers) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                LATTICE_INNER_NAME, LATTICE_INNER_DESC, null, null);
        mv.visitCode();

        // Copy yPrecomputed (slots 2/3) into a "safe" double slot before the
        // coord prologue overwrites slot 2 with int blockX. Slot 5/6 is the first
        // free slot pair past the coord-prologue slots (2, 3, 4).
        final int yPrecomputedSlot = 5;
        mv.visitVarInsn(Opcodes.DLOAD, 2);
        mv.visitVarInsn(Opcodes.DSTORE, yPrecomputedSlot);

        emitCoordPrologue(mv);

        EmitState st = new EmitState(mv, classInternalName, helpers, /* castSelfForSubclassNoiseFields */ true);
        st.preinstallSpill(plan.hoistedSubtree(), yPrecomputedSlot);
        st.emit(root);

        mv.visitInsn(Opcodes.DRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /**
     * Emit a {@code fillArray(double[], ContextProvider)} override that drives
     * vanilla's (y, x, z) cell triple loop directly, calling {@link #LATTICE_Y_NAME}
     * once per Y-position and {@link #LATTICE_INNER_NAME} for every (y, x, z) cell.
     * The override only fires when the provider is a {@code NoiseChunk}; any other
     * provider falls through to the supertype's
     * {@link CompiledDensityFunction#fillArray scalar path} (which is what
     * {@code NoiseChunk.sliceFillingContextProvider} ends up using anyway, since
     * that provider is not the {@code NoiseChunk} itself).
     *
     * <h2>Equivalent Java</h2>
     * <pre>
     * public void fillArray(double[] values, ContextProvider provider) {
     *     if (provider instanceof NoiseChunk nc) {
     *         nc.arrayIndex = 0;
     *         for (int yi = nc.cellHeight - 1; yi &gt;= 0; yi--) {
     *             nc.inCellY = yi;
     *             double yPre = lattice_y(this, nc);
     *             for (int xi = 0; xi &lt; nc.cellWidth; xi++) {
     *                 nc.inCellX = xi;
     *                 for (int zi = 0; zi &lt; nc.cellWidth; zi++) {
     *                     nc.inCellZ = zi;
     *                     values[nc.arrayIndex++] = lattice_inner(this, nc, yPre);
     *                 }
     *             }
     *         }
     *         return;
     *     }
     *     super.fillArray(values, provider);
     * }
     * </pre>
     *
     * <h2>Why not just override the inner Marker / FlatCache</h2>
     *
     * <p>NoiseChunk's wrap-then-iterate model already expects each
     * {@link DensityFunction} child to drive its own {@code fillArray} via the
     * provider — that's the existing {@code provider.fillAllDirectly(values, this)}
     * fallback we sit on top of. The vanilla path runs the (y, x, z) triple loop in
     * {@code NoiseChunk.fillAllDirectly}, calling {@code compute(this)} per cell
     * — recomputing the Y-only subtree {@code cellWidth × cellWidth} times per Y.
     * Overriding {@code fillArray} here lets us keep the same iteration order
     * vanilla uses (so the order of side-effecting noise samples remains identical
     * — important for parity) but lift the per-Y precompute out of the inner two
     * loops.
     *
     * <h2>Correctness boundary</h2>
     *
     * <p>The provider check is an exact {@code INSTANCEOF NoiseChunk}, not a
     * structural match. {@code DebugCellProvider} (a hypothetical subclass of
     * {@code NoiseChunk} we don't ship) would still trigger the fast path; that's
     * fine because the inner loop's only assumption is that {@code blockX/Y/Z} on
     * the FunctionContext are derived from {@code cellStartBlockX + inCellX}, etc.
     * — which is part of NoiseChunk's public contract.
     *
     * <p>Subclasses of NoiseChunk that override {@code fillAllDirectly} would
     * normally see their override called by the supertype's {@link
     * CompiledDensityFunction#fillArray} fallback; the lattice override skips
     * that delegation. If a subclass needs the override-based hook, it should
     * also override {@code fillArray} on its own DensityFunctions. None of the
     * vanilla subclasses do.
     */
    private static void emitLatticeFillArrayOverride(ClassWriter cw, String classInternalName) {
        String desc = "([DL" + CONTEXT_PROVIDER_INTERNAL + ";)V";
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "fillArray", desc, null, null);
        mv.visitCode();

        // Slot layout:
        //   0 = this, 1 = values[], 2 = provider,
        //   3 = NoiseChunk (cast), 4 = cellWidth, 5 = cellHeight,
        //   6 = yi (loop var), 7 = xi, 8 = zi,
        //   9-10 = yPre (double).
        Label fallback = new Label();

        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitTypeInsn(Opcodes.INSTANCEOF, NOISE_CHUNK_INTERNAL);
        mv.visitJumpInsn(Opcodes.IFEQ, fallback);

        // NoiseChunk nc = (NoiseChunk) provider;
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitTypeInsn(Opcodes.CHECKCAST, NOISE_CHUNK_INTERNAL);
        mv.visitVarInsn(Opcodes.ASTORE, 3);

        // Hoist cellWidth / cellHeight to locals so the JIT proves the inner
        // loop bounds are loop-invariant (NoiseChunk's fields aren't volatile,
        // but the JVM still has to clear that with field aliasing analysis;
        // a single load per dimension makes the bounds trivially constant for
        // the unrolled inner loop).
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitFieldInsn(Opcodes.GETFIELD, NOISE_CHUNK_INTERNAL, "cellWidth", "I");
        mv.visitVarInsn(Opcodes.ISTORE, 4);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitFieldInsn(Opcodes.GETFIELD, NOISE_CHUNK_INTERNAL, "cellHeight", "I");
        mv.visitVarInsn(Opcodes.ISTORE, 5);

        // nc.arrayIndex = 0;
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitFieldInsn(Opcodes.PUTFIELD, NOISE_CHUNK_INTERNAL, "arrayIndex", "I");

        // for (int yi = cellHeight - 1; yi >= 0; yi--)
        mv.visitVarInsn(Opcodes.ILOAD, 5);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.ISUB);
        mv.visitVarInsn(Opcodes.ISTORE, 6);
        Label yLoopHead = new Label();
        Label yLoopExit = new Label();
        mv.visitLabel(yLoopHead);
        mv.visitVarInsn(Opcodes.ILOAD, 6);
        mv.visitJumpInsn(Opcodes.IFLT, yLoopExit);

        // nc.inCellY = yi
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitVarInsn(Opcodes.ILOAD, 6);
        mv.visitFieldInsn(Opcodes.PUTFIELD, NOISE_CHUNK_INTERNAL, "inCellY", "I");

        // double yPre = lattice_y(this, nc);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitInvokeDynamicInsn(LATTICE_Y_NAME, HELPER_DESC, HELPER_BSM);
        mv.visitVarInsn(Opcodes.DSTORE, 9);

        // for (int xi = 0; xi < cellWidth; xi++)
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, 7);
        Label xLoopHead = new Label();
        Label xLoopExit = new Label();
        mv.visitLabel(xLoopHead);
        mv.visitVarInsn(Opcodes.ILOAD, 7);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, xLoopExit);

        // nc.inCellX = xi
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitVarInsn(Opcodes.ILOAD, 7);
        mv.visitFieldInsn(Opcodes.PUTFIELD, NOISE_CHUNK_INTERNAL, "inCellX", "I");

        // for (int zi = 0; zi < cellWidth; zi++)
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, 8);
        Label zLoopHead = new Label();
        Label zLoopExit = new Label();
        mv.visitLabel(zLoopHead);
        mv.visitVarInsn(Opcodes.ILOAD, 8);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, zLoopExit);

        // nc.inCellZ = zi
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitVarInsn(Opcodes.ILOAD, 8);
        mv.visitFieldInsn(Opcodes.PUTFIELD, NOISE_CHUNK_INTERNAL, "inCellZ", "I");

        // values[nc.arrayIndex++] = lattice_inner(this, nc, yPre);
        // Stack discipline: push values, push arrayIndex (capture old), increment.
        mv.visitVarInsn(Opcodes.ALOAD, 1);                       // values
        mv.visitVarInsn(Opcodes.ALOAD, 3);                       // nc
        mv.visitInsn(Opcodes.DUP);                               // nc, nc
        mv.visitFieldInsn(Opcodes.GETFIELD, NOISE_CHUNK_INTERNAL, "arrayIndex", "I"); // nc, idx
        mv.visitInsn(Opcodes.DUP_X1);                            // idx, nc, idx
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IADD);                              // idx, nc, idx+1
        mv.visitFieldInsn(Opcodes.PUTFIELD, NOISE_CHUNK_INTERNAL, "arrayIndex", "I"); // idx (post-store)
        // Stack: values, idx (the old idx, our store target).
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitVarInsn(Opcodes.DLOAD, 9);
        mv.visitInvokeDynamicInsn(LATTICE_INNER_NAME, LATTICE_INNER_DESC, HELPER_BSM);
        // Stack: values, idx, value(double)
        mv.visitInsn(Opcodes.DASTORE);

        // zi++
        mv.visitIincInsn(8, 1);
        mv.visitJumpInsn(Opcodes.GOTO, zLoopHead);
        mv.visitLabel(zLoopExit);

        // xi++
        mv.visitIincInsn(7, 1);
        mv.visitJumpInsn(Opcodes.GOTO, xLoopHead);
        mv.visitLabel(xLoopExit);

        // yi--
        mv.visitIincInsn(6, -1);
        mv.visitJumpInsn(Opcodes.GOTO, yLoopHead);
        mv.visitLabel(yLoopExit);

        mv.visitInsn(Opcodes.RETURN);

        // Fallback: super.fillArray(values, provider).
        mv.visitLabel(fallback);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, COMPILED_BASE_INTERNAL, "fillArray", desc, false);
        mv.visitInsn(Opcodes.RETURN);

        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** {@code blockX/Y/Z()} from ctx → slots 2/3/4 (int). Shared by compute() and every helper. */
    private static void emitCoordPrologue(MethodVisitor mv) {
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, FUNCTION_CONTEXT_INTERNAL, "blockX", "()I", true);
        mv.visitVarInsn(Opcodes.ISTORE, 2);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, FUNCTION_CONTEXT_INTERNAL, "blockY", "()I", true);
        mv.visitVarInsn(Opcodes.ISTORE, 3);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, FUNCTION_CONTEXT_INTERNAL, "blockZ", "()I", true);
        mv.visitVarInsn(Opcodes.ISTORE, 4);
    }

    /* --------------------------------------------------------------------- */
    /* Helper registry: tracks which extracted nodes have been assigned indices */
    /* --------------------------------------------------------------------- */

    static final class HelperRegistry {
        final ClassWriter cw;
        final String classInternalName;
        final ConstantPool pool;
        final RefCount.Result rc;
        final Set<IRNode> extracted;
        private final IdentityHashMap<IRNode, Integer> index = new IdentityHashMap<>();
        private final Deque<IRNode> pending = new ArrayDeque<>();
        private int nextIndex = 0;
        private int emitted = 0;

        HelperRegistry(ClassWriter cw, String classInternalName, ConstantPool pool,
                       RefCount.Result rc, Set<IRNode> extracted) {
            this.cw = cw;
            this.classInternalName = classInternalName;
            this.pool = pool;
            this.rc = rc;
            this.extracted = extracted;
        }

        /** Allocate (or reuse) a helper index for {@code node}; queue it for emission. */
        int indexOf(IRNode node) {
            Integer existing = index.get(node);
            if (existing != null) return existing;
            int idx = nextIndex++;
            if (idx >= MAX_HELPERS) {
                throw new BytecodeTooLargeException(
                        "Generated DF needs more than " + MAX_HELPERS + " helper methods");
            }
            index.put(node, idx);
            pending.addLast(node);
            return idx;
        }

        /** Emit every queued helper, including any helpers transitively discovered during emission. */
        void drain() {
            while (!pending.isEmpty()) {
                IRNode node = pending.pollFirst();
                int idx = index.get(node);
                emitHelper(idx, node);
                emitted++;
            }
        }

        int emittedCount() { return emitted; }

        private void emitHelper(int idx, IRNode node) {
            // Helper signature uses CompiledDensityFunction (the supertype) for `self`,
            // not the hidden class itself. This keeps the call-site MethodType free of
            // hidden-class types. Subclass-only fields (e.g. per-octave ImprovedNoise)
            // need a CHECKCAST in the emitter (see castSelfForSubclassNoiseFields).
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                    helperName(idx), HELPER_DESC, null, null);
            mv.visitCode();
            emitCoordPrologue(mv);

            // `self` is (CompiledDensityFunction) in the descriptor; per-octave noise fields
            // live on the hidden subclass, so emitOctaveContribution must CHECKCAST before GETFIELD.
            EmitState st = new EmitState(mv, classInternalName, this, true);
            // Inline the body of `node` (the helper root) directly. Children that are themselves
            // extracted will route through MH.invokeExact inside emit().
            st.emitInline(node);

            mv.visitInsn(Opcodes.DRETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
    }

    /** Stable naming for helper methods. Avoid `$` to keep stack traces readable. */
    public static String helperName(int idx) {
        return "helper_" + idx;
    }

    /* --------------------------------------------------------------------- */
    /* The recursive emitter                                                 */
    /* --------------------------------------------------------------------- */

    private static final class EmitState {
        private final MethodVisitor mv;
        private final String classInternalName;
        private final HelperRegistry helpers;
        private final ConstantPool pool;
        private final IdentityHashMap<IRNode, Integer> spillSlots = new IdentityHashMap<>();
        private int nextLocal = 5; // slots 0..4 are reserved (this/ctx/x/y/z)
        /**
         * True for static {@code helper_N} methods: local 0 is typed as
         * {@link CompiledDensityFunction} in the method descriptor, but
         * {@link #emitOctaveContribution} reads subclass-only {@code noise_*} fields.
         * A {@code CHECKCAST} to the generated class is required for verification.
         * {@code compute()} passes false — {@code this} is already the precise subclass.
         */
        private final boolean castSelfForSubclassNoiseFields;

        EmitState(MethodVisitor mv, String classInternalName, HelperRegistry helpers,
                  boolean castSelfForSubclassNoiseFields) {
            this.mv = mv;
            this.classInternalName = classInternalName;
            this.helpers = helpers;
            this.pool = helpers.pool;
            this.castSelfForSubclassNoiseFields = castSelfForSubclassNoiseFields;
        }

        private int allocDoubleSlot() {
            int slot = nextLocal;
            nextLocal += 2;
            return slot;
        }

        /**
         * Pre-install a spill mapping for {@code node} → {@code doubleSlot} so the next
         * {@link #emit(IRNode)} call that encounters {@code node} short-circuits into
         * a single {@code DLOAD doubleSlot} instead of re-emitting its body. Used by
         * the lattice {@code lattice_inner} helper to substitute the precomputed
         * Y-slab value (passed in as a method parameter) for the hoisted Y-only
         * subtree everywhere it appears in the root expression.
         *
         * <p>The {@code nextLocal} cursor is bumped past the end of the supplied slot
         * range so subsequent {@link #allocDoubleSlot} calls don't collide with it.
         */
        void preinstallSpill(IRNode node, int doubleSlot) {
            spillSlots.put(node, doubleSlot);
            int after = doubleSlot + 2;
            if (after > nextLocal) {
                nextLocal = after;
            }
        }

        /**
         * Captures the current spill table and local-variable cursor so a conditional
         * branch can be emitted without leaking its private spills into sibling branches
         * or the post-branch merge frame.
         *
         * <p>The bug we guard against: each {@code IRNode}-keyed entry in {@link #spillSlots}
         * tells {@link #emit(IRNode)} that the value is already live in some local slot, so
         * the next emission becomes a single {@code DLOAD slot}. If branch A emits {@code X}
         * for the first time and stores it into slot 9, then branch B (reached via a
         * different jump) inherits the same map and tries to {@code DLOAD 9} — but on B's
         * incoming path slot 9 was never written, so the verifier rejects the class with
         * <em>"get long/double overflows locals"</em>. Restoring the snapshot before each
         * branch arm makes those spills strictly local: the branch can still spill its own
         * shared subexpressions, but the entries are forgotten as soon as the branch ends.
         * Restoring {@link #nextLocal} also lets sibling branches reuse the same slot
         * indices instead of monotonically growing the frame.</p>
         */
        private record BranchScope(IdentityHashMap<IRNode, Integer> spills, int nextLocal) {}

        private BranchScope snapshotBranch() {
            return new BranchScope(new IdentityHashMap<>(spillSlots), nextLocal);
        }

        private void restoreBranch(BranchScope snap) {
            spillSlots.clear();
            spillSlots.putAll(snap.spills);
            nextLocal = snap.nextLocal;
        }

        void emit(IRNode node) {
            // Already spilled in this method — just reload.
            Integer slot = spillSlots.get(node);
            if (slot != null) {
                mv.visitVarInsn(Opcodes.DLOAD, slot);
                return;
            }
            // Routed to a helper: emit a single static call.
            if (helpers.extracted.contains(node)) {
                emitHelperCall(node);
                if (isSpillCandidate(node)) {
                    int s = allocDoubleSlot();
                    mv.visitInsn(Opcodes.DUP2);
                    mv.visitVarInsn(Opcodes.DSTORE, s);
                    spillSlots.put(node, s);
                }
                return;
            }
            boolean shouldSpill = isSpillCandidate(node);
            emitInline(node);
            if (shouldSpill) {
                int s = allocDoubleSlot();
                mv.visitInsn(Opcodes.DUP2);
                mv.visitVarInsn(Opcodes.DSTORE, s);
                spillSlots.put(node, s);
            }
        }

        private void emitHelperCall(IRNode node) {
            int idx = helpers.indexOf(node);
            if (INDY_HELPERS_ENABLED) {
                // INVOKEDYNAMIC path — single bytecode, ConstantCallSite resolved on
                // first hit. The bsm uses invokedName ("helper_5" etc.) to locate
                // the static helper on this hidden class and returns a CCS bound to
                // it; subsequent calls go through a constant-target invokestatic that
                // the JIT can fully inline (no array load, no field load, no
                // MH.invokeExact dispatch through the signature-polymorphic adapter).
                mv.visitVarInsn(Opcodes.ALOAD, 0);          // self
                mv.visitVarInsn(Opcodes.ALOAD, 1);          // ctx
                mv.visitInvokeDynamicInsn(
                        helperName(idx),
                        HELPER_DESC,
                        HELPER_BSM);
                return;
            }
            // Legacy path (kept behind -Ddfc.indy_helpers=false for one release as
            // insurance against an unforeseen LinkageError during indy bsm linkage).
            // Load the helper's MethodHandle from the inherited helperHandles[] field.
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETFIELD, COMPILED_BASE_INTERNAL,
                    "helperHandles", METHOD_HANDLE_ARRAY_DESC);
            ldcInt(idx);
            mv.visitInsn(Opcodes.AALOAD);
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, METHOD_HANDLE_INTERNAL,
                    "invokeExact", HELPER_DESC, false);
        }

        private boolean isSpillCandidate(IRNode node) {
            Integer rcv = helpers.rc.refs().get(node);
            if (rcv == null || rcv < 2) return false;
            if (node instanceof IRNode.Const) return false;
            if (node instanceof IRNode.BlockX || node instanceof IRNode.BlockY || node instanceof IRNode.BlockZ) return false;
            return true;
        }

        /* ---------------- node-specific emission ---------------- */

        void emitInline(IRNode node) {
            switch (node) {
                case IRNode.Const c -> emitConst(c.value());
                case IRNode.BlockX bx -> { mv.visitVarInsn(Opcodes.ILOAD, 2); mv.visitInsn(Opcodes.I2D); }
                case IRNode.BlockY by -> { mv.visitVarInsn(Opcodes.ILOAD, 3); mv.visitInsn(Opcodes.I2D); }
                case IRNode.BlockZ bz -> { mv.visitVarInsn(Opcodes.ILOAD, 4); mv.visitInsn(Opcodes.I2D); }

                case IRNode.Bin bin -> emitBin(bin);
                case IRNode.Unary u -> emitUnary(u);
                case IRNode.Clamp cl -> emitClamp(cl);
                case IRNode.RangeChoice rc -> emitRangeChoice(rc);
                case IRNode.YClampedGradient g -> emitYClampedGradient(g);

                case IRNode.Noise n -> emitNoise(n);
                case IRNode.ShiftedNoise sn -> emitShiftedNoise(sn);
                case IRNode.ShiftA sa -> emitShiftA(sa);
                case IRNode.ShiftB sb -> emitShiftB(sb);
                case IRNode.Shift s -> emitShift(s);
                case IRNode.WeirdScaled w -> emitWeirdScaled(w);
                case IRNode.InlinedNoise n -> emitInlinedNoise(n);
                case IRNode.InlinedBlendedNoise b -> emitInlinedBlendedNoise(b);
                case IRNode.WeirdRarity wr -> emitWeirdRarity(wr);

                case IRNode.Spline.Constant sc -> emitConst(sc.value());
                case IRNode.Spline.Multipoint mp -> emitMultipointSpline(mp);

                case IRNode.Marker m -> emitInvoke(m.externIndex());
                case IRNode.Invoke iv -> emitInvoke(iv.externIndex());
                case IRNode.EndIslands e -> emitInvoke(e.externIndex());
                case IRNode.BlendDensity bd -> emitBlendDensity(bd);
            }
        }

        /* ---------------- primitive helpers ---------------- */

        private void emitConst(double v) {
            if (v == 0.0d) {
                mv.visitInsn(Opcodes.DCONST_0);
            } else if (v == 1.0d) {
                mv.visitInsn(Opcodes.DCONST_1);
            } else {
                mv.visitLdcInsn(v);
            }
        }

        private void emitBin(IRNode.Bin bin) {
            switch (bin.op()) {
                case ADD -> { emit(bin.left()); emit(bin.right()); mv.visitInsn(Opcodes.DADD); }
                case SUB -> { emit(bin.left()); emit(bin.right()); mv.visitInsn(Opcodes.DSUB); }
                case MUL -> { emit(bin.left()); emit(bin.right()); mv.visitInsn(Opcodes.DMUL); }
                case DIV -> { emit(bin.left()); emit(bin.right()); mv.visitInsn(Opcodes.DDIV); }
                case MIN -> {
                    emit(bin.left());
                    emit(bin.right());
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Math", "min", "(DD)D", false);
                }
                case MAX -> {
                    emit(bin.left());
                    emit(bin.right());
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Math", "max", "(DD)D", false);
                }
            }
        }

        private void emitUnary(IRNode.Unary u) {
            emit(u.input());
            switch (u.op()) {
                case ABS -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Math", "abs", "(D)D", false);
                case NEG -> mv.visitInsn(Opcodes.DNEG);
                case SQUARE -> {
                    mv.visitInsn(Opcodes.DUP2);
                    mv.visitInsn(Opcodes.DMUL);
                }
                case CUBE -> {
                    mv.visitInsn(Opcodes.DUP2);
                    mv.visitInsn(Opcodes.DUP2);
                    mv.visitInsn(Opcodes.DMUL);
                    mv.visitInsn(Opcodes.DMUL);
                }
                case HALF_NEGATIVE -> emitConditionalScale(0.5);
                case QUARTER_NEGATIVE -> emitConditionalScale(0.25);
                case SQUEEZE -> emitSqueeze();
            }
        }

        // x > 0 ? x : x * factor  with x already on the stack (top).
        private void emitConditionalScale(double factor) {
            mv.visitInsn(Opcodes.DUP2);
            mv.visitInsn(Opcodes.DCONST_0);
            mv.visitInsn(Opcodes.DCMPL);
            Label keep = new Label();
            Label end = new Label();
            mv.visitJumpInsn(Opcodes.IFGT, keep);
            mv.visitLdcInsn(factor);
            mv.visitInsn(Opcodes.DMUL);
            mv.visitJumpInsn(Opcodes.GOTO, end);
            mv.visitLabel(keep);
            mv.visitLabel(end);
        }

        private void emitSqueeze() {
            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "dev/denismasterherobrine/densityfunctioncompiler/compiler/runtime/Runtime",
                    "squeeze", "(D)D", false);
        }

        private void emitClamp(IRNode.Clamp c) {
            emit(c.input());
            mv.visitLdcInsn(c.max());
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Math", "min", "(DD)D", false);
            mv.visitLdcInsn(c.min());
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Math", "max", "(DD)D", false);
        }

        private void emitRangeChoice(IRNode.RangeChoice rc) {
            emit(rc.input());
            int slot = allocDoubleSlot();
            mv.visitVarInsn(Opcodes.DSTORE, slot);
            mv.visitVarInsn(Opcodes.DLOAD, slot);
            mv.visitLdcInsn(rc.min());
            mv.visitInsn(Opcodes.DCMPG);
            Label outOfRange = new Label();
            Label end = new Label();
            mv.visitJumpInsn(Opcodes.IFLT, outOfRange);

            mv.visitVarInsn(Opcodes.DLOAD, slot);
            mv.visitLdcInsn(rc.max());
            mv.visitInsn(Opcodes.DCMPL);
            mv.visitJumpInsn(Opcodes.IFGE, outOfRange);

            // Each branch must NOT see spills the other branch made — those slots are
            // uninitialized on its incoming path. Snapshot before emitting each arm and
            // restore afterwards (also clears the post-merge state so code after `end`
            // never tries to reload a slot that's only typed on one arm).
            BranchScope snap = snapshotBranch();
            emit(rc.whenInRange());
            restoreBranch(snap);
            mv.visitJumpInsn(Opcodes.GOTO, end);

            mv.visitLabel(outOfRange);
            emit(rc.whenOutOfRange());
            restoreBranch(snap);

            mv.visitLabel(end);
        }

        private void emitYClampedGradient(IRNode.YClampedGradient g) {
            mv.visitVarInsn(Opcodes.ILOAD, 3);
            mv.visitInsn(Opcodes.I2D);
            mv.visitLdcInsn((double) g.fromY());
            mv.visitLdcInsn((double) g.toY());
            mv.visitLdcInsn(g.fromValue());
            mv.visitLdcInsn(g.toValue());
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/util/Mth", "clampedMap",
                    "(DDDDD)D", false);
        }

        /* ---------------- noise samples ---------------- */

        private void loadNoise(int idx) {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETFIELD, COMPILED_BASE_INTERNAL, "noises",
                    "[L" + NORMAL_NOISE_INTERNAL + ";");
            ldcInt(idx);
            mv.visitInsn(Opcodes.AALOAD);
        }

        private void emitNoise(IRNode.Noise n) {
            loadNoise(n.noiseIndex());
            mv.visitVarInsn(Opcodes.ILOAD, 2);
            mv.visitInsn(Opcodes.I2D);
            mv.visitLdcInsn(n.xzScale());
            mv.visitInsn(Opcodes.DMUL);
            mv.visitVarInsn(Opcodes.ILOAD, 3);
            mv.visitInsn(Opcodes.I2D);
            mv.visitLdcInsn(n.yScale());
            mv.visitInsn(Opcodes.DMUL);
            mv.visitVarInsn(Opcodes.ILOAD, 4);
            mv.visitInsn(Opcodes.I2D);
            mv.visitLdcInsn(n.xzScale());
            mv.visitInsn(Opcodes.DMUL);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, NORMAL_NOISE_INTERNAL, "getValue",
                    "(DDD)D", false);
        }

        private void emitShiftedNoise(IRNode.ShiftedNoise sn) {
            loadNoise(sn.noiseIndex());
            mv.visitVarInsn(Opcodes.ILOAD, 2);
            mv.visitInsn(Opcodes.I2D);
            mv.visitLdcInsn(sn.xzScale());
            mv.visitInsn(Opcodes.DMUL);
            emit(sn.shiftX());
            mv.visitInsn(Opcodes.DADD);

            mv.visitVarInsn(Opcodes.ILOAD, 3);
            mv.visitInsn(Opcodes.I2D);
            mv.visitLdcInsn(sn.yScale());
            mv.visitInsn(Opcodes.DMUL);
            emit(sn.shiftY());
            mv.visitInsn(Opcodes.DADD);

            mv.visitVarInsn(Opcodes.ILOAD, 4);
            mv.visitInsn(Opcodes.I2D);
            mv.visitLdcInsn(sn.xzScale());
            mv.visitInsn(Opcodes.DMUL);
            emit(sn.shiftZ());
            mv.visitInsn(Opcodes.DADD);

            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, NORMAL_NOISE_INTERNAL, "getValue",
                    "(DDD)D", false);
        }

        private void emitShift(IRNode.Shift s) {
            loadNoise(s.noiseIndex());
            mv.visitVarInsn(Opcodes.ILOAD, 2);
            mv.visitInsn(Opcodes.I2D);
            mv.visitLdcInsn(0.25);
            mv.visitInsn(Opcodes.DMUL);
            mv.visitVarInsn(Opcodes.ILOAD, 3);
            mv.visitInsn(Opcodes.I2D);
            mv.visitLdcInsn(0.25);
            mv.visitInsn(Opcodes.DMUL);
            mv.visitVarInsn(Opcodes.ILOAD, 4);
            mv.visitInsn(Opcodes.I2D);
            mv.visitLdcInsn(0.25);
            mv.visitInsn(Opcodes.DMUL);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, NORMAL_NOISE_INTERNAL, "getValue",
                    "(DDD)D", false);
            mv.visitLdcInsn(4.0);
            mv.visitInsn(Opcodes.DMUL);
        }

        private void emitShiftA(IRNode.ShiftA s) {
            loadNoise(s.noiseIndex());
            mv.visitVarInsn(Opcodes.ILOAD, 2);
            mv.visitInsn(Opcodes.I2D);
            mv.visitLdcInsn(0.25);
            mv.visitInsn(Opcodes.DMUL);
            mv.visitInsn(Opcodes.DCONST_0);
            mv.visitVarInsn(Opcodes.ILOAD, 4);
            mv.visitInsn(Opcodes.I2D);
            mv.visitLdcInsn(0.25);
            mv.visitInsn(Opcodes.DMUL);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, NORMAL_NOISE_INTERNAL, "getValue",
                    "(DDD)D", false);
            mv.visitLdcInsn(4.0);
            mv.visitInsn(Opcodes.DMUL);
        }

        private void emitShiftB(IRNode.ShiftB s) {
            loadNoise(s.noiseIndex());
            mv.visitVarInsn(Opcodes.ILOAD, 4);
            mv.visitInsn(Opcodes.I2D);
            mv.visitLdcInsn(0.25);
            mv.visitInsn(Opcodes.DMUL);
            mv.visitVarInsn(Opcodes.ILOAD, 2);
            mv.visitInsn(Opcodes.I2D);
            mv.visitLdcInsn(0.25);
            mv.visitInsn(Opcodes.DMUL);
            mv.visitInsn(Opcodes.DCONST_0);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, NORMAL_NOISE_INTERNAL, "getValue",
                    "(DDD)D", false);
            mv.visitLdcInsn(4.0);
            mv.visitInsn(Opcodes.DMUL);
        }

        /* ---------------- Tier-3 inlined noise emission ---------------- */

        /**
         * Emit the fully unrolled per-octave loop for a single
         * {@link IRNode.InlinedNoise}. The shape of the bytecode is:
         * <pre>
         *   // Coordinate prep — emit each coord IR sub-tree once into a fresh slot
         *   emit(coordX); DSTORE cxSlot
         *   emit(coordY); DSTORE cySlot
         *   emit(coordZ); DSTORE czSlot
         *
         *   // First branch (inputCoordScale = 1.0): octave-by-octave
         *   //   contribution_i = ampValueFactor_i *
         *   //                    noise_i.noise(wrap(cx*freq_i), wrap(cy*freq_i), wrap(cz*freq_i))
         *   //   sum += contribution_i
         *
         *   // Second branch (inputCoordScale = NormalNoise.INPUT_FACTOR):
         *   //   pre-scale cx/cy/cz once, then same per-octave loop
         *
         *   // Final: sum *= valueFactor
         * </pre>
         *
         * <p>The {@code wrap} call inlines through {@link
         * dev.denismasterherobrine.densityfunctioncompiler.compiler.runtime.Runtime#wrapAxis}
         * and HotSpot will collapse it into the call site once the surrounding
         * {@code compute} method gets hot.
         */
        private void emitInlinedNoise(IRNode.InlinedNoise n) {
            var spec = pool.noiseSpec(n.specPoolIndex());
            // Phase 1 — coordinate prep into private slots.
            emit(n.coordX());
            int cxSlot = allocDoubleSlot();
            mv.visitVarInsn(Opcodes.DSTORE, cxSlot);
            emit(n.coordY());
            int cySlot = allocDoubleSlot();
            mv.visitVarInsn(Opcodes.DSTORE, cySlot);
            emit(n.coordZ());
            int czSlot = allocDoubleSlot();
            mv.visitVarInsn(Opcodes.DSTORE, czSlot);

            // Phase 2 — first branch sum on top of stack.
            emitBranchSum(spec.first(), n.specPoolIndex(), 0, cxSlot, cySlot, czSlot);

            // Phase 3 — second branch sum, accumulating into Phase 2's running total.
            // Pre-scale coords once when inputCoordScale != 1.0 (NormalNoise.INPUT_FACTOR
            // for the second branch). Skipping the multiply on identity scale avoids
            // burning a DLOAD/LDC/DMUL/DSTORE chain we'd never use.
            var second = spec.second();
            int sCx, sCy, sCz;
            if (second.activeOctaves().length > 0 && Double.compare(second.inputCoordScale(), 1.0) != 0) {
                sCx = allocDoubleSlot();
                mv.visitVarInsn(Opcodes.DLOAD, cxSlot);
                mv.visitLdcInsn(second.inputCoordScale());
                mv.visitInsn(Opcodes.DMUL);
                mv.visitVarInsn(Opcodes.DSTORE, sCx);
                sCy = allocDoubleSlot();
                mv.visitVarInsn(Opcodes.DLOAD, cySlot);
                mv.visitLdcInsn(second.inputCoordScale());
                mv.visitInsn(Opcodes.DMUL);
                mv.visitVarInsn(Opcodes.DSTORE, sCy);
                sCz = allocDoubleSlot();
                mv.visitVarInsn(Opcodes.DLOAD, czSlot);
                mv.visitLdcInsn(second.inputCoordScale());
                mv.visitInsn(Opcodes.DMUL);
                mv.visitVarInsn(Opcodes.DSTORE, sCz);
            } else {
                sCx = cxSlot;
                sCy = cySlot;
                sCz = czSlot;
            }

            int secondCount = second.activeOctaves().length;
            for (int i = 0; i < secondCount; i++) {
                emitOctaveContribution(n.specPoolIndex(), 1, i,
                        second.inputFactors()[i], second.ampValueFactors()[i],
                        sCx, sCy, sCz);
                mv.visitInsn(Opcodes.DADD);
            }

            // Phase 4 — multiply by NormalNoise.valueFactor.
            mv.visitLdcInsn(spec.valueFactor());
            mv.visitInsn(Opcodes.DMUL);
        }

        /**
         * Emit per-octave contributions for one PerlinNoise branch and leave the sum
         * on top of the operand stack. When the branch has no active octaves the
         * stack ends with {@code DCONST_0} so the second-branch loop's DADD chain
         * has something to accumulate into.
         */
        private void emitBranchSum(dev.denismasterherobrine.densityfunctioncompiler.compiler.noise.NoiseSpec.PerlinSpec branch,
                                   int specIdx, int branchIdx, int cxSlot, int cySlot, int czSlot) {
            int count = branch.activeOctaves().length;
            if (count == 0) {
                mv.visitInsn(Opcodes.DCONST_0);
                return;
            }
            // first contribution leaves a double on the stack; subsequent ones DADD.
            for (int i = 0; i < count; i++) {
                emitOctaveContribution(specIdx, branchIdx, i,
                        branch.inputFactors()[i], branch.ampValueFactors()[i],
                        cxSlot, cySlot, czSlot);
                if (i > 0) mv.visitInsn(Opcodes.DADD);
            }
        }

        /**
         * Emit one octave's contribution: {@code ampValueFactor_i *
         * noise_i.noise(wrap(cx*freq_i), wrap(cy*freq_i), wrap(cz*freq_i))}, leaving
         * a single double on top of the operand stack.
         *
         * <p>Field load order matters: pushing the {@code ampValueFactor_i} constant
         * first lets the {@code DMUL} after the {@code INVOKEVIRTUAL} stay clean —
         * we never have to swap a long/double from below an object reference.
         */
        private void emitOctaveContribution(int specIdx, int branchIdx, int activeOctaveIdx,
                                            double inputFactor, double ampValueFactor,
                                            int cxSlot, int cySlot, int czSlot) {
            mv.visitLdcInsn(ampValueFactor);
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            if (castSelfForSubclassNoiseFields) {
                mv.visitTypeInsn(Opcodes.CHECKCAST, classInternalName);
            }
            mv.visitFieldInsn(Opcodes.GETFIELD, classInternalName,
                    noiseFieldName(specIdx, branchIdx, activeOctaveIdx), IMPROVED_NOISE_DESC);

            mv.visitVarInsn(Opcodes.DLOAD, cxSlot);
            mv.visitLdcInsn(inputFactor);
            mv.visitInsn(Opcodes.DMUL);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, RUNTIME_INTERNAL,
                    "wrapAxis", "(D)D", false);

            mv.visitVarInsn(Opcodes.DLOAD, cySlot);
            mv.visitLdcInsn(inputFactor);
            mv.visitInsn(Opcodes.DMUL);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, RUNTIME_INTERNAL,
                    "wrapAxis", "(D)D", false);

            mv.visitVarInsn(Opcodes.DLOAD, czSlot);
            mv.visitLdcInsn(inputFactor);
            mv.visitInsn(Opcodes.DMUL);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, RUNTIME_INTERNAL,
                    "wrapAxis", "(D)D", false);

            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, IMPROVED_NOISE_INTERNAL,
                    "noise", "(DDD)D", false);
            mv.visitInsn(Opcodes.DMUL);
        }

        /**
         * Standalone {@link IRNode.WeirdRarity} emission: just delegates to the same
         * static helper {@link #emitWeirdScaled} previously used inline. Surfaced as
         * its own node so {@link dev.denismasterherobrine.densityfunctioncompiler.compiler.ir.RefCount}
         * can spill the result to a slot for the {@code abs(noise(x/r,y/r,z/r)) * r}
         * fan-out (4 uses).
         */
        private void emitWeirdRarity(IRNode.WeirdRarity wr) {
            emit(wr.input());
            ldcInt(wr.rarityValueMapperOrdinal());
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, RUNTIME_INTERNAL,
                    "weirdRarity", "(DI)D", false);
        }

        private void emitWeirdScaled(IRNode.WeirdScaled w) {
            emit(w.input());
            ldcInt(w.rarityValueMapperOrdinal());
            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "dev/denismasterherobrine/densityfunctioncompiler/compiler/runtime/Runtime",
                    "weirdRarity", "(DI)D", false);
            int dSlot = allocDoubleSlot();
            mv.visitInsn(Opcodes.DUP2);
            mv.visitVarInsn(Opcodes.DSTORE, dSlot);
            loadNoise(w.noiseIndex());
            mv.visitVarInsn(Opcodes.ILOAD, 2);
            mv.visitInsn(Opcodes.I2D);
            mv.visitVarInsn(Opcodes.DLOAD, dSlot);
            mv.visitInsn(Opcodes.DDIV);
            mv.visitVarInsn(Opcodes.ILOAD, 3);
            mv.visitInsn(Opcodes.I2D);
            mv.visitVarInsn(Opcodes.DLOAD, dSlot);
            mv.visitInsn(Opcodes.DDIV);
            mv.visitVarInsn(Opcodes.ILOAD, 4);
            mv.visitInsn(Opcodes.I2D);
            mv.visitVarInsn(Opcodes.DLOAD, dSlot);
            mv.visitInsn(Opcodes.DDIV);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, NORMAL_NOISE_INTERNAL, "getValue",
                    "(DDD)D", false);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Math", "abs", "(D)D", false);
            mv.visitInsn(Opcodes.DMUL);
        }

        /* ---------------- spline ---------------- */

        private void emitMultipointSpline(IRNode.Spline.Multipoint mp) {
            // Compute coordinate, spill to slot, then walk the binary-search ladder.
            emit(mp.coordinate());
            mv.visitInsn(Opcodes.D2F);
            int fSlot = allocFloatSlot();
            mv.visitVarInsn(Opcodes.FSTORE, fSlot);

            float[] locs = mp.locations();
            int n = locs.length;
            Label end = new Label();

            // Snapshot taken AFTER fSlot is allocated so fSlot is treated as
            // outside-the-branch state and remains valid across all segment arms.
            // Each segment / extrapolation arm gets a clean view: it can spill its
            // own subexpressions, but those spills are invalidated before the next
            // sibling arm runs (slot indices and IRNode→slot bindings are reset).
            BranchScope snap = snapshotBranch();

            if (n == 1) {
                emitLinearExtend(fSlot, locs, mp.derivatives(), 0, mp.values().get(0));
                mv.visitInsn(Opcodes.F2D);
                mv.visitJumpInsn(Opcodes.GOTO, end);
                restoreBranch(snap);
                mv.visitLabel(end);
                return;
            }

            Label leftExt = new Label();
            Label rightExt = new Label();

            mv.visitVarInsn(Opcodes.FLOAD, fSlot);
            mv.visitLdcInsn(locs[0]);
            mv.visitInsn(Opcodes.FCMPG);
            mv.visitJumpInsn(Opcodes.IFLT, leftExt);

            mv.visitVarInsn(Opcodes.FLOAD, fSlot);
            mv.visitLdcInsn(locs[n - 1]);
            mv.visitInsn(Opcodes.FCMPL);
            mv.visitJumpInsn(Opcodes.IFGE, rightExt);

            for (int i = 0; i < n - 1; i++) {
                mv.visitVarInsn(Opcodes.FLOAD, fSlot);
                mv.visitLdcInsn(locs[i + 1]);
                mv.visitInsn(Opcodes.FCMPG);
                Label notThis = new Label();
                mv.visitJumpInsn(Opcodes.IFGE, notThis);
                restoreBranch(snap);
                emitInterpolatedSegment(fSlot, mp, i);
                restoreBranch(snap);
                mv.visitJumpInsn(Opcodes.GOTO, end);
                mv.visitLabel(notThis);
            }
            mv.visitJumpInsn(Opcodes.GOTO, rightExt);

            mv.visitLabel(leftExt);
            restoreBranch(snap);
            emitLinearExtend(fSlot, locs, mp.derivatives(), 0, mp.values().get(0));
            restoreBranch(snap);
            mv.visitJumpInsn(Opcodes.GOTO, end);

            mv.visitLabel(rightExt);
            restoreBranch(snap);
            emitLinearExtend(fSlot, locs, mp.derivatives(), n - 1, mp.values().get(n - 1));
            restoreBranch(snap);

            mv.visitLabel(end);
            mv.visitInsn(Opcodes.F2D);
        }

        private void emitInterpolatedSegment(int fSlot, IRNode.Spline.Multipoint mp, int i) {
            float l0 = mp.locations()[i];
            float l1 = mp.locations()[i + 1];
            float d0 = mp.derivatives()[i];
            float d1 = mp.derivatives()[i + 1];

            // t = (f - l0) / (l1 - l0)
            mv.visitVarInsn(Opcodes.FLOAD, fSlot);
            mv.visitLdcInsn(l0);
            mv.visitInsn(Opcodes.FSUB);
            mv.visitLdcInsn(l1 - l0);
            mv.visitInsn(Opcodes.FDIV);
            int tSlot = allocFloatSlot();
            mv.visitVarInsn(Opcodes.FSTORE, tSlot);

            // y0 = sub-spline i
            emitSplineAsFloat(mp.values().get(i));
            int y0Slot = allocFloatSlot();
            mv.visitVarInsn(Opcodes.FSTORE, y0Slot);

            // y1 = sub-spline i+1
            emitSplineAsFloat(mp.values().get(i + 1));
            int y1Slot = allocFloatSlot();
            mv.visitVarInsn(Opcodes.FSTORE, y1Slot);

            // f8 = d0 * (l1 - l0) - (y1 - y0)
            mv.visitLdcInsn(d0 * (l1 - l0));
            mv.visitVarInsn(Opcodes.FLOAD, y1Slot);
            mv.visitVarInsn(Opcodes.FLOAD, y0Slot);
            mv.visitInsn(Opcodes.FSUB);
            mv.visitInsn(Opcodes.FSUB);
            int f8Slot = allocFloatSlot();
            mv.visitVarInsn(Opcodes.FSTORE, f8Slot);

            // f9 = -d1 * (l1 - l0) + (y1 - y0)
            mv.visitLdcInsn(-d1 * (l1 - l0));
            mv.visitVarInsn(Opcodes.FLOAD, y1Slot);
            mv.visitVarInsn(Opcodes.FLOAD, y0Slot);
            mv.visitInsn(Opcodes.FSUB);
            mv.visitInsn(Opcodes.FADD);
            int f9Slot = allocFloatSlot();
            mv.visitVarInsn(Opcodes.FSTORE, f9Slot);

            // y0 + t*(y1-y0) + t*(1-t)*(f8 + t*(f9-f8))
            mv.visitVarInsn(Opcodes.FLOAD, y0Slot);
            mv.visitVarInsn(Opcodes.FLOAD, tSlot);
            mv.visitVarInsn(Opcodes.FLOAD, y1Slot);
            mv.visitVarInsn(Opcodes.FLOAD, y0Slot);
            mv.visitInsn(Opcodes.FSUB);
            mv.visitInsn(Opcodes.FMUL);
            mv.visitInsn(Opcodes.FADD);
            mv.visitVarInsn(Opcodes.FLOAD, tSlot);
            mv.visitInsn(Opcodes.FCONST_1);
            mv.visitVarInsn(Opcodes.FLOAD, tSlot);
            mv.visitInsn(Opcodes.FSUB);
            mv.visitInsn(Opcodes.FMUL);
            mv.visitVarInsn(Opcodes.FLOAD, f8Slot);
            mv.visitVarInsn(Opcodes.FLOAD, tSlot);
            mv.visitVarInsn(Opcodes.FLOAD, f9Slot);
            mv.visitVarInsn(Opcodes.FLOAD, f8Slot);
            mv.visitInsn(Opcodes.FSUB);
            mv.visitInsn(Opcodes.FMUL);
            mv.visitInsn(Opcodes.FADD);
            mv.visitInsn(Opcodes.FMUL);
            mv.visitInsn(Opcodes.FADD);
        }

        private void emitLinearExtend(int fSlot, float[] locs, float[] derivs, int idx,
                                      IRNode.Spline value) {
            float d = derivs[idx];
            emitSplineAsFloat(value);
            if (d == 0.0F) return;
            mv.visitVarInsn(Opcodes.FLOAD, fSlot);
            mv.visitLdcInsn(locs[idx]);
            mv.visitInsn(Opcodes.FSUB);
            mv.visitLdcInsn(d);
            mv.visitInsn(Opcodes.FMUL);
            mv.visitInsn(Opcodes.FADD);
        }

        private void emitSplineAsFloat(IRNode.Spline value) {
            switch (value) {
                case IRNode.Spline.Constant sc -> mv.visitLdcInsn(sc.value());
                case IRNode.Spline.Multipoint inner -> {
                    // Route through emit() so the splitter's helper-extraction is honoured
                    // for nested splines too. Returns a double; convert to float.
                    emit(inner);
                    mv.visitInsn(Opcodes.D2F);
                }
            }
        }

        private int allocFloatSlot() {
            int slot = nextLocal;
            nextLocal += 1;
            return slot;
        }

        /* ---------------- invoke / blend ---------------- */

        /**
         * Straight {@code pool[i].compute(ctx)} — do not wrap every extern in a
         * cache try/miss path: most externs are not {@code NoiseChunk} cache
         * wrappers, so a universal wrapper regresses hot paths (extra static
         * call, NaN check, second {@code GETFIELD} on miss). A future opt-in
         * can target only known wrapper slots at compile time.
         */
        private void emitInvoke(int idx) {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            if (castSelfForSubclassNoiseFields) {
                mv.visitTypeInsn(Opcodes.CHECKCAST, classInternalName);
            }
            mv.visitFieldInsn(Opcodes.GETFIELD, classInternalName, externFieldName(idx), DENSITY_FUNCTION_DESC);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, DENSITY_FUNCTION_INTERNAL,
                    "compute", "(L" + FUNCTION_CONTEXT_INTERNAL + ";)D", true);
        }

        private void emitInlinedBlendedNoise(IRNode.InlinedBlendedNoise n) {
            BlendedNoiseByteEmitter.emit(
                    mv, classInternalName, pool, n.blendedSpecIndex(), castSelfForSubclassNoiseFields, this::allocDoubleSlot);
        }

        private void emitBlendDensity(IRNode.BlendDensity bd) {
            // Evaluate the input first with a clean operand stack so any branchy code
            // inside (RangeChoice arms, nested Spline.Multipoint ladders) doesn't have
            // to merge frames while Blender+ctx are sitting on the operand stack. The
            // previous emission order pushed Blender, ctx, then ran emit(bd.input()) —
            // when the input contained a BranchScope-using subtree, ASM's COMPUTE_FRAMES
            // would merge divergent arm frames at the join label and slots written on
            // only some arms became TOP. Subsequent DLOADs of those slots from the
            // outer scope's spill table then failed verification with
            // "get long/double overflows locals" (see CompiledDF_18 in run-output-fixed.log).
            // Spilling to a fresh slot first costs one extra DSTORE/DLOAD pair per call
            // and serialises the side-effects cleanly.
            emit(bd.input());
            int dSlot = allocDoubleSlot();
            mv.visitVarInsn(Opcodes.DSTORE, dSlot);

            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, FUNCTION_CONTEXT_INTERNAL,
                    "getBlender", "()Lnet/minecraft/world/level/levelgen/blending/Blender;", true);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitVarInsn(Opcodes.DLOAD, dSlot);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    "net/minecraft/world/level/levelgen/blending/Blender",
                    "blendDensity",
                    "(L" + FUNCTION_CONTEXT_INTERNAL + ";D)D", false);
        }

        private void ldcInt(int v) {
            if (v >= -1 && v <= 5) mv.visitInsn(Opcodes.ICONST_0 + v);
            else if (v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE) mv.visitIntInsn(Opcodes.BIPUSH, v);
            else if (v >= Short.MIN_VALUE && v <= Short.MAX_VALUE) mv.visitIntInsn(Opcodes.SIPUSH, v);
            else mv.visitLdcInsn(v);
        }
    }
}
