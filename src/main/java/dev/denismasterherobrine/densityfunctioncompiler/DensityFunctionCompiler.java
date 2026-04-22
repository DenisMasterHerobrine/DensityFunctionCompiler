package dev.denismasterherobrine.densityfunctioncompiler;

import com.mojang.logging.LogUtils;
import dev.denismasterherobrine.densityfunctioncompiler.compiler.pipeline.RegistryWarmer;
import dev.denismasterherobrine.densityfunctioncompiler.compiler.vector.DfcVectorSupport;
import dev.denismasterherobrine.densityfunctioncompiler.debug.DfcCommand;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import org.slf4j.Logger;

@Mod(DensityFunctionCompiler.MODID)
public class DensityFunctionCompiler {
    public static final String MODID = "densityfunctioncompiler";
    public static final Logger LOGGER = LogUtils.getLogger();

    public DensityFunctionCompiler(IEventBus modBus, ModContainer container) {
        LOGGER.info("DensityFunctionCompiler initialising — runtime DF JIT pipeline enabling.");
        DfcVectorSupport.logStatusOnce();
        var bus = NeoForge.EVENT_BUS;
        bus.addListener(DensityFunctionCompiler::onRegisterCommands);
        bus.addListener(DensityFunctionCompiler::onServerStarting);
        bus.addListener(DensityFunctionCompiler::onDatapackSync);
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
}
