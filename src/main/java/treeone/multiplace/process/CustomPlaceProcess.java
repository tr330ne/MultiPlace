package treeone.multiplace.process;

import com.zenith.feature.inventory.InventoryActionRequest;
import com.zenith.feature.inventory.actions.MoveToHotbarSlot;
import com.zenith.feature.inventory.actions.SetHeldItem;
import com.zenith.feature.inventory.actions.WaitAction;
import com.zenith.feature.inventory.util.InventoryUtil;
import com.zenith.feature.pathfinder.Baritone;
import com.zenith.feature.pathfinder.PathingCommand;
import com.zenith.feature.pathfinder.PathingCommandType;
import com.zenith.feature.pathfinder.PathingRequestFuture;
import com.zenith.feature.pathfinder.goals.Goal;
import com.zenith.feature.pathfinder.goals.GoalNear;
import com.zenith.feature.pathfinder.movement.MovementHelper;
import com.zenith.feature.pathfinder.process.BaritoneProcessHelper;
import com.zenith.feature.player.*;
import com.zenith.feature.player.raycast.RaycastHelper;
import com.zenith.mc.block.*;
import com.zenith.mc.item.ItemData;
import com.zenith.util.math.MathHelper;
import lombok.Data;
import org.cloudburstmc.math.vector.Vector2f;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.MoveToHotbarAction;
import org.jspecify.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;
import static com.zenith.Globals.*;
import static treeone.multiplace.MultiPlacePlugin.PLUGIN_CONFIG;

public class CustomPlaceProcess extends BaritoneProcessHelper {

    private @Nullable PathingRequestFuture future;
    private @Nullable Object target;
    private int tries = 0;

    public CustomPlaceProcess(Baritone baritone) {
        super(baritone);
    }

    public void placeBlock(int x, int y, int z, ItemData item) {
        onLostControl();
        this.target = new PlaceBlock(x, y, z, item);
        this.future = new PathingRequestFuture();
    }

    public void breakBlock(int x, int y, int z) {
        onLostControl();
        this.target = new BreakBlock(x, y, z);
        this.future = new PathingRequestFuture();
    }

    @Override
    public boolean isActive() {
        return target != null;
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel, Goal currentGoal, PathingCommand prevCommand) {
        Object t = target;
        if (t == null) {
            onLostControl();
            return null;
        }
        PathingCommand cmd;
        boolean succeeded;
        if (t instanceof PlaceBlock pb) {
            cmd = pb.pathingCommand();
            succeeded = pb.isSucceeded();
        } else {
            BreakBlock bb = (BreakBlock) t;
            cmd = bb.pathingCommand();
            succeeded = bb.isSucceeded();
        }
        if (cmd == null) {
            if (succeeded && future != null) {
                future.complete(true);
                future.notifyListeners();
            }
            onLostControl();
            return new PathingCommand(null, PathingCommandType.DEFER);
        }
        if (calcFailed) {
            if (++tries > CONFIG.client.extra.pathfinder.interactWithProcessMaxPathTries) {
                onLostControl();
                return null;
            }
        }
        return cmd;
    }

    @Override
    public void onLostControl() {
        target = null;
        if (future != null && !future.isCompleted()) {
            future.complete(false);
        }
        future = null;
        tries = 0;
    }

    @Override
    public String displayName0() {
        return "CustomPlaceProcess: " + target;
    }

    @Data
    public static class PlaceBlock {
        private final int x;
        private final int y;
        private final int z;
        private final ItemData placeItem;
        private boolean succeeded = false;

        public void interact(Hand hand, PlaceTarget placeTarget, Rotation rotation) {
            var in = Input.builder()
                    .hand(hand)
                    .clickRequiresRotation(true)
                    .clickTarget(new ClickTarget.BlockPosition(
                            placeTarget.supportingBlockState().x(),
                            placeTarget.supportingBlockState().y(),
                            placeTarget.supportingBlockState().z()))
                    .rightClick(true)
                    .sneaking(PLUGIN_CONFIG.session.sneak);

            INPUTS.submit(
                    InputRequest.builder()
                            .owner(this)
                            .input(in.build())
                            .yaw(rotation.yaw())
                            .pitch(rotation.pitch())
                            .priority(Baritone.getPriority() + 1)
                            .build()
            ).addInputExecutedListener(f -> {
                if (futureSucceeded(f, placeTarget)) {
                    PATH_LOG.info("CustomPlaceProcess: placed block at [{}, {}, {}] with item: {}", x, y, z, placeItem);
                    succeeded = true;
                }
            });
        }

        public PathingCommand pathingCommand() {
            if (succeeded || !targetValid()) return null;
            double distToTarget = MathHelper.distance3d(
                    CACHE.getPlayerCache().getX(), CACHE.getPlayerCache().getY(), CACHE.getPlayerCache().getZ(),
                    x, y, z
            );
            if (distToTarget <= BOT.getBlockReachDistance() + 8) {
                if (CONFIG.client.extra.pathfinder.placeBlockVerifyAbleToPlace && entityInPlaceTarget()) {
                    info("An entity is blocking the place position [{}, {}, {}], stopping", x, y, z);
                    return null;
                }
                var placeTargets = findPlaceTargets();
                if (placeTargets.isEmpty()) {
                    info("No valid blocks to place against, stopping");
                    return null;
                }
                for (PlaceTarget placeTarget : placeTargets) {
                    Rotation rotation = rotationToPlaceTarget(placeTarget);
                    if (rotation == null) continue;

                    Hand hand = Hand.MAIN_HAND;
                    var itemSlot = InventoryUtil.searchPlayerInventory(item -> item.getId() == placeItem.id());
                    if (itemSlot >= 36 && itemSlot <= 44) {
                        INVENTORY.submit(InventoryActionRequest.builder()
                                .owner(this)
                                .priority(Baritone.getPriority() + 1)
                                .actions(new SetHeldItem(itemSlot - 36))
                                .build());
                    } else if (itemSlot >= 9 && itemSlot <= 36) {
                        INVENTORY.submit(InventoryActionRequest.builder()
                                .owner(this)
                                .priority(Baritone.getPriority() + 1)
                                .actions(
                                        new MoveToHotbarSlot(itemSlot, MoveToHotbarAction.SLOT_6),
                                        new SetHeldItem(6))
                                .build());
                    } else if (itemSlot == 45) {
                        INVENTORY.submit(InventoryActionRequest.builder()
                                .owner(this)
                                .priority(Baritone.getPriority() + 1)
                                .actions(new WaitAction())
                                .build());
                        hand = Hand.OFF_HAND;
                    }

                    if (hand == Hand.MAIN_HAND) {
                        if (CACHE.getPlayerCache().getHeldItemSlot() == itemSlot - 36) {
                            interact(Hand.MAIN_HAND, placeTarget, rotation);
                        }
                    } else {
                        interact(Hand.OFF_HAND, placeTarget, rotation);
                    }
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
            }
            int rangeSq = Math.max(2, (int) Math.pow(BOT.getBlockReachDistance() - 1, 2));
            if (!PLUGIN_CONFIG.session.pathing) return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            return new PathingCommand(new GoalNear(x, y, z, rangeSq), PathingCommandType.REVALIDATE_GOAL_AND_PATH);
        }

        public boolean targetValid() {
            if (InventoryUtil.searchPlayerInventory(i -> i.getId() == placeItem.id()) == -1) {
                info("Item: {} not in inventory, stopping", placeItem.name());
                return false;
            }
            if (World.isChunkLoadedBlockPos(x, z)) {
                Block block = World.getBlock(x, y, z);
                if (CONFIG.client.extra.pathfinder.placeBlockVerifyAbleToPlace && !block.isAir()) {
                    info("A block: {} is already at [{}, {}, {}], stopping", block.name(), x, y, z);
                    return false;
                }
            }
            return true;
        }

        public @Nullable Rotation rotationToPlaceTarget(PlaceTarget placeTarget) {
            int px = placeTarget.supportingBlockState().x();
            int py = placeTarget.supportingBlockState().y();
            int pz = placeTarget.supportingBlockState().z();
            Position center = World.blockInteractionCenter(px, py, pz);
            boolean sneak = PLUGIN_CONFIG.session.sneak;
            Vector2f centerRot = sneak
                    ? sneakRotationTo(center.x(), center.y(), center.z())
                    : RotationHelper.rotationTo(center.x(), center.y(), center.z());
            var centerResult = RaycastHelper.playerEyeRaycastThroughToBlockTarget(px, py, pz, centerRot.getX(), centerRot.getY());
            if (centerResult.hit()
                    && centerResult.x() == px && centerResult.y() == py && centerResult.z() == pz
                    && centerResult.direction() == placeTarget.direction()) {
                return new Rotation(centerRot.getX(), centerRot.getY());
            }
            double step = 0.1, maxStep = 0.5;
            for (var pos : rotationStepList(center.x(), center.y(), center.z(), step, maxStep)) {
                Vector2f rot = sneak
                        ? sneakRotationTo(pos.x(), pos.y(), pos.z())
                        : RotationHelper.rotationTo(pos.x(), pos.y(), pos.z());
                var res = RaycastHelper.playerEyeRaycastThroughToBlockTarget(px, py, pz, rot.getX(), rot.getY());
                if (res.hit() && res.x() == px && res.y() == py && res.z() == pz && res.direction() == placeTarget.direction()) {
                    return new Rotation(rot.getX(), rot.getY());
                }
            }
            return null;
        }

        public List<Position> rotationStepList(double x, double y, double z, double step, double maxStep) {
            var result = new ArrayList<Position>();
            for (double d = step; d <= maxStep; d += step) {
                for (int ddx = -1; ddx <= 1; ddx++) {
                    for (int ddy = -1; ddy <= 1; ddy++) {
                        for (int ddz = -1; ddz <= 1; ddz++) {
                            if (ddx == 0 && ddy == 0 && ddz == 0) continue;
                            result.add(new Position(x + ddx * d, y + ddy * d, z + ddz * d));
                        }
                    }
                }
            }
            return result;
        }

        public record PlaceTarget(BlockState supportingBlockState, Direction direction) {}

        public boolean entityInPlaceTarget() {
            var entityCbs = new ArrayList<LocalizedCollisionBox>();
            var blockCb = new LocalizedCollisionBox(new CollisionBox(0, 1, 0, 1, 0, 1), x, y, z);
            World.getEntityCollisionBoxes(blockCb, entityCbs, entity -> entity.getEntityData().blocksBuilding());
            return !entityCbs.isEmpty();
        }

        static final Direction[] placeDirections = {
                Direction.DOWN, Direction.SOUTH, Direction.EAST,
                Direction.NORTH, Direction.WEST, Direction.UP
        };

        public List<PlaceTarget> findPlaceTargets() {
            var validPlaces = new ArrayList<PlaceTarget>();
            for (var face : placeDirections) {
                int dx = x + face.x(), dy = y + face.y(), dz = z + face.z();
                int blockStateId = World.getBlockStateId(dx, dy, dz);
                if (!MovementHelper.canPlaceAgainst(blockStateId)) continue;
                validPlaces.add(new PlaceTarget(World.getBlockState(dx, dy, dz), face.invert()));
            }
            return validPlaces;
        }

        public boolean futureSucceeded(InputRequestFuture future, PlaceTarget placeTarget) {
            if (!future.getNow()) return false;
            if (!(future.getClickResult() instanceof ClickResult.RightClickResult r)) return false;
            return r.getBlockX() == placeTarget.supportingBlockState().x()
                    && r.getBlockY() == placeTarget.supportingBlockState().y()
                    && r.getBlockZ() == placeTarget.supportingBlockState().z();
        }

        private void info(String msg, Object... args) {
            PATH_LOG.info(msg, args);
        }
    }

    @Data
    public static class BreakBlock {
        private final int x;
        private final int y;
        private final int z;
        private boolean isBreaking = false;
        private boolean succeeded = false;

        public PathingCommand pathingCommand() {
            if (succeeded || !targetValid()) return null;
            isBreaking = BOT.getInteractions().isDestroying(x, y, z);
            if (canInteract()) {
                Hand hand = Hand.MAIN_HAND;
                int toolSlot = bestTool(World.getBlock(x, y, z));
                if (toolSlot >= 9) {
                    if (toolSlot < 36) {
                        INVENTORY.submit(InventoryActionRequest.builder()
                                .owner(this)
                                .actions(new MoveToHotbarSlot(toolSlot, MoveToHotbarAction.from(0)))
                                .priority(Baritone.getPriority())
                                .build());
                    } else if (toolSlot <= 44) {
                        INVENTORY.submit(InventoryActionRequest.builder()
                                .owner(this)
                                .actions(new SetHeldItem(toolSlot - 36))
                                .priority(Baritone.getPriority())
                                .build());
                    } else if (toolSlot == 45) {
                        hand = Hand.OFF_HAND;
                    }
                }
                interact(hand);
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            int rangeSq = Math.max(2, (int) Math.pow(BOT.getBlockReachDistance() - 1, 2));
            if (!PLUGIN_CONFIG.session.pathing) return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            return new PathingCommand(new GoalNear(x, y, z, rangeSq), PathingCommandType.REVALIDATE_GOAL_AND_PATH);
        }

        private int bestTool(Block block) {
            int bestInd = -1;
            double bestSpeed = -1;
            var container = CACHE.getPlayerCache().getInventoryCache().getOpenContainer();
            for (int i = container.getSize() - 1; i >= 0; i--) {
                var itemStack = container.getItemStack(i);
                if (itemStack == null) continue;
                double speed = BOT.getInteractions().blockBreakSpeed(block, itemStack);
                if (speed > bestSpeed) {
                    bestSpeed = speed;
                    bestInd = i;
                }
            }
            return bestInd;
        }

        public boolean targetValid() {
            if (World.isChunkLoadedBlockPos(x, z)) {
                Block block = World.getBlock(x, y, z);
                if (block.isAir()) {
                    if (isBreaking) {
                        succeeded = true;
                        info("Block [{}, {}, {}] broken!", x, y, z);
                        return false;
                    }
                    info("No block is at [{}, {}, {}], stopping", x, y, z);
                    return false;
                }
                if (World.isFluid(block)) {
                    info("A fluid {} is at [{}, {}, {}], stopping", block.name(), x, y, z);
                    return false;
                }
                if (block.destroySpeed() < 0) {
                    info("An unbreakable block {} is at [{}, {}, {}], stopping", block.name(), x, y, z);
                    return false;
                }
                var cbs = BLOCK_DATA.getInteractionBoxesFromBlockStateId(World.getBlockStateId(x, y, z));
                if (cbs.isEmpty()) {
                    info("A block without interaction boxes is at target position, stopping");
                    return false;
                }
            }
            return true;
        }

        public boolean canInteract() {
            Position center = World.blockInteractionCenter(x, y, z);
            boolean sneak = PLUGIN_CONFIG.session.sneak;
            Vector2f rotation = sneak
                    ? sneakRotationTo(center.x(), center.y(), center.z())
                    : RotationHelper.rotationTo(center.x(), center.y(), center.z());
            var result = RaycastHelper.playerEyeRaycastThroughToBlockTarget(x, y, z, rotation.getX(), rotation.getY());
            return result.hit() && result.x() == x && result.y() == y && result.z() == z;
        }

        public void interact(Hand hand) {
            Position center = World.blockInteractionCenter(x, y, z);
            boolean sneak = PLUGIN_CONFIG.session.sneak;
            Vector2f rot = sneak
                    ? sneakRotationTo(center.x(), center.y(), center.z())
                    : RotationHelper.rotationTo(center.x(), center.y(), center.z());
            var in = Input.builder()
                    .hand(hand)
                    .clickRequiresRotation(true)
                    .clickTarget(new ClickTarget.BlockPosition(x, y, z))
                    .leftClick(true)
                    .sneaking(sneak);
            INPUTS.submit(
                    InputRequest.builder()
                            .owner(this)
                            .input(in.build())
                            .yaw(rot.getX())
                            .pitch(rot.getY())
                            .priority(Baritone.getPriority() + 1)
                            .build()
            ).addInputExecutedListener(f -> {
                if (futureSucceeded(f)) {
                    if (!isBreaking) {
                        info("Started breaking block {} at [{}, {}, {}]", World.getBlock(x, y, z).name(), x, y, z);
                    }
                    isBreaking = true;
                }
            });
        }

        public boolean futureSucceeded(InputRequestFuture future) {
            if (!future.getNow()) return false;
            if (!(future.getClickResult() instanceof ClickResult.LeftClickResult r)) return false;
            return r.getBlockX() == x && r.getBlockY() == y && r.getBlockZ() == z;
        }

        private void info(String msg, Object... args) {
            PATH_LOG.info(msg, args);
        }
    }

    private static final double SNEAK_EYE_HEIGHT = 1.27;

    static Vector2f sneakRotationTo(double targetX, double targetY, double targetZ) {
        double eyeY = BOT.getY() + SNEAK_EYE_HEIGHT;
        double dx = targetX - BOT.getX();
        double dy = targetY - eyeY;
        double dz = targetZ - BOT.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        double yaw = Math.toDegrees(Math.atan2(dz, dx)) - 90.0;
        double pitch = -Math.toDegrees(Math.atan2(dy, dist));
        return Vector2f.from((float) yaw, (float) pitch);
    }
}