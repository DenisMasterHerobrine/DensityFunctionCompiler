package dev.denismasterherobrine.densityfunctioncompiler.debug;

import dev.denismasterherobrine.densityfunctioncompiler.compiler.codegen.ConstantPool;
import dev.denismasterherobrine.densityfunctioncompiler.compiler.ir.IRNode;
import dev.denismasterherobrine.densityfunctioncompiler.compiler.ir.RefCount;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Pretty-prints an {@link IRNode} DAG with reference counts and shared-subtree markers.
 * Output is deliberately compact so it fits in chat / a log line and makes CSE wins
 * visible at a glance.
 */
public final class IRPrinter {

    private IRPrinter() {}

    public static String print(IRNode root, RefCount.Result rc, ConstantPool pool) {
        StringBuilder sb = new StringBuilder(512);
        Map<IRNode, Integer> ids = new IdentityHashMap<>();
        new Walker(sb, rc, pool, ids).walk(root, 0);
        sb.append('\n');
        sb.append("-- legend: [refN] = referenced N times; #N = previously printed shared subtree\n");
        return sb.toString();
    }

    private static final class Walker {
        private final StringBuilder out;
        private final RefCount.Result rc;
        private final ConstantPool pool;
        private final Map<IRNode, Integer> ids;
        private int nextId = 0;

        Walker(StringBuilder out, RefCount.Result rc, ConstantPool pool, Map<IRNode, Integer> ids) {
            this.out = out;
            this.rc = rc;
            this.pool = pool;
            this.ids = ids;
        }

        void walk(IRNode n, int depth) {
            indent(depth);
            int refs = rc.refs().getOrDefault(n, 1);
            Integer existing = ids.get(n);
            if (existing != null) {
                out.append("#").append(existing).append(' ').append(label(n)).append('\n');
                return;
            }
            if (refs >= 2) {
                int id = nextId++;
                ids.put(n, id);
                out.append('#').append(id).append(' ');
            }
            if (refs >= 2) out.append("[ref").append(refs).append("] ");
            out.append(label(n)).append('\n');
            for (IRNode c : RefCount.children(n)) {
                walk(c, depth + 1);
            }
        }

        private void indent(int depth) {
            out.append("  ".repeat(depth));
        }

        private String label(IRNode n) {
            return switch (n) {
                case IRNode.Const c -> "Const(" + c.value() + ")";
                case IRNode.BlockX bx -> "BlockX";
                case IRNode.BlockY by -> "BlockY";
                case IRNode.BlockZ bz -> "BlockZ";
                case IRNode.Bin b -> "Bin." + b.op();
                case IRNode.Unary u -> "Unary." + u.op();
                case IRNode.Clamp c -> "Clamp(" + c.min() + ".." + c.max() + ")";
                case IRNode.RangeChoice rc -> "RangeChoice(" + rc.min() + ".." + rc.max() + ")";
                case IRNode.YClampedGradient g ->
                        "YClampedGradient(y " + g.fromY() + ".." + g.toY() + " -> "
                                + g.fromValue() + ".." + g.toValue() + ")";
                case IRNode.Noise n2 -> "Noise#" + n2.noiseIndex()
                        + " xz=" + n2.xzScale() + " y=" + n2.yScale();
                case IRNode.ShiftedNoise sn -> "ShiftedNoise#" + sn.noiseIndex()
                        + " xz=" + sn.xzScale() + " y=" + sn.yScale();
                case IRNode.ShiftA sa -> "ShiftA#" + sa.noiseIndex();
                case IRNode.ShiftB sb -> "ShiftB#" + sb.noiseIndex();
                case IRNode.Shift s -> "Shift#" + s.noiseIndex();
                case IRNode.WeirdScaled w -> "WeirdScaled#" + w.noiseIndex()
                        + " mapper=" + w.rarityValueMapperOrdinal();
                case IRNode.InlinedNoise in -> {
                    var spec = pool.noiseSpec(in.specPoolIndex());
                    yield "InlinedNoise#" + in.specPoolIndex()
                            + " octaves=" + spec.first().activeOctaves().length
                            + "+" + spec.second().activeOctaves().length
                            + " maxValue=" + in.maxValue();
                }
                case IRNode.InlinedBlendedNoise b ->
                        "InlinedBlendedNoise#" + b.blendedSpecIndex() + " maxValue=" + b.maxValue();
                case IRNode.WeirdRarity wr -> "WeirdRarity(mapper=" + wr.rarityValueMapperOrdinal() + ")";
                case IRNode.EndIslands e -> "EndIslands@" + e.externIndex();
                case IRNode.Spline.Constant sc -> "Spline.Const(" + sc.value() + ")";
                case IRNode.Spline.Multipoint mp -> "Spline.Multipoint(n=" + mp.locations().length + ")";
                case IRNode.Marker m -> "Marker@" + m.externIndex()
                        + " (" + pool.extern(m.externIndex()).getClass().getSimpleName() + ")";
                case IRNode.Invoke iv -> "Invoke@" + iv.externIndex()
                        + " (" + pool.extern(iv.externIndex()).getClass().getSimpleName() + ")";
                case IRNode.BlendDensity bd -> "BlendDensity";
            };
        }
    }
}
