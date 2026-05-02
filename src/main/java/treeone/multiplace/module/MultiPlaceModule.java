package treeone.multiplace.module;

import com.github.rfresh2.EventConsumer;
import com.zenith.Proxy;
import com.zenith.event.client.ClientBotTick;
import com.zenith.event.client.ClientDisconnectEvent;
import com.zenith.feature.inventory.actions.CloseContainer;
import com.zenith.feature.inventory.actions.MoveToHotbarSlot;
import com.zenith.feature.inventory.actions.SetHeldItem;
import com.zenith.feature.inventory.InventoryActionRequest;
import com.zenith.feature.inventory.util.InventoryUtil;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.MoveToHotbarAction;
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
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerAction;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundOpenScreenPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundPlayerActionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundUseItemOnPacket;
import com.zenith.feature.pathfinder.movement.MovementHelper;
import com.zenith.mc.block.BlockPos;
import com.zenith.mc.block.Direction;
import java.util.ArrayList;
import java.util.List;
import static com.github.rfresh2.EventConsumer.of;
import static com.zenith.Globals.*;
import static treeone.multiplace.MultiPlacePlugin.LOG;
import static treeone.multiplace.MultiPlacePlugin.PLUGIN_CONFIG;

public class MultiPlaceModule extends Module {

    private final List<CustomPlaceProcess> placeProcesses = new ArrayList<>();
    private final List<CustomPlaceProcess> breakProcesses = new ArrayList<>();
    private final List<BlockPos> lastSentInstantBreaks = new java.util.LinkedList<>();
    private final List<BlockPos> lastSentInstantPlaces = new java.util.LinkedList<>();

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
        return List.of(
                of(ClientBotTick.class, this::onClientBotTick),
                of(ClientDisconnectEvent.class, this::onClientDisconnect)
        );
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

        if (PLUGIN_CONFIG.session.instant) {
            if (PLUGIN_CONFIG.session.mode == MultiPlaceConfig.Mode.BREAK) {
                tickInstantBreak(positions);
            } else {
                tickInstantPlace(positions);
            }
            return;
        }

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

    private void tickInstantPlace(List<MultiPlaceConfig.Pos> positions) {
        ItemData itemData = ItemRegistry.REGISTRY.get(PLUGIN_CONFIG.session.itemName);
        if (itemData == null) {
            LOG.warn("MultiPlace instant: unknown item '{}', stopping.", PLUGIN_CONFIG.session.itemName);
            PLUGIN_CONFIG.session.active = false;
            return;
        }
        int itemSlot = InventoryUtil.searchPlayerInventory(i -> i.getId() == itemData.id());
        if (itemSlot == -1) return;
        if (itemSlot >= 9 && itemSlot <= 35) {
            INVENTORY.submit(InventoryActionRequest.builder()
                    .owner(this)
                    .priority(0)
                    .actions(new MoveToHotbarSlot(itemSlot, MoveToHotbarAction.from(0)))
                    .build());
            return;
        } else if (itemSlot >= 36 && itemSlot <= 44) {
            if (CACHE.getPlayerCache().getHeldItemSlot() != itemSlot - 36) {
                INVENTORY.submit(InventoryActionRequest.builder()
                        .owner(this)
                        .priority(0)
                        .actions(new SetHeldItem(itemSlot - 36))
                        .build());
                return;
            }
        } else {
            return;
        }

        var botPos = BOT.blockPosition();
        lastSentInstantPlaces.removeIf(bp -> !World.getBlock(bp).isAir());

        for (MultiPlaceConfig.Pos pos : positions) {
            var bp = new BlockPos(pos.x, pos.y, pos.z);
            if (!World.getBlock(bp).isAir()) continue;
            if (lastSentInstantPlaces.contains(bp)) continue;
            if (botPos.distance(bp) > 5) continue;
            Direction placeDir = findSupportingFace(pos.x, pos.y, pos.z);
            if (placeDir == null) continue;
            int sx = pos.x + placeDir.x();
            int sy = pos.y + placeDir.y();
            int sz = pos.z + placeDir.z();
            lastSentInstantPlaces.add(bp);
            while (lastSentInstantPlaces.size() > 50) lastSentInstantPlaces.removeFirst();
            Proxy.getInstance().getClient().sendAsync(
                    new ServerboundUseItemOnPacket(
                            sx, sy, sz,
                            placeDir.invert().mcpl(),
                            Hand.MAIN_HAND,
                            0.5f, 0.5f, 0.5f,
                            PLUGIN_CONFIG.session.sneak,
                            false,
                            0
                    )
            );
        }
    }

    private Direction findSupportingFace(int x, int y, int z) {
        Direction[] dirs = {Direction.DOWN, Direction.SOUTH, Direction.EAST, Direction.NORTH, Direction.WEST, Direction.UP};
        for (Direction dir : dirs) {
            int nx = x + dir.x(), ny = y + dir.y(), nz = z + dir.z();
            if (MovementHelper.canPlaceAgainst(World.getBlockStateId(nx, ny, nz))) return dir;
        }
        return null;
    }

    private void tickInstantBreak(List<MultiPlaceConfig.Pos> positions) {
        if (!BOT.isOnGround()) return;
        if (BOT.getInteractions().isDestroying()) return;
        var botPos = BOT.blockPosition();
        lastSentInstantBreaks.removeIf(bp -> World.getBlock(bp).isAir());
        for (MultiPlaceConfig.Pos pos : positions) {
            var bp = new BlockPos(pos.x, pos.y, pos.z);
            if (World.getBlock(bp).isAir()) continue;
            if (lastSentInstantBreaks.contains(bp)) continue;
            if (BOT.getInteractions().isDestroying(pos.x, pos.y, pos.z)) continue;
            if (botPos.distance(bp) > 5) continue;
            if (bp.y() <= botPos.y() - 1) continue;

            var block = World.getBlock(pos.x, pos.y, pos.z);
            var container = CACHE.getPlayerCache().getInventoryCache().getOpenContainer();
            int toolSlot = CustomPlaceProcess.BreakBlock.bestTool(block);
            var toolItem = toolSlot >= 0 ? container.getItemStack(toolSlot) : null;
            if (BOT.getInteractions().blockBreakSpeed(block, toolItem) < 1.0F) continue;

            if (toolSlot >= 9 && toolSlot < 36) {
                INVENTORY.submit(InventoryActionRequest.builder()
                        .owner(this)
                        .priority(0)
                        .actions(new MoveToHotbarSlot(toolSlot, MoveToHotbarAction.from(0)))
                        .build());
                continue;
            } else if (toolSlot >= 36 && toolSlot <= 44) {
                if (CACHE.getPlayerCache().getHeldItemSlot() != toolSlot - 36) {
                    INVENTORY.submit(InventoryActionRequest.builder()
                            .owner(this)
                            .priority(0)
                            .actions(new SetHeldItem(toolSlot - 36))
                            .build());
                    continue;
                }
            }

            lastSentInstantBreaks.add(bp);
            while (lastSentInstantBreaks.size() > 50) lastSentInstantBreaks.removeFirst();
            Proxy.getInstance().getClient().sendAsync(
                    new ServerboundPlayerActionPacket(
                            PlayerAction.START_DESTROY_BLOCK,
                            pos.x, pos.y, pos.z,
                            Direction.DOWN.mcpl(),
                            0
                    )
            );
        }
    }

    private void onClientDisconnect(ClientDisconnectEvent event) {
        if (!PLUGIN_CONFIG.session.disableOnDisconnect) return;
        PLUGIN_CONFIG.session.active = false;
        reset();
    }

    public void reset() {
        for (var process : placeProcesses) process.onLostControl();
        placeProcesses.clear();
        for (var process : breakProcesses) process.onLostControl();
        breakProcesses.clear();
        lastSentInstantBreaks.clear();
        lastSentInstantPlaces.clear();
    }

    @Override
    public void onDisable() {
        reset();
    }
}