package treeone.multiplace;

import com.zenith.plugin.api.Plugin;
import com.zenith.plugin.api.PluginAPI;
import com.zenith.plugin.api.ZenithProxyPlugin;
import treeone.multiplace.command.MultiPlaceCommand;
import treeone.multiplace.module.MultiPlaceModule;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

@Plugin(
        id = "multiplace",
        version = "25.04.26",
        description = "Places/Breaks blocks at set positions in a loop.",
        authors = {"TreeOne"},
        mcVersions = {"1.21.4"},
        url = "https://github.com/tr330ne/MultiPlace"
)
public class MultiPlacePlugin implements ZenithProxyPlugin {
    public static PluginAPI API;
    public static MultiPlaceConfig PLUGIN_CONFIG;
    public static ComponentLogger LOG;

    @Override
    public void onLoad(PluginAPI pluginAPI) {
        API = pluginAPI;
        LOG = pluginAPI.getLogger();
        PLUGIN_CONFIG = API.registerConfig("multiplace", MultiPlaceConfig.class);
        API.registerCommand(new MultiPlaceCommand());
        API.registerModule(new MultiPlaceModule());
        LOG.info("MultiPlace loaded");
    }
}