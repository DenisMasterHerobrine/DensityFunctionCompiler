package dev.denismasterherobrine.densityfunctioncompiler.doc;

/**
 * Phase 0 — Aquifer and DFC integration (Minecraft 1.21.1, Mojang mappings).
 *
 * <p>{@code NoiseBasedAquifer} is <b>not</b> a {@code DensityFunction} and is not
 * walkable by {@code IRBuilder}. It is a separate worldgen object that
 * <b>calls</b> router-backed densities. Random-state compilation already jits
 * those router fields; the hot JFR time in
 * {@code Aquifer$NoiseBasedAquifer#computeSubstance} combines vanilla grid/fluid
 * logic with virtual {@code DensityFunction#compute} into those jitted types.
 *
 * <p>Future fusion/redirect must be bit-for-bit with vanilla. Option B
 * (de-duplication) lives in {@link dev.denismasterherobrine.densityfunctioncompiler.aquifer.DfcAquiferFusion}
 * and {@code NoiseBasedAquiferMixin}; it is off by default (config + mixin not registered) because
 * redirects on hot {@code compute} calls can regress performance.
 */
public final class AquiferParitySpec {
    private AquiferParitySpec() {}
}
