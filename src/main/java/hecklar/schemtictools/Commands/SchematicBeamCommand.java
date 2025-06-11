package hecklar.schemtictools.Commands;

import com.mojang.brigadier.CommandDispatcher;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import fi.dy.masa.litematica.selection.Box;
import hecklar.schemtictools.Render.SchematicBeamRenderer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.MushroomBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;

import static hecklar.schemtictools.SchematicTools.sendMessage;

public class SchematicBeamCommand {
    private static final Logger LOGGER = LogManager.getLogger("2b2tTweaks");
    private static boolean isInitialized = false;
    private static final int UPDATE_INTERVAL = 5; // Ticks between updates
    private static final double UPDATE_RADIUS = 10.0; // Blocks
    private static int tickCounter = 0;
    private static SchematicPlacement currentPlacement = null;
    private static final Map<BlockPos, SchematicBlockInfo> allSchematicBlocks = new HashMap<>();
    private static final Set<BlockPos> completeBlocks = new HashSet<>();
    private static final Map<Long, Integer> highestYLevels = new HashMap<>();

    private static class SchematicBlockInfo {
        final BlockState expectedState;
        final String regionName;
        final BlockPos regionPos;

        SchematicBlockInfo(BlockState state, String region, BlockPos pos) {
            this.expectedState = state;
            this.regionName = region;
            this.regionPos = pos;
        }
    }

    private static long getColumnKey(int x, int z) {
        return ((long)x << 32) | (z & 0xFFFFFFFFL);
    }

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        if (!isInitialized) {
            // --- MODIFIED LINE HERE ---
            WorldRenderEvents.AFTER_TRANSLUCENT.register(context ->
                    SchematicBeamRenderer.render(
                            context.matrixStack(),
                            context.camera(),
                            context.consumers() // <-- Pass the VertexConsumerProvider
                    )
            );
            // --- END OF MODIFICATION ---

            ClientTickEvents.END_CLIENT_TICK.register(client -> {
                if (SchematicBeamRenderer.isEnabled() && client.player != null && client.world != null) { // Added null check for world
                    tickCounter++;
                    if (tickCounter >= UPDATE_INTERVAL) {
                        tickCounter = 0;
                        checkNearbyBlocks(client);
                    }
                }
            });

            isInitialized = true;
        }

        // Modified command structure here
        dispatcher.register(ClientCommandManager.literal("tools")
                .then(ClientCommandManager.literal("render")
                        .then(ClientCommandManager.literal("beams")
                                .then(ClientCommandManager.literal("toggle")
                                        .executes(context -> {
                                            SchematicBeamRenderer.toggleRendering();
                                            boolean enabled = SchematicBeamRenderer.isEnabled();
                                            sendMessage(enabled ? "§aSchematic beams enabled" : "§cSchematic beams disabled");
                                            if (enabled) {
                                                // Run initial check when enabling
                                                updateIncompleteBlocks();
                                            } else {
                                                // Clear beams and state when disabling
                                                SchematicBeamRenderer.clearBlocks();
                                                completeBlocks.clear(); // Also clear completion tracking
                                            }
                                            return 1;
                                        }))
                                .then(ClientCommandManager.literal("refresh")
                                        .executes(context -> {
                                            if (SchematicBeamRenderer.isEnabled()) {
                                                updateIncompleteBlocks(); // Re-calculate and update beams
                                                sendMessage("§aRefreshed incomplete block beams");
                                            } else {
                                                sendMessage("§cBeams are currently disabled. Use /compose render beams toggle to enable");
                                            }
                                            return 1;
                                        }))
                        )
                )
        );
    }

    private static boolean isHighestInColumn(BlockPos pos) {
        int highestY = highestYLevels.getOrDefault(getColumnKey(pos.getX(), pos.getZ()), Integer.MIN_VALUE);
        return pos.getY() == highestY;
    }

    private static void checkNearbyBlocks(MinecraftClient client) {
        if (!SchematicBeamRenderer.isEnabled() || client.player == null || allSchematicBlocks.isEmpty()) {
            return;
        }

        BlockPos playerPos = client.player.getBlockPos();
        Set<BlockPos> changedBlocks = new HashSet<>();

        // Check each tracked block within radius
        for (Map.Entry<BlockPos, SchematicBlockInfo> entry : allSchematicBlocks.entrySet()) {
            BlockPos pos = entry.getKey();
            // Only process highest blocks in each column
            if (!isHighestInColumn(pos)) {
                continue;
            }

            if (playerPos.getSquaredDistance(pos) <= UPDATE_RADIUS * UPDATE_RADIUS) {
                BlockState currentState = client.world.getBlockState(pos);
                boolean matches = blocksMatch(entry.getValue().expectedState, currentState);
                boolean wasComplete = completeBlocks.contains(pos);

                if (matches && !wasComplete) {
                    // Block is now complete
                    completeBlocks.add(pos);
                    SchematicBeamRenderer.removeBlock(pos);
                    changedBlocks.add(pos);
                } else if (!matches && wasComplete) {
                    // Block was complete but is now incorrect
                    completeBlocks.remove(pos);
                    SchematicBeamRenderer.addIncompleteBlock(pos);
                    changedBlocks.add(pos);
                }
            }
        }

        if (!changedBlocks.isEmpty()) {
            LOGGER.debug("Updated {} blocks in radius {} around player",
                    changedBlocks.size(), UPDATE_RADIUS);
        }
    }

    private static boolean blocksMatch(BlockState schematicState, BlockState worldState) {
        // If blocks are exactly equal, they match
        if (schematicState.equals(worldState)) {
            return true;
        }

        // Special case for mushroom blocks - only compare the block type, ignore properties
        if (schematicState.getBlock() instanceof MushroomBlock &&
                worldState.getBlock() instanceof MushroomBlock) {
            return schematicState.getBlock() == worldState.getBlock();
        }

        // Handle mushroom stem separately since it's not a MushroomBlock instance
        if (schematicState.getBlock() == Blocks.MUSHROOM_STEM &&
                worldState.getBlock() == Blocks.MUSHROOM_STEM) {
            return true;
        }

        return false;
    }

    private static void updateIncompleteBlocks() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return;

        SchematicPlacementManager placementManager = DataManager.getSchematicPlacementManager();
        SchematicPlacement placement = placementManager.getSelectedSchematicPlacement();

        if (placement == null || placement.getSchematic() == null) {
            sendMessage("§cNo schematic placement selected");
            return;
        }

        // Reset tracking if schematic changed
        if (currentPlacement != placement) {
            currentPlacement = placement;
            allSchematicBlocks.clear();
            completeBlocks.clear();
            highestYLevels.clear();
            SchematicBeamRenderer.clearBlocks();
        }

        LitematicaSchematic schematic = placement.getSchematic();
        Map<String, Box> areas = schematic.getAreas();

        // First pass: Find highest block in each column
        highestYLevels.clear();
        for (Map.Entry<String, Box> entry : areas.entrySet()) {
            String regionName = entry.getKey();
            Box box = entry.getValue();
            LitematicaBlockStateContainer container = schematic.getSubRegionContainer(regionName);
            BlockPos origin = placement.getOrigin();
            BlockPos regionPos = schematic.getSubRegionPosition(regionName);

            if (container == null || origin == null || regionPos == null) {
                continue;
            }

            BlockPos size = box.getSize();
            for (int x = 0; x < size.getX(); x++) {
                for (int z = 0; z < size.getZ(); z++) {
                    for (int y = size.getY() - 1; y >= 0; y--) {
                        BlockState schematicState = container.get(x, y, z);
                        if (schematicState != null && !schematicState.isAir()) {
                            BlockPos worldPos = new BlockPos(
                                    origin.getX() + regionPos.getX() + x,
                                    origin.getY() + regionPos.getY() + y,
                                    origin.getZ() + regionPos.getZ() + z
                            );
                            long columnKey = getColumnKey(worldPos.getX(), worldPos.getZ());
                            highestYLevels.merge(columnKey, worldPos.getY(), Math::max);
                            break;  // Found highest block in this column
                        }
                    }
                }
            }
        }

        // Second pass: Check blocks and track completion
        int incompleteCount = 0;
        SchematicBeamRenderer.clearBlocks();

        for (Map.Entry<String, Box> entry : areas.entrySet()) {
            String regionName = entry.getKey();
            Box box = entry.getValue();
            LitematicaBlockStateContainer container = schematic.getSubRegionContainer(regionName);
            BlockPos origin = placement.getOrigin();
            BlockPos regionPos = schematic.getSubRegionPosition(regionName);

            if (container == null || origin == null || regionPos == null) {
                continue;
            }

            BlockPos size = box.getSize();
            for (int x = 0; x < size.getX(); x++) {
                for (int y = 0; y < size.getY(); y++) {
                    for (int z = 0; z < size.getZ(); z++) {
                        BlockState schematicState = container.get(x, y, z);
                        if (schematicState == null || schematicState.isAir()) {
                            continue;
                        }

                        BlockPos worldPos = new BlockPos(
                                origin.getX() + regionPos.getX() + x,
                                origin.getY() + regionPos.getY() + y,
                                origin.getZ() + regionPos.getZ() + z
                        );

                        // Store all schematic blocks for reference
                        allSchematicBlocks.put(worldPos, new SchematicBlockInfo(
                                schematicState, regionName, regionPos));

                        // Only process highest blocks
                        if (!isHighestInColumn(worldPos)) {
                            continue;
                        }

                        BlockState worldState = client.world.getBlockState(worldPos);
                        if (!blocksMatch(schematicState, worldState)) {
                            SchematicBeamRenderer.addIncompleteBlock(worldPos);
                            incompleteCount++;
                        } else {
                            completeBlocks.add(worldPos);
                        }
                    }
                }
            }
        }

        sendMessage(String.format("§7Found %d incomplete blocks (top layer only)", incompleteCount));
    }
}