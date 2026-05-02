package treeone.multiplace.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.zenith.command.api.Command;
import com.zenith.command.api.CommandCategory;
import com.zenith.command.api.CommandContext;
import com.zenith.command.api.CommandUsage;
import com.zenith.mc.item.ItemData;
import com.zenith.mc.item.ItemRegistry;
import treeone.multiplace.MultiPlaceConfig;
import static com.mojang.brigadier.arguments.IntegerArgumentType.getInteger;
import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.greedyString;
import static com.zenith.command.brigadier.BlockPosArgument.blockPos;
import static com.zenith.command.brigadier.BlockPosArgument.getBlockPos;
import static com.zenith.command.brigadier.ItemArgument.getItem;
import static com.zenith.command.brigadier.ItemArgument.item;
import static com.zenith.Globals.MODULE;
import static com.zenith.command.brigadier.ToggleArgumentType.getToggle;
import treeone.multiplace.module.MultiPlaceModule;
import static com.zenith.command.brigadier.ToggleArgumentType.toggle;
import static treeone.multiplace.MultiPlacePlugin.PLUGIN_CONFIG;

public class MultiPlaceCommand extends Command {

    @Override
    public CommandUsage commandUsage() {
        return CommandUsage.builder()
                .name("multiplace")
                .category(CommandCategory.MODULE)
                .description("Places/Breaks blocks at set positions in a loop.")
                .usageLines(
                        "on/off",
                        "mode place/break",
                        "instant on/off",
                        "item <item>",
                        "sneak on/off",
                        "pathing on/off",
                        "limitcontainers on/off",
                        "add <x> <y> <z>",
                        "addAt <index> <x> <y> <z>",
                        "addAll <x1> <y1> <z1>,,<x2> <y2> <z2>...",
                        "del <index>",
                        "clear",
                        "stats",
                        "disableOnDisconnect on/off",
                        "reset"
                )
                .aliases("mp")
                .build();
    }

    @Override
    public LiteralArgumentBuilder<CommandContext> register() {
        return command("multiplace")
                .then(argument("toggle", toggle()).executes(c -> {
                    boolean on = getToggle(c, "toggle");
                    if (on) {
                        if (PLUGIN_CONFIG.session.positions.isEmpty()) {
                            c.getSource().getEmbed()
                                    .title("MultiPlace " + toggleStrCaps(false))
                                    .description("No positions added.")
                                    .errorColor();
                            return ERROR;
                        }
                        if (PLUGIN_CONFIG.session.mode == MultiPlaceConfig.Mode.PLACE) {
                            if (PLUGIN_CONFIG.session.itemName.isEmpty()) {
                                c.getSource().getEmbed()
                                        .title("MultiPlace " + toggleStrCaps(false))
                                        .description("No item set.")
                                        .errorColor();
                                return ERROR;
                            }
                            ItemData itemData = ItemRegistry.REGISTRY.get(PLUGIN_CONFIG.session.itemName);
                            if (itemData == null) {
                                c.getSource().getEmbed()
                                        .title("MultiPlace " + toggleStrCaps(false))
                                        .description("Item not in registry.")
                                        .errorColor();
                                return ERROR;
                            }
                        }
                    }
                    PLUGIN_CONFIG.session.active = on;
                    if (!on) MODULE.get(MultiPlaceModule.class).reset();
                    c.getSource().getEmbed()
                            .title("MultiPlace " + toggleStrCaps(on));
                    return OK;
                }))

                .then(literal("add").then(argument("pos", blockPos()).executes(c -> {
                    var positions = PLUGIN_CONFIG.session.positions;
                    if (positions.size() >= 256) {
                        c.getSource().getEmbed()
                                .title("MultiPlace")
                                .description("Max 256 positions.")
                                .errorColor();
                        return ERROR;
                    }
                    var pos = getBlockPos(c, "pos");
                    positions.add(new MultiPlaceConfig.Pos(pos.x(), pos.y(), pos.z()));
                    c.getSource().getEmbed()
                            .title("Added #" + (positions.size() - 1))
                            .description(pos.x() + " " + pos.y() + " " + pos.z());
                    return OK;
                })))

                .then(literal("addAt").then(argument("index", integer(0, 255)).then(argument("pos", blockPos()).executes(c -> {
                    int idx = getInteger(c, "index");
                    var positions = PLUGIN_CONFIG.session.positions;
                    if (positions.size() >= 256) {
                        c.getSource().getEmbed()
                                .title("MultiPlace")
                                .description("Max 256 positions.")
                                .errorColor();
                        return ERROR;
                    }
                    var pos = getBlockPos(c, "pos");
                    try {
                        positions.add(idx, new MultiPlaceConfig.Pos(pos.x(), pos.y(), pos.z()));
                        c.getSource().getEmbed()
                                .title("Added at #" + idx)
                                .description(pos.x() + " " + pos.y() + " " + pos.z());
                        return OK;
                    } catch (final Exception e) {
                        c.getSource().getEmbed()
                                .title("Invalid index.")
                                .errorColor();
                        return ERROR;
                    }
                }))))

                .then(literal("addAll").then(argument("allPositions", greedyString()).executes(c -> {
                    var input = getString(c, "allPositions");
                    var split = input.split(",,");
                    if (split.length == 0) {
                        c.getSource().getEmbed()
                                .title("MultiPlace")
                                .description("Each position must be delimited by `,,`")
                                .errorColor();
                        return ERROR;
                    }
                    var positions = PLUGIN_CONFIG.session.positions;
                    if (positions.size() + split.length > 256) {
                        c.getSource().getEmbed()
                                .title("MultiPlace")
                                .description("Max 256 positions.")
                                .errorColor();
                        return ERROR;
                    }
                    for (var entry : split) {
                        var parts = entry.trim().split("\\s+");
                        if (parts.length != 3) {
                            c.getSource().getEmbed()
                                    .title("MultiPlace")
                                    .description("Invalid format: `" + entry.trim() + "`. Expected `<x> <y> <z>`")
                                    .errorColor();
                            return ERROR;
                        }
                        try {
                            int x = Integer.parseInt(parts[0]);
                            int y = Integer.parseInt(parts[1]);
                            int z = Integer.parseInt(parts[2]);
                            positions.add(new MultiPlaceConfig.Pos(x, y, z));
                        } catch (NumberFormatException e) {
                            c.getSource().getEmbed()
                                    .title("MultiPlace")
                                    .description("Invalid coordinates: `" + entry.trim() + "`")
                                    .errorColor();
                            return ERROR;
                        }
                    }
                    c.getSource().getEmbed()
                            .title("Added " + split.length + " position(s)");
                    return OK;
                })))

                .then(literal("del").then(argument("index", integer(0, 255)).executes(c -> {
                    int idx = getInteger(c, "index");
                    var positions = PLUGIN_CONFIG.session.positions;
                    if (idx >= positions.size()) {
                        c.getSource().getEmbed()
                                .title("MultiPlace")
                                .description("Index out of range.")
                                .errorColor();
                        return ERROR;
                    }
                    positions.remove(idx);
                    c.getSource().getEmbed()
                            .title("Removed #" + idx);
                    return OK;
                })))

                .then(literal("stats").executes(c -> {
                    c.getSource().getEmbed()
                            .title("***MultiPlace***");
                    return OK;
                }))

                .then(literal("s").executes(c -> {
                    c.getSource().getEmbed()
                            .title("***MultiPlace***");
                    return OK;
                }))

                .then(literal("item").then(argument("item", item()).executes(c -> {
                    ItemData itemData = getItem(c, "item");
                    if (itemData == null) {
                        c.getSource().getEmbed()
                                .title("MultiPlace")
                                .description("Unknown item.")
                                .errorColor();
                        return ERROR;
                    }
                    PLUGIN_CONFIG.session.itemName = itemData.name();
                    c.getSource().getEmbed()
                            .title("Item set to " + itemData.name());
                    return OK;
                })))

                .then(literal("sneak").then(argument("toggle", toggle()).executes(c -> {
                    PLUGIN_CONFIG.session.sneak = getToggle(c, "toggle");
                    c.getSource().getEmbed()
                            .title("Sneak " + toggleStrCaps(PLUGIN_CONFIG.session.sneak));
                    return OK;
                })))

                .then(literal("limitcontainers").then(argument("toggle", toggle()).executes(c -> {
                    PLUGIN_CONFIG.session.limitContainers = getToggle(c, "toggle");
                    c.getSource().getEmbed()
                            .title("Limit Containers " + toggleStrCaps(PLUGIN_CONFIG.session.limitContainers));
                    return OK;
                })))

                .then(literal("disableOnDisconnect").then(argument("toggle", toggle()).executes(c -> {
                    PLUGIN_CONFIG.session.disableOnDisconnect = getToggle(c, "toggle");
                    c.getSource().getEmbed()
                            .title("Disable On Disconnect " + toggleStrCaps(PLUGIN_CONFIG.session.disableOnDisconnect));
                    return OK;
                })))

                .then(literal("instant").then(argument("toggle", toggle()).executes(c -> {
                    PLUGIN_CONFIG.session.instant = getToggle(c, "toggle");
                    if (PLUGIN_CONFIG.session.instant) MODULE.get(MultiPlaceModule.class).reset();
                    c.getSource().getEmbed()
                            .title("Instant " + toggleStrCaps(PLUGIN_CONFIG.session.instant));
                    return OK;
                })))

                .then(literal("pathing").then(argument("toggle", toggle()).executes(c -> {
                    PLUGIN_CONFIG.session.pathing = getToggle(c, "toggle");
                    c.getSource().getEmbed()
                            .title("Pathing " + toggleStrCaps(PLUGIN_CONFIG.session.pathing));
                    return OK;
                })))

                .then(literal("mode")
                        .then(literal("place").executes(c -> {
                            PLUGIN_CONFIG.session.mode = MultiPlaceConfig.Mode.PLACE;
                            MODULE.get(MultiPlaceModule.class).reset();
                            c.getSource().getEmbed().title("Mode: Place");
                            return OK;
                        }))
                        .then(literal("break").executes(c -> {
                            PLUGIN_CONFIG.session.mode = MultiPlaceConfig.Mode.BREAK;
                            MODULE.get(MultiPlaceModule.class).reset();
                            c.getSource().getEmbed().title("Mode: Break");
                            return OK;
                        })))

                .then(literal("clear").executes(c -> {
                    PLUGIN_CONFIG.session.positions.clear();
                    c.getSource().getEmbed()
                            .title("Positions Cleared")
                            .successColor();
                    return OK;
                }))

                .then(literal("reset").executes(c -> {
                    PLUGIN_CONFIG.session.active = false;
                    PLUGIN_CONFIG.session.positions.clear();
                    PLUGIN_CONFIG.session.itemName = "";
                    PLUGIN_CONFIG.session.sneak = false;
                    PLUGIN_CONFIG.session.limitContainers = false;
                    PLUGIN_CONFIG.session.instant = false;
                    PLUGIN_CONFIG.session.pathing = false;
                    PLUGIN_CONFIG.session.disableOnDisconnect = false;
                    PLUGIN_CONFIG.session.mode = MultiPlaceConfig.Mode.PLACE;
                    MODULE.get(MultiPlaceModule.class).reset();
                    c.getSource().getEmbed()
                            .title("MultiPlace Reset")
                            .successColor();
                    return OK;
                }));
    }

    @Override
    public void defaultHandler(CommandContext ctx) {
        String positions = formatPositions();
        ctx.getEmbed()
                .addField("Active", toggleStr(PLUGIN_CONFIG.session.active))
                .addField("Mode", PLUGIN_CONFIG.session.mode.name().toLowerCase())
                .addField("Instant", toggleStr(PLUGIN_CONFIG.session.instant));
        if (PLUGIN_CONFIG.session.mode == MultiPlaceConfig.Mode.PLACE) {
            ctx.getEmbed().addField("Item", PLUGIN_CONFIG.session.itemName.isEmpty() ? "not set" : PLUGIN_CONFIG.session.itemName);
        }
        ctx.getEmbed()
                .addField("Sneak", toggleStr(PLUGIN_CONFIG.session.sneak))
                .addField("Pathing", toggleStr(PLUGIN_CONFIG.session.pathing))
                .addField("Limit Containers", toggleStr(PLUGIN_CONFIG.session.limitContainers))
                .addField("Positions", positions.isEmpty() ? "none" : positions)
                .addField("Disable On Disconnect", toggleStr(PLUGIN_CONFIG.session.disableOnDisconnect))
                .primaryColor();
    }

    private String formatPositions() {
        var sb = new StringBuilder();
        var list = PLUGIN_CONFIG.session.positions;
        for (int i = 0; i < list.size(); i++) {
            sb.append("`").append(i).append(":` ").append(list.get(i)).append("\n");
        }
        return sb.toString().stripTrailing();
    }
}