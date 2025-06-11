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
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.registry.Registries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
public class MaterialsCalculatorCommand {
    private static final Logger LOGGER = LogManager.getLogger("2b2tTweaks");
    // Keep track of the last report file
    private static File lastReportFile = null;

    private static class MaterialCount {
        int count;
        String blockName;

        MaterialCount(String blockName) {
            this.blockName = blockName;
            this.count = 0;
        }

        void increment() {
            count++;
        }

        void add(int amount) {
            count += amount;
        }
    }

    // Class to track materials per schematic
    private static class SchematicMaterials {
        String name;
        Map<Block, MaterialCount> materials = new HashMap<>();
        int totalBlocks = 0;

        SchematicMaterials(String name) {
            this.name = name;
        }

        void addBlock(Block block, String blockName) {
            materials.computeIfAbsent(block, b -> new MaterialCount(blockName)).increment();
            totalBlocks++;
        }
    }

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        // Register the material calculation command
        dispatcher.register(ClientCommandManager.literal("tools")
                .then(ClientCommandManager.literal("materials")
                        .then(ClientCommandManager.argument("directory", StringArgumentType.greedyString())
                                .suggests((context, builder) -> {
                                    suggestDirectories(DataManager.getSchematicsBaseDirectory(), "", builder);
                                    return builder.buildFuture();
                                })
                                .executes(context -> {
                                    String directory = StringArgumentType.getString(context, "directory");
                                    return calculateMaterials(directory, context.getSource());
                                }))
                        // Add command to open the last report
                        .then(ClientCommandManager.literal("open-last")
                                .executes(context -> {
                                    if (lastReportFile != null && lastReportFile.exists()) {
                                        // Open the file using the system's default application
                                        try {
                                            // Get OS-specific way to open files
                                            String os = System.getProperty("os.name").toLowerCase();
                                            ProcessBuilder builder = new ProcessBuilder();

                                            if (os.contains("win")) {
                                                // Windows
                                                builder.command("explorer", lastReportFile.getAbsolutePath());
                                            } else if (os.contains("mac")) {
                                                // macOS
                                                builder.command("open", lastReportFile.getAbsolutePath());
                                            } else if (os.contains("nix") || os.contains("nux")) {
                                                // Linux/Unix
                                                builder.command("xdg-open", lastReportFile.getAbsolutePath());
                                            } else {
                                                context.getSource().sendFeedback(Text.literal(
                                                        "§cUnsupported operating system for opening files directly."));
                                                return 0;
                                            }

                                            builder.start();
                                            context.getSource().sendFeedback(Text.literal("§aOpening materials report..."));
                                        } catch (Exception e) {
                                            LOGGER.error("Failed to open file", e);
                                            context.getSource().sendFeedback(Text.literal(
                                                    "§cCouldn't open the report file: " + e.getMessage()));
                                        }
                                    } else {
                                        context.getSource().sendFeedback(Text.literal("§cNo report has been generated yet"));
                                    }
                                    return 1;
                                }))
                ));
    }

    private static void suggestDirectories(File baseDir, String currentPath, SuggestionsBuilder builder) {
        File currentDir = new File(baseDir, currentPath);
        File[] files = currentDir.listFiles();

        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    String relativePath = currentPath.isEmpty() ?
                            file.getName() : currentPath + "/" + file.getName();
                    builder.suggest(relativePath + "/");
                    suggestDirectories(baseDir, relativePath, builder);
                }
            }
        }
    }

    private static int calculateMaterials(String directory, FabricClientCommandSource source) {
        try {
            File baseDir = DataManager.getSchematicsBaseDirectory();
            File targetDir = new File(baseDir, directory);

            if (!targetDir.exists() || !targetDir.isDirectory()) {
                source.sendFeedback(Text.literal("§cInvalid directory path: " + directory));
                return 0;
            }

            source.sendFeedback(Text.literal("§6Calculating required materials for all schematics..."));

            // Material tracking - both total and per-schematic
            Map<Block, MaterialCount> totalMaterialCounts = new HashMap<>();
            List<SchematicMaterials> perSchematicMaterials = new ArrayList<>();
            int totalSchematicsProcessed = 0;
            int totalBlocks = 0;

            // Process all schematics in directory recursively
            Queue<File> directories = new ArrayDeque<>();
            directories.add(targetDir);

            while (!directories.isEmpty()) {
                File currentDir = directories.poll();
                File[] files = currentDir.listFiles();

                if (files != null) {
                    for (File file : files) {
                        if (file.isDirectory()) {
                            directories.add(file);
                            continue;
                        }

                        if (!file.getName().endsWith(".litematic")) {
                            continue;
                        }

                        try {
                            LitematicaSchematic schematic = LitematicaSchematic.createFromFile(
                                    file.getParentFile().toPath().toFile(),
                                    file.getName()
                            );

                            if (schematic == null) continue;

                            // Create a new entry for this schematic's materials
                            SchematicMaterials schematicMats = new SchematicMaterials(file.getName());

                            // Process the schematic and update both total and per-schematic counts
                            int blockCount = processSchematic(schematic, totalMaterialCounts, schematicMats);

                            // Add to the list of per-schematic materials
                            perSchematicMaterials.add(schematicMats);

                            totalSchematicsProcessed++;
                            totalBlocks += blockCount;

                            source.sendFeedback(Text.literal(String.format(
                                    "§7Processed: §f%s §7(+%d blocks)",
                                    file.getName(), blockCount
                            )));
                        } catch (Exception e) {
                            source.sendFeedback(Text.literal("§cFailed to process " + file.getName() + ": " + e.getMessage()));
                        }
                    }
                }
            }

            if (totalSchematicsProcessed == 0) {
                source.sendFeedback(Text.literal("§cNo valid schematics found in the directory"));
                return 0;
            }

            // Generate report
            File reportFile = generateMaterialsReport(totalMaterialCounts, perSchematicMaterials,
                    directory, totalSchematicsProcessed, totalBlocks, source);

            if (reportFile != null) {
                lastReportFile = reportFile;
                // Send clickable file link
                sendReportFileInfo(reportFile, source);
            }

            return 1;

        } catch (Exception e) {
            source.sendFeedback(Text.literal("§cError during processing: " + e.getMessage()));
            e.printStackTrace();
            return 0;
        }
    }

    private static void sendReportFileInfo(File reportFile, FabricClientCommandSource source) {
        if (reportFile == null) return;

        // Create a command to open the file
        String openCommand = "/tools materials open-last";

        // Create a clickable message with better formatting - Fixed the underscore issue
        MutableText message = Text.literal("\n§aDetailed materials report saved! ").append(
                Text.literal("§b[CLICK TO OPEN]")
                        .setStyle(Style.EMPTY
                                .withColor(Formatting.AQUA)
                                .withBold(true)
                                .withUnderline(true)
                                .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, openCommand))
                                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                        Text.literal("Click to open: " + reportFile.getName())
                                ))
                        )
        );
        
        // Debug: Log the command being executed
        LOGGER.info("Setting up click event for command: " + openCommand);
        // Send the clickable message
        source.sendFeedback(message);

        // Let them know they can use the command
        source.sendFeedback(Text.literal("§7Or type: §f" + openCommand));
    }

    private static int processSchematic(LitematicaSchematic schematic,
                                        Map<Block, MaterialCount> totalMaterials,
                                        SchematicMaterials schematicMats) {
        int blockCount = 0;
        Map<String, Box> areas = schematic.getAreas();

        for (Map.Entry<String, Box> entry : areas.entrySet()) {
            LitematicaBlockStateContainer container = schematic.getSubRegionContainer(entry.getKey());
            if (container == null) continue;

            Box box = entry.getValue();
            BlockPos size = box.getSize();

            for (int x = 0; x < size.getX(); x++) {
                for (int y = 0; y < size.getY(); y++) {
                    for (int z = 0; z < size.getZ(); z++) {
                        BlockState state = container.get(x, y, z);
                        if (state != null && !state.isAir()) {
                            Block block = state.getBlock();

                            // Skip fluids and other blocks that you don't need to place
                            if (shouldCountBlock(block, state)) {
                                String blockName = getBlockName(block);

                                // Update total counts
                                totalMaterials.computeIfAbsent(block, b -> new MaterialCount(blockName)).increment();

                                // Update per-schematic counts
                                schematicMats.addBlock(block, blockName);

                                blockCount++;
                            }
                        }
                    }
                }
            }
        }

        return blockCount;
    }

    private static boolean shouldCountBlock(Block block, BlockState state) {
        // Skip fluids and certain blocks that are not placeable items
        return state.getFluidState().isEmpty() && !state.isAir();
    }

    private static String getBlockName(Block block) {
        // Updated to use the current registry system for Minecraft 1.21.4
        Identifier id = Registries.BLOCK.getId(block);
        return id.toString();
    }

    private static File generateMaterialsReport(
            Map<Block, MaterialCount> totalMaterialCounts,
            List<SchematicMaterials> perSchematicMaterials,
            String directory,
            int totalSchematicsProcessed,
            int totalBlocks,
            FabricClientCommandSource source) {

        try {
            File baseDir = DataManager.getSchematicsBaseDirectory();
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
            File reportFile = new File(baseDir, "materials_report_" + timestamp + ".txt");

            try (PrintWriter writer = new PrintWriter(new FileWriter(reportFile))) {
                writer.println("Materials List Analysis Report");
                writer.println("Generated: " + LocalDateTime.now());
                writer.println("Directory: " + directory);
                writer.println("Schematics Processed: " + totalSchematicsProcessed);
                writer.println("Total Blocks: " + totalBlocks);

                // First section: Total materials across all schematics
                writer.println("\n----- TOTAL MATERIALS REQUIRED -----\n");

                // Convert to list for sorting
                List<Map.Entry<Block, MaterialCount>> sortedMaterials = new ArrayList<>(totalMaterialCounts.entrySet());

                // Sort by block count (descending)
                sortedMaterials.sort((a, b) -> Integer.compare(b.getValue().count, a.getValue().count));

                // Calculate and display material counts
                for (Map.Entry<Block, MaterialCount> entry : sortedMaterials) {
                    MaterialCount mc = entry.getValue();
                    int stacks = mc.count / 64;
                    int remainder = mc.count % 64;
                    double shulkers = mc.count / (64.0 * 27); // 27 stacks per shulker

                    writer.printf("%-40s: %,d blocks (%d stacks + %d, %.2f shulker boxes)%n",
                            mc.blockName, mc.count, stacks, remainder, shulkers);

                    // Also send top materials to chat for immediate feedback
                    if (mc.count > 1000) {
                        source.sendFeedback(Text.literal(String.format(
                                "§7%s: §a%,d §7(%.1f shulkers)",
                                mc.blockName, mc.count, shulkers
                        )));
                    }
                }

                // Add summary for all materials
                writer.println("\nTotal Summary:");
                writer.println("Total unique materials: " + sortedMaterials.size());

                // Calculate total shulker boxes needed
                double totalShulkers = sortedMaterials.stream()
                        .mapToDouble(e -> e.getValue().count / (64.0 * 27))
                        .sum();
                writer.printf("Total shulker boxes required: %.2f%n", totalShulkers);

                // Second section: Per-schematic materials
                writer.println("\n\n----- MATERIALS REQUIRED BY EACH SCHEMATIC -----\n");

                // Sort schematics by total block count (largest first)
                perSchematicMaterials.sort((a, b) -> Integer.compare(b.totalBlocks, a.totalBlocks));

                // Process each schematic
                for (SchematicMaterials schematic : perSchematicMaterials) {
                    writer.println("### " + schematic.name + " ###");
                    writer.println("Total blocks: " + schematic.totalBlocks);

                    // Calculate total shulker boxes for this schematic
                    double schematicTotalShulkers = schematic.materials.values().stream()
                            .mapToDouble(mc -> mc.count / (64.0 * 27))
                            .sum();
                    writer.printf("Total shulker boxes: %.2f%n", schematicTotalShulkers);

                    // Sort materials by count
                    List<Map.Entry<Block, MaterialCount>> sortedSchematicMaterials =
                            new ArrayList<>(schematic.materials.entrySet());
                    sortedSchematicMaterials.sort((a, b) ->
                            Integer.compare(b.getValue().count, a.getValue().count));

                    // Write ALL materials for this schematic
                    writer.println("Materials:");
                    for (Map.Entry<Block, MaterialCount> entry : sortedSchematicMaterials) {
                        MaterialCount mc = entry.getValue();
                        int stacks = mc.count / 64;
                        int remainder = mc.count % 64;
                        double shulkers = mc.count / (64.0 * 27); // 27 stacks per shulker

                        writer.printf("  %-40s: %,d blocks (%d stacks + %d, %.2f shulker boxes)%n",
                                mc.blockName, mc.count, stacks, remainder, shulkers);
                    }
                    writer.println();
                }
            }

            return reportFile;

        } catch (Exception e) {
            source.sendFeedback(Text.literal("§cError generating materials report: " + e.getMessage()));
            return null;
        }
    }
}