package dev.denismasterherobrine.densityfunctioncompiler.test;

import dev.denismasterherobrine.densityfunctioncompiler.compiler.ir.CoordDep;
import dev.denismasterherobrine.densityfunctioncompiler.compiler.ir.IRNode;

/**
 * Lightweight checks for {@link CoordDep} (no JUnit — run from {@code /dfc} or manually).
 */
public final class CoordDepTest {

    private CoordDepTest() {}

    public static void verify() {
        IRNode c = new IRNode.Const(1.0);
        var m = CoordDep.flagsForAllNodes(c);
        if (!CoordDep.nodesWithNoBlockCoords(m).contains(c)) {
            throw new AssertionError("const should have no block coord dep");
        }
        if (CoordDep.usesAnyBlockCoord(c)) {
            throw new AssertionError("const root should not use block coords");
        }

        IRNode bx = IRNode.BlockX.INSTANCE;
        if (!CoordDep.usesAnyBlockCoord(bx)) {
            throw new AssertionError("BlockX should use a coord");
        }
        var mx = CoordDep.flagsForAllNodes(bx).get(bx);
        if (mx == null || !mx.usesX() || mx.usesY() || mx.usesZ()) {
            throw new AssertionError("BlockX dep flags: " + mx);
        }
    }
}
