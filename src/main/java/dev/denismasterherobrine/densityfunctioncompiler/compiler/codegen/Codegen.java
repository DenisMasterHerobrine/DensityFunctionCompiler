package dev.denismasterherobrine.densityfunctioncompiler.compiler.codegen;

import dev.denismasterherobrine.densityfunctioncompiler.compiler.ir.IRNode;
import dev.denismasterherobrine.densityfunctioncompiler.compiler.ir.RefCount;
import org.objectweb.asm.ClassWriter;
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
    private static final String DENSITY_FUNCTION_INTERNAL = "net/minecraft/world/level/levelgen/DensityFunction";
    private static final String FUNCTION_CONTEXT_INTERNAL =
            "net/minecraft/world/level/levelgen/DensityFunction$FunctionContext";
    private static final String METHOD_HANDLE_INTERNAL = "java/lang/invoke/MethodHandle";
    private static final String METHOD_HANDLE_ARRAY_DESC = "[Ljava/lang/invoke/MethodHandle;";

    /**
     * Constructor descriptor used both by {@link #emitConstructor} and by
     * {@link dev.denismasterherobrine.densityfunctioncompiler.compiler.Compiler}
     * reflection. The trailing {@code MethodHandle} is the bound constructor
     * itself, threaded through so {@link CompiledDensityFunction#rebind} can
     * allocate fresh instances without {@code NEW SelfClass} (which hidden
     * classes are forbidden from emitting).
     */
    public static final String CTOR_DESC =
            "([D[L" + NORMAL_NOISE_INTERNAL + ";[Ljava/lang/Object;[L"
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

        emitConstructor(cw);
        // Note: rebind() is implemented in the supertype using the constructor MethodHandle
        // we thread through; we deliberately do NOT emit a rebind override here because that
        // would require a `NEW classInternalName` instruction, which hidden classes cannot
        // emit (the JVM rejects defineHiddenClass when the constant pool names the hidden
        // class itself).

        HelperRegistry helpers = new HelperRegistry(cw, classInternalName, pool, rc, extracted);
        emitCompute(cw, classInternalName, root, helpers);
        helpers.drain();

        cw.visitEnd();
        return new Result(cw.toByteArray(), helpers.emittedCount());
    }

    /** Bytecode + count of helper methods generated. */
    public record Result(byte[] bytecode, int helpersEmitted) {}

    /* --------------------------------------------------------------------- */
    /* Constructor                                                           */
    /* --------------------------------------------------------------------- */

    private static void emitConstructor(ClassWriter cw) {
        // (double[], NormalNoise[], Object[], DensityFunction[], double, double,
        //  MethodHandle[], MethodHandle)
        // Slot layout: this=0, constants=1, noises=2, splines=3, externs=4, minValue=5/6,
        // maxValue=7/8, helperHandles=9, constructorMH=10.
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", CTOR_DESC, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitVarInsn(Opcodes.DLOAD, 5);
        mv.visitVarInsn(Opcodes.DLOAD, 7);
        mv.visitVarInsn(Opcodes.ALOAD, 9);
        mv.visitVarInsn(Opcodes.ALOAD, 10);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, COMPILED_BASE_INTERNAL, "<init>", CTOR_DESC, false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
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

        EmitState st = new EmitState(mv, classInternalName, helpers);
        st.emit(root);

        mv.visitInsn(Opcodes.DRETURN);
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
            // not the hidden class itself. This keeps the method descriptor free of
            // self-references; field accesses inside the body use COMPILED_BASE_INTERNAL
            // anyway since the fields are declared on the supertype.
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                    helperName(idx), HELPER_DESC, null, null);
            mv.visitCode();
            emitCoordPrologue(mv);

            EmitState st = new EmitState(mv, classInternalName, this);
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
        private final IdentityHashMap<IRNode, Integer> spillSlots = new IdentityHashMap<>();
        private int nextLocal = 5; // slots 0..4 are reserved (this/ctx/x/y/z)

        EmitState(MethodVisitor mv, String classInternalName, HelperRegistry helpers) {
            this.mv = mv;
            this.classInternalName = classInternalName;
            this.helpers = helpers;
        }

        private int allocDoubleSlot() {
            int slot = nextLocal;
            nextLocal += 2;
            return slot;
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
            // Load the helper's MethodHandle from the inherited helperHandles[] field.
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETFIELD, COMPILED_BASE_INTERNAL,
                    "helperHandles", METHOD_HANDLE_ARRAY_DESC);
            ldcInt(idx);
            mv.visitInsn(Opcodes.AALOAD);
            // Push the actual args: self + ctx.
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            // Signature-polymorphic invokeExact whose call-site descriptor uses the
            // supertype, not the hidden subclass — no self-reference in the constant pool.
            // The bound MH was constructed by Compiler with this exact MethodType, so the
            // runtime check passes.
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

        private void emitInvoke(int idx) {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETFIELD, COMPILED_BASE_INTERNAL, "externs",
                    "[L" + DENSITY_FUNCTION_INTERNAL + ";");
            ldcInt(idx);
            mv.visitInsn(Opcodes.AALOAD);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, DENSITY_FUNCTION_INTERNAL,
                    "compute", "(L" + FUNCTION_CONTEXT_INTERNAL + ";)D", true);
        }

        private void emitBlendDensity(IRNode.BlendDensity bd) {
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, FUNCTION_CONTEXT_INTERNAL,
                    "getBlender", "()Lnet/minecraft/world/level/levelgen/blending/Blender;", true);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            emit(bd.input());
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
