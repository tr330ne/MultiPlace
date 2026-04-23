package treeone.multiplace.module;

import com.github.rfresh2.EventConsumer;
import com.zenith.Proxy;
import com.zenith.event.client.ClientBotTick;
import com.zenith.feature.inventory.actions.CloseContainer;
import com.zenith.feature.inventory.util.InventoryUtil;
import com.zenith.feature.player.Input;
import com.zenith.feature.player.InputRequest;
import com.zenith.feature.player.World;
import com.zenith.mc.item.ItemData;
import com.zenith.mc.item.ItemRegistry;
import com.zenith.module.api.Module;
import com.zenith.network.client.ClientSession;
import com.zenith.network.codec.PacketHandlerCodec;
import com.zenith.network.codec.PacketHandlerStateCodec;
import treeone.multiplace.MultiPlaceConfig;
import treeone.multiplace.process.CustomPlaceProcess;
import org.geysermc.mcprotocollib.protocol.data.ProtocolState;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundOpenScreenPacket;
import java.util.ArrayList;
import java.util.List;
import static com.github.rfresh2.EventConsumer.of;
import static com.zenith.Globals.*;
import static treeone.multiplace.MultiPlacePlugin.LOG;
import static treeone.multiplace.MultiPlacePlugin.PLUGIN_CONFIG;

public class MultiPlaceModule extends Module {

    private final List<CustomPlaceProcess> placeProcesses = new ArrayList<>();
    private final List<CustomPlaceProcess> breakProcesses = new ArrayList<>();

    @Override
    public boolean enabledSetting() {
        return true;
    }

    @Override
    public void onEnable() {
        placeProcesses.clear();
        breakProcesses.clear();
    }

    @Override
    public List<EventConsumer<?>> registerEvents() {
        return List.of(of(ClientBotTick.class, this::onClientBotTick));
    }

    @Override
    public PacketHandlerCodec registerClientPacketHandlerCodec() {
        return PacketHandlerCodec.clientBuilder()
                .setPriority(0)
                .setId("multiplace")
                .state(ProtocolState.GAME, PacketHandlerStateCodec.clientBuilder()
                        .inbound(ClientboundOpenScreenPacket.class, this::onOpenScreen)
                        .build())
                .build();
    }

    private ClientboundOpenScreenPacket onOpenScreen(ClientboundOpenScreenPacket packet, ClientSession session) {
        if (!PLUGIN_CONFIG.session.active) return packet;
        if (!PLUGIN_CONFIG.session.limitContainers) return packet;
        if (Proxy.getInstance().hasActivePlayer()) return packet;
        var closePacket = new CloseContainer(packet.getContainerId()).packet();
        if (closePacket != null) {
            Proxy.getInstance().getClient().sendAsync(closePacket);
        }
        return null;
    }

    private void onClientBotTick(ClientBotTick event) {
        if (!PLUGIN_CONFIG.session.active) return;

        if (PLUGIN_CONFIG.session.sneak) {
            INPUTS.submit(InputRequest.builder()
                    .owner(this)
                    .input(Input.builder()
                            .sneaking(true)
                            .build())
                    .priority(0)
                    .build());
        }

        List<MultiPlaceConfig.Pos> positions = PLUGIN_CONFIG.session.positions;
        if (positions.isEmpty()) return;

        if (PLUGIN_CONFIG.session.mode == MultiPlaceConfig.Mode.BREAK) {
            tickBreak(positions);
        } else {
            tickPlace(positions);
        }
    }

    private void tickPlace(List<MultiPlaceConfig.Pos> positions) {
        ItemData itemData = ItemRegistry.REGISTRY.get(PLUGIN_CONFIG.session.itemName);
        if (itemData == null) {
            LOG.warn("MultiPlace: unknown item '{}', stopping.", PLUGIN_CONFIG.session.itemName);
            PLUGIN_CONFIG.session.active = false;
            return;
        }
        if (InventoryUtil.searchPlayerInventory(i -> i.getId() == itemData.id()) == -1) return;

        while (placeProcesses.size() < positions.size()) {
            var process = new CustomPlaceProcess(BARITONE);
            BARITONE.getPathingControlManager().registerProcess(process);
            placeProcesses.add(process);
        }

        for (int i = 0; i < positions.size(); i++) {
            MultiPlaceConfig.Pos pos = positions.get(i);
            if (!World.getBlock(pos.x, pos.y, pos.z).isAir()) continue;
            placeProcesses.get(i).placeBlock(pos.x, pos.y, pos.z, itemData);
        }
    }

    private void tickBreak(List<MultiPlaceConfig.Pos> positions) {
        while (breakProcesses.size() < positions.size()) {
            var process = new CustomPlaceProcess(BARITONE);
            BARITONE.getPathingControlManager().registerProcess(process);
            breakProcesses.add(process);
        }

        for (int i = 0; i < positions.size(); i++) {
            MultiPlaceConfig.Pos pos = positions.get(i);
            if (World.getBlock(pos.x, pos.y, pos.z).isAir()) continue;
            breakProcesses.get(i).breakBlock(pos.x, pos.y, pos.z);
        }
    }

    public void reset() {
        for (var process : placeProcesses) process.onLostControl();
        placeProcesses.clear();
        for (var process : breakProcesses) process.onLostControl();
        breakProcesses.clear();
    }

    @Override
    public void onDisable() {
        reset();
    }
}