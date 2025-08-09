package hecklar.schemtictools.Commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.litematica.selection.Box;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.util.*;

/**
 * Command to find the schematic with the most occurrences of a specific block
 * Usage: /compose findmost <block> <directory>
 */
public class FindMostBlockCommand {
    private static final Logger LOGGER = LogManager.getLogger("FindMostBlock");

    private static class SchematicBlockCount {
        final String path;
        final int count;

        SchematicBlockCount(String path, int count) {
            this.path = path;
            this.count = count;
        }
    }

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(ClientCommandManager.literal("tools")
                .then(ClientCommandManager.literal("findmost")
                        .then(ClientCommandManager.argument("block", StringArgumentType.word())
                                .suggests((context, builder) -> {
                                    suggestBlockNames(builder);
                                    return builder.buildFuture();
                                })
                                .then(ClientCommandManager.argument("directory", StringArgumentType.greedyString())
                                        .suggests((context, builder) -> {
                                            suggestDirectories(DataManager.getSchematicsBaseDirectory(), "", builder);
                                            return builder.buildFuture();
                                        })
                                        .executes(context -> {
                                            String blockName = StringArgumentType.getString(context, "block");
                                            String directory = StringArgumentType.getString(context, "directory");
                                            return findSchematicWithMostBlock(blockName, directory, context.getSource());
                                        })))));
    }

    private static int findSchematicWithMostBlock(String blockName, String directory, FabricClientCommandSource source) {
        try {
            // Parse the block
            Block targetBlock = parseBlock(blockName);
            if (targetBlock == null || (targetBlock == Blocks.AIR && !blockName.equals("air"))) {
                source.sendFeedback(Text.literal("§cUnknown block: " + blockName));
                return 0;
            }

            // Get the directory
            File baseDir = DataManager.getSchematicsBaseDirectory();
            File targetDir = new File(baseDir, directory);

            if (!targetDir.exists() || !targetDir.isDirectory()) {
                source.sendFeedback(Text.literal("§cInvalid directory: " + directory));
                return 0;
            }

            source.sendFeedback(Text.literal("§6Searching for schematics with the most §f" + targetBlock.getName().getString()));
            source.sendFeedback(Text.literal("§7Directory: §f" + directory));

            // Find all schematics
            List<File> schematicFiles = findAllSchematics(targetDir);
            if (schematicFiles.isEmpty()) {
                source.sendFeedback(Text.literal("§cNo schematics found in directory"));
                return 0;
            }

            source.sendFeedback(Text.literal("§7Analyzing §f" + schematicFiles.size() + "§7 schematics..."));

            // Track results
            List<SchematicBlockCount> results = new ArrayList<>();
            SchematicBlockCount highest = null;
            int totalWithBlock = 0;

            // Analyze each schematic
            for (File file : schematicFiles) {
                try {
                    LitematicaSchematic schematic = LitematicaSchematic.createFromFile(
                            file.getParentFile().toPath().toFile(),
                            file.getName()
                    );

                    if (schematic == null) continue;

                    int blockCount = countBlockInSchematic(schematic, targetBlock);

                    if (blockCount > 0) {
                        String relativePath = getRelativePath(baseDir, file);
                        SchematicBlockCount result = new SchematicBlockCount(relativePath, blockCount);
                        results.add(result);
                        totalWithBlock++;

                        if (highest == null || blockCount > highest.count) {
                            highest = result;
                        }
                    }

                } catch (Exception e) {
                    LOGGER.error("Error analyzing schematic: " + file.getName(), e);
                }
            }

            // Sort results by count
            results.sort((a, b) -> Integer.compare(b.count, a.count));

            // Display results
            source.sendFeedback(Text.literal(""));

            if (highest != null) {
                source.sendFeedback(Text.literal("§a=== Schematic with Most " + targetBlock.getName().getString() + " ==="));
                source.sendFeedback(Text.literal("§f" + highest.path));
                source.sendFeedback(Text.literal("§6Count: §f" + String.format("%,d", highest.count) + " blocks"));

                // Calculate stacks and shulker boxes
                int stacks = highest.count / 64;
                int remainder = highest.count % 64;
                double shulkers = highest.count / (64.0 * 27);

                source.sendFeedback(Text.literal("§7That's §f" + stacks + " stacks + " + remainder +
                        " §7(§f" + String.format("%.2f", shulkers) + " shulker boxes§7)"));

                // Show top 10 if there are more
                if (results.size() > 1) {
                    source.sendFeedback(Text.literal(""));
                    source.sendFeedback(Text.literal("§7Top 10 Schematics:"));

                    int count = 0;
                    for (SchematicBlockCount result : results) {
                        if (count >= 10) break;

                        String marker = (result == highest) ? " §6★" : "";
                        source.sendFeedback(Text.literal(String.format("§7%2d. §f%,6d blocks §8- §7%s%s",
                                count + 1,
                                result.count,
                                result.path,
                                marker)));
                        count++;
                    }
                }

                // Summary
                source.sendFeedback(Text.literal(""));
                source.sendFeedback(Text.literal("§7Summary:"));
                source.sendFeedback(Text.literal("§7- Total schematics analyzed: §f" + schematicFiles.size()));
                source.sendFeedback(Text.literal("§7- Schematics containing " + targetBlock.getName().getString() + ": §f" + totalWithBlock));

                if (results.size() > 0) {
                    int totalBlocks = results.stream().mapToInt(r -> r.count).sum();
                    double avgBlocks = (double) totalBlocks / results.size();
                    source.sendFeedback(Text.literal("§7- Average blocks per schematic: §f" + String.format("%.0f", avgBlocks)));
                    source.sendFeedback(Text.literal("§7- Total " + targetBlock.getName().getString() + " blocks: §f" + String.format("%,d", totalBlocks)));
                }

            } else {
                source.sendFeedback(Text.literal("§cNo schematics found containing " + targetBlock.getName().getString()));
            }

            return 1;

        } catch (Exception e) {
            LOGGER.error("Error finding schematic with most blocks", e);
            source.sendFeedback(Text.literal("§cError: " + e.getMessage()));
            return 0;
        }
    }

    /**
     * Count occurrences of a specific block in a schematic
     */
    private static int countBlockInSchematic(LitematicaSchematic schematic, Block targetBlock) {
        int count = 0;

        for (String regionName : schematic.getAreas().keySet()) {
            LitematicaBlockStateContainer container = schematic.getSubRegionContainer(regionName);
            Box box = schematic.getAreas().get(regionName);

            if (container == null || box == null) continue;

            BlockPos size = box.getSize();

            // Check every position in the schematic
            for (int y = 0; y < size.getY(); y++) {
                for (int x = 0; x < size.getX(); x++) {
                    for (int z = 0; z < size.getZ(); z++) {
                        BlockState state = container.get(x, y, z);
                        if (state != null && state.getBlock() == targetBlock) {
                            count++;
                        }
                    }
                }
            }
        }

        return count;
    }

    /**
     * Parse block name to Block
     */
    private static Block parseBlock(String blockName) {
        // Try direct registry lookup
        Identifier blockId = Identifier.tryParse(blockName.toLowerCase().replace(' ', '_'));
        if (blockId != null) {
            Block block = Registries.BLOCK.get(blockId);
            if (block != Blocks.AIR || blockName.equals("air")) {
                return block;
            }
        }

        // Try with minecraft namespace
        blockId = Identifier.tryParse("minecraft:" + blockName.toLowerCase().replace(' ', '_'));
        if (blockId != null) {
            return Registries.BLOCK.get(blockId);
        }

        return null;
    }

    /**
     * Find all schematic files in a directory recursively
     */
    private static List<File> findAllSchematics(File dir) {
        List<File> schematics = new ArrayList<>();
        findSchematicsRecursive(dir, schematics);
        return schematics;
    }

    private static void findSchematicsRecursive(File dir, List<File> schematics) {
        File[] files = dir.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isDirectory()) {
                findSchematicsRecursive(file, schematics);
            } else if (file.getName().endsWith(".litematic")) {
                schematics.add(file);
            }
        }
    }

    /**
     * Get relative path from base directory
     */
    private static String getRelativePath(File baseDir, File file) {
        String basePath = baseDir.getAbsolutePath();
        String filePath = file.getAbsolutePath();

        if (filePath.startsWith(basePath)) {
            String relative = filePath.substring(basePath.length());
            if (relative.startsWith(File.separator)) {
                relative = relative.substring(1);
            }
            return relative;
        }

        return file.getName();
    }

    /**
     * Suggest block names for auto-completion
     */
    private static void suggestBlockNames(SuggestionsBuilder builder) {
        String input = builder.getRemaining().toLowerCase();

        // Common blocks
        String[] commonBlocks = {
                "stone", "cobblestone", "dirt", "grass_block", "oak_planks", "spruce_planks",
                "birch_planks", "stone_bricks", "bricks", "sandstone", "glass", "concrete",
                "terracotta", "quartz_block", "oak_log", "spruce_log", "water", "lava",
                "sand", "gravel", "gold_block", "iron_block", "diamond_block", "obsidian",
                "tnt", "chest", "furnace", "crafting_table", "wool", "carpet"
        };

        for (String block : commonBlocks) {
            if (block.startsWith(input) || block.contains(input)) {
                builder.suggest(block);
            }
        }

        // Also from registry
        Registries.BLOCK.stream()
                .map(block -> Registries.BLOCK.getId(block))
                .filter(id -> id.getNamespace().equals("minecraft"))
                .map(id -> id.getPath())
                .filter(path -> path.startsWith(input) || (input.length() > 2 && path.contains(input)))
                .limit(30)
                .forEach(builder::suggest);
    }

    /**
     * Suggest directories for auto-completion
     */
    private static void suggestDirectories(File baseDir, String currentPath, SuggestionsBuilder builder) {
        File currentDir = new File(baseDir, currentPath);
        File[] files = currentDir.listFiles();

        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    String relativePath = currentPath.isEmpty() ?
                            file.getName() : currentPath + "/" + file.getName();
                    builder.suggest(relativePath);
                    suggestDirectories(baseDir, relativePath, builder);
                }
            }
        }
    }
}