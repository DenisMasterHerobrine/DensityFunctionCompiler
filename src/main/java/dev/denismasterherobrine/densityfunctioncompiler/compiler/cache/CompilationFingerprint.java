package dev.denismasterherobrine.densityfunctioncompiler.compiler.cache;

import dev.denismasterherobrine.densityfunctioncompiler.compiler.codegen.ConstantPool;
import dev.denismasterherobrine.densityfunctioncompiler.compiler.ir.IRNode;
import dev.denismasterherobrine.densityfunctioncompiler.compiler.ir.RefCount;
import dev.denismasterherobrine.densityfunctioncompiler.compiler.noise.BlendedNoiseSpec;
import dev.denismasterherobrine.densityfunctioncompiler.compiler.noise.NoiseSpec;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.IdentityHashMap;

/**
 * SHA-256 digest of the post-optimisation IR and constant-pool binding identities.
 * <p>Two compiles with identical digests are safe to share the same hidden
 * class + MethodHandle bundle, instantiating a fresh instance with
 * {@link ConstantPool#finishConstants()}-style snapshots from the new pool.
 */
public final class CompilationFingerprint {

    private CompilationFingerprint() {}

    /**
     * 32-byte SHA-256 digest. Structure + {@link ConstantPool} bindings
     * (identities, structured noise specs) must be identical.
     */
    public static byte[] sha256(
            IRNode root,
            ConstantPool pool,
            double minValue,
            double maxValue) {
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
        hashIrStructure(root, pool, sha);
        hashPoolBindings(pool, minValue, maxValue, sha);
        return sha.digest();
    }

    public static String stableClassSuffix(byte[] sha256) {
        StringBuilder sb = new StringBuilder(20);
        for (int i = 0; i < 10; i++) {
            int b = sha256[i] & 0xff;
            sb.append(Character.forDigit(b >> 4, 16));
            sb.append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
    }

    private static void hashIrStructure(IRNode root, ConstantPool pool, MessageDigest sha) {
        IdentityHashMap<IRNode, Object> done = new IdentityHashMap<>();
        postOrderHash(root, pool, sha, done);
    }

    private static void postOrderHash(IRNode n, ConstantPool pool, MessageDigest d,
            IdentityHashMap<IRNode, Object> done) {
        if (n == null) return;
        if (done.containsKey(n)) return;
        for (IRNode c : RefCount.children(n)) {
            postOrderHash(c, pool, d, done);
        }
        done.put(n, Boolean.TRUE);
        hashNode(n, pool, d);
    }

    private static void putTag(MessageDigest d, int tag) {
        d.update((byte) (tag & 0xff));
        d.update((byte) ((tag >> 8) & 0xff));
    }

    private static void putU32(MessageDigest d, int v) {
        d.update((byte) (v & 0xff));
        d.update((byte) ((v >> 8) & 0xff));
        d.update((byte) ((v >> 16) & 0xff));
        d.update((byte) ((v >> 24) & 0xff));
    }

    private static void putF64(MessageDigest d, double v) {
        long bits = Double.doubleToRawLongBits(v);
        for (int i = 0; i < 8; i++) {
            d.update((byte) (bits & 0xff));
            bits >>>= 8;
        }
    }

    private static void hashNode(IRNode n, ConstantPool pool, MessageDigest d) {
        if (n instanceof IRNode.Const c) { putTag(d, 1); putF64(d, c.value()); return; }
        if (n instanceof IRNode.BlockX) { putTag(d, 2); return; }
        if (n instanceof IRNode.BlockY) { putTag(d, 3); return; }
        if (n instanceof IRNode.BlockZ) { putTag(d, 4); return; }
        if (n instanceof IRNode.Bin b) { putTag(d, 5); putTag(d, b.op().ordinal()); return; }
        if (n instanceof IRNode.Unary u) { putTag(d, 6); putTag(d, u.op().ordinal()); return; }
        if (n instanceof IRNode.Clamp c) { putTag(d, 7); putF64(d, c.min()); putF64(d, c.max()); return; }
        if (n instanceof IRNode.RangeChoice c) { putTag(d, 8); putF64(d, c.min()); putF64(d, c.max()); return; }
        if (n instanceof IRNode.YClampedGradient y) { putTag(d, 9); putU32(d, y.fromY()); putU32(d, y.toY());
            putF64(d, y.fromValue()); putF64(d, y.toValue()); return; }
        if (n instanceof IRNode.Noise n1) { putTag(d, 10); putU32(d, n1.noiseIndex()); putF64(d, n1.xzScale());
            putF64(d, n1.yScale()); putF64(d, n1.maxValue()); return; }
        if (n instanceof IRNode.ShiftedNoise s) { putTag(d, 11); putU32(d, s.noiseIndex()); putF64(d, s.xzScale());
            putF64(d, s.yScale()); putF64(d, s.maxValue()); return; }
        if (n instanceof IRNode.ShiftA a) { putTag(d, 12); putU32(d, a.noiseIndex()); putF64(d, a.maxValue()); return; }
        if (n instanceof IRNode.ShiftB a) { putTag(d, 13); putU32(d, a.noiseIndex()); putF64(d, a.maxValue()); return; }
        if (n instanceof IRNode.Shift a) { putTag(d, 14); putU32(d, a.noiseIndex()); putF64(d, a.maxValue()); return; }
        if (n instanceof IRNode.WeirdScaled w) { putTag(d, 15); putU32(d, w.noiseIndex());
            putU32(d, w.rarityValueMapperOrdinal()); putF64(d, w.maxValue()); return; }
        if (n instanceof IRNode.InlinedNoise in) { putTag(d, 16); putU32(d, in.specPoolIndex());
            putF64(d, in.maxValue()); return; }
        if (n instanceof IRNode.InlinedBlendedNoise b) { putTag(d, 17); putU32(d, b.blendedSpecIndex());
            putF64(d, b.maxValue()); return; }
        if (n instanceof IRNode.WeirdRarity w) { putTag(d, 18); putU32(d, w.rarityValueMapperOrdinal()); return; }
        if (n instanceof IRNode.EndIslands e) { putTag(d, 19); putU32(d, e.externIndex()); return; }
        if (n instanceof IRNode.Spline.Constant c) { putTag(d, 20); putF32(d, c.value()); return; }
        if (n instanceof IRNode.Spline.Multipoint mp) { putTag(d, 25); putF32(d, mp.minValue()); putF32(d, mp.maxValue());
            d.update(shaFloatArrayDigest(mp.locations(), mp.derivatives())); return; }
        if (n instanceof IRNode.Marker m) { putTag(d, 21); putU32(d, m.externIndex()); return; }
        if (n instanceof IRNode.Invoke in) { putTag(d, 22); putU32(d, in.externIndex()); return; }
        if (n instanceof IRNode.BlendDensity) { putTag(d, 23); return; }
        throw new IllegalStateException("Unhandled IR node for fingerprint: " + n);
    }

    private static void putF32(MessageDigest d, float v) {
        int bits = Float.floatToRawIntBits(v);
        putU32(d, bits);
    }

    private static byte[] shaFloatArrayDigest(float[] a, float[] b) {
        try {
            var baos = new ByteArrayOutputStream();
            var dos = new DataOutputStream(baos);
            dos.writeInt(a == null ? -1 : a.length);
            if (a != null) for (float v : a) dos.writeInt(Float.floatToRawIntBits(v));
            dos.writeInt(b == null ? -1 : b.length);
            if (b != null) for (float v : b) dos.writeInt(Float.floatToRawIntBits(v));
            dos.flush();
            return MessageDigest.getInstance("SHA-256").digest(baos.toByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private static void hashPoolBindings(ConstantPool pool, double minV, double maxV, MessageDigest d) {
        putF64(d, minV);
        putF64(d, maxV);
        d.update((byte) 0xA1);
        for (int i = 0; i < pool.constantCount(); i++) {
            putF64(d, pool.constant(i));
        }
        d.update((byte) 0xA2);
        for (int i = 0; i < pool.noiseCount(); i++) {
            NormalNoise n = pool.noise(i);
            putJdkIdentity(n, d);
        }
        d.update((byte) 0xA3);
        for (int i = 0; i < pool.externCount(); i++) {
            DensityFunction f = pool.extern(i);
            putJdkIdentity(f, d);
        }
        d.update((byte) 0xA4);
        for (int i = 0; i < pool.splineCount(); i++) {
            putJdkIdentity(pool.splineObject(i), d);
        }
        d.update((byte) 0xA5);
        for (int i = 0; i < pool.noiseSpecCount(); i++) {
            hashNoiseSpec(pool.noiseSpec(i), d);
        }
        d.update((byte) 0xA6);
        for (int i = 0; i < pool.blendedNoiseSpecCount(); i++) {
            hashBlendedSpec(pool.blendedNoiseSpec(i), d);
        }
    }

    private static void putJdkIdentity(Object o, MessageDigest d) {
        if (o == null) {
            d.update((byte) 0x00);
            return;
        }
        d.update((byte) 0x01);
        int id = System.identityHashCode(o);
        putU32(d, id);
    }

    private static void hashNoiseSpec(NoiseSpec s, MessageDigest d) {
        if (s == null) { d.update((byte) 0x7f); return; }
        d.update((byte) 0x40);
        putF64(d, s.valueFactor());
        hashPerlin(s.first(), d);
        hashPerlin(s.second(), d);
    }

    private static void hashPerlin(NoiseSpec.PerlinSpec p, MessageDigest d) {
        d.update((byte) 0x50);
        putF64(d, p.inputCoordScale());
        for (int i = 0; i < p.inputFactors().length; i++) {
            putF64(d, p.inputFactors()[i]);
        }
        for (int i = 0; i < p.ampValueFactors().length; i++) {
            putF64(d, p.ampValueFactors()[i]);
        }
        for (int i = 0; i < p.activeOctaves().length; i++) {
            putJdkIdentity(p.activeOctaves()[i], d);
        }
    }

    private static void hashBlendedSpec(BlendedNoiseSpec b, MessageDigest d) {
        d.update((byte) 0x60);
        putF64(d, b.xzMultiplier());
        putF64(d, b.yMultiplier());
        putF64(d, b.xzFactor());
        putF64(d, b.yFactor());
        putF64(d, b.smearScaleMultiplier());
        putF64(d, b.maxValue());
        for (int i = 0; i < b.mainOctaves().length; i++) {
            putJdkIdentity(b.mainOctaves()[i], d);
        }
        for (int i = 0; i < b.minLimitOctaves().length; i++) {
            putJdkIdentity(b.minLimitOctaves()[i], d);
        }
        for (int i = 0; i < b.maxLimitOctaves().length; i++) {
            putJdkIdentity(b.maxLimitOctaves()[i], d);
        }
    }
}
