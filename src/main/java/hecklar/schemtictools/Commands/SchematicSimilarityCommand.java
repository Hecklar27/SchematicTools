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
import net.minecraft.block.BlockState;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Command to find the most similar schematic in a directory compared to a reference schematic
 * Usage: /compose compare <schematic_name> <schematic_directory>
 */
public class SchematicSimilarityCommand {
    private static final Logger LOGGER = LogManager.getLogger("SchematicSimilarity");

    // Result class to store similarity data
    private static class SimilarityResult {
        final String schematicPath;
        final double similarity;
        final int matchingBlocks;
        final int totalBlocks;

        SimilarityResult(String path, double similarity, int matching, int total) {
            this.schematicPath = path;
            this.similarity = similarity;
            this.matchingBlocks = matching;
            this.totalBlocks = total;
        }
    }

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(ClientCommandManager.literal("compose")
                .then(ClientCommandManager.literal("compare")
                        .then(ClientCommandManager.argument("args", StringArgumentType.greedyString())
                                .suggests((context, builder) -> {
                                    // Provide combined suggestions
                                    suggestSchematicsAndDirectories(builder);
                                    return builder.buildFuture();
                                })
                                .executes(context -> {
                                    String args = StringArgumentType.getString(context, "args");
                                    return executeComparisonFromArgs(args, context.getSource());
                                }))));
    }

    private static int executeComparisonFromArgs(String args, FabricClientCommandSource source) {
        // Parse the arguments to extract schematic name and directory
        String[] parsed = parseArguments(args);

        if (parsed == null || parsed.length != 2) {
            source.sendFeedback(Text.literal("§cUsage: /compose compare <schematic_name> <directory>"));
            source.sendFeedback(Text.literal("§7Example: /compose compare building.litematic maps/city"));
            source.sendFeedback(Text.literal("§7Example with quotes: /compose compare \"my building.litematic\" \"maps/my city\""));
            return 0;
        }

        return executeComparison(parsed[0], parsed[1], source);
    }

    private static String[] parseArguments(String args) {
        // Handle quoted arguments
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < args.length(); i++) {
            char c = args.charAt(i);

            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ' ' && !inQuotes) {
                if (current.length() > 0) {
                    parts.add(current.toString());
                    current = new StringBuilder();
                }
            } else {
                current.append(c);
            }
        }

        if (current.length() > 0) {
            parts.add(current.toString());
        }

        // If we have exactly 2 parts, great
        if (parts.size() == 2) {
            return new String[] { parts.get(0), parts.get(1) };
        }

        // Try to intelligently split based on .litematic extension
        if (parts.size() > 2) {
            // Find which part contains .litematic
            int litematicIndex = -1;
            for (int i = 0; i < parts.size(); i++) {
                if (parts.get(i).contains(".litematic")) {
                    litematicIndex = i;
                    break;
                }
            }

            if (litematicIndex >= 0) {
                // Combine everything up to and including .litematic as the schematic name
                StringBuilder schematicName = new StringBuilder();
                for (int i = 0; i <= litematicIndex; i++) {
                    if (i > 0) schematicName.append(" ");
                    schematicName.append(parts.get(i));
                }

                // Combine everything after as the directory
                StringBuilder directory = new StringBuilder();
                for (int i = litematicIndex + 1; i < parts.size(); i++) {
                    if (i > litematicIndex + 1) directory.append(" ");
                    directory.append(parts.get(i));
                }

                if (directory.length() > 0) {
                    return new String[] { schematicName.toString(), directory.toString() };
                }
            }
        }

        // If still no luck, try simple space split at first space after .litematic
        int litematicEnd = args.indexOf(".litematic");
        if (litematicEnd > 0) {
            litematicEnd += 10; // Length of ".litematic"
            if (litematicEnd < args.length()) {
                String remaining = args.substring(litematicEnd).trim();
                if (!remaining.isEmpty()) {
                    String schematicName = args.substring(0, litematicEnd).trim();
                    return new String[] { schematicName, remaining };
                }
            }
        }

        return null;
    }

    private static int executeComparison(String schematicName, String directory, FabricClientCommandSource source) {
        try {
            // Validate inputs
            File baseDir = DataManager.getSchematicsBaseDirectory();

            source.sendFeedback(Text.literal("§7Searching for schematic: §f" + schematicName));
            source.sendFeedback(Text.literal("§7In directory: §f" + directory));

            // Load reference schematic
            File referenceFile = findSchematicFile(baseDir, schematicName);
            if (referenceFile == null) {
                source.sendFeedback(Text.literal("§cCould not find schematic: " + schematicName));
                return 0;
            }

            // Load target directory
            File targetDir = new File(baseDir, directory);
            if (!targetDir.exists() || !targetDir.isDirectory()) {
                source.sendFeedback(Text.literal("§cInvalid directory: " + directory));
                return 0;
            }

            source.sendFeedback(Text.literal("§6Loading reference schematic: §f" + referenceFile.getName()));

            // Load reference schematic
            LitematicaSchematic refSchematic = LitematicaSchematic.createFromFile(
                    referenceFile.getParentFile().toPath().toFile(),
                    referenceFile.getName()
            );

            if (refSchematic == null) {
                source.sendFeedback(Text.literal("§cFailed to load reference schematic"));
                return 0;
            }

            // Extract reference schematic top blocks
            Map<BlockPos, BlockState> refTopBlocks = extractTopBlocks(refSchematic);
            if (refTopBlocks.isEmpty()) {
                source.sendFeedback(Text.literal("§cReference schematic contains no blocks"));
                return 0;
            }

            source.sendFeedback(Text.literal("§7Reference schematic has §f" + refTopBlocks.size() + "§7 top blocks"));
            source.sendFeedback(Text.literal("§6Analyzing schematics in directory: §f" + directory));

            // Find all schematics in directory
            List<File> schematicFiles = findAllSchematics(targetDir);
            if (schematicFiles.isEmpty()) {
                source.sendFeedback(Text.literal("§cNo schematics found in directory"));
                return 0;
            }

            source.sendFeedback(Text.literal("§7Found §f" + schematicFiles.size() + "§7 schematics to analyze"));

            // Calculate similarities
            List<SimilarityResult> results = new ArrayList<>();
            SimilarityResult highestResult = null;
            double highestSimilarity = 0.0;

            for (File file : schematicFiles) {
                // Skip if it's the same file as reference
                if (file.getAbsolutePath().equals(referenceFile.getAbsolutePath())) {
                    continue;
                }

                try {
                    LitematicaSchematic schematic = LitematicaSchematic.createFromFile(
                            file.getParentFile().toPath().toFile(),
                            file.getName()
                    );

                    if (schematic == null) {
                        LOGGER.warn("Failed to load schematic: " + file.getName());
                        continue;
                    }

                    Map<BlockPos, BlockState> topBlocks = extractTopBlocks(schematic);
                    if (topBlocks.isEmpty()) {
                        continue;
                    }

                    // Calculate similarity
                    double similarity = calculateSimilarity(refTopBlocks, topBlocks);
                    int matchingBlocks = countMatchingBlocks(refTopBlocks, topBlocks);

                    String relativePath = getRelativePath(baseDir, file);
                    SimilarityResult result = new SimilarityResult(
                            relativePath,
                            similarity,
                            matchingBlocks,
                            Math.max(refTopBlocks.size(), topBlocks.size())
                    );

                    results.add(result);

                    // Track highest similarity
                    if (similarity > highestSimilarity) {
                        highestSimilarity = similarity;
                        highestResult = result;
                    }

                } catch (Exception e) {
                    LOGGER.error("Error analyzing schematic: " + file.getName(), e);
                }
            }

            // Sort results by similarity
            results.sort((a, b) -> Double.compare(b.similarity, a.similarity));

            // Display results
            source.sendFeedback(Text.literal("§a=== Similarity Analysis Complete ==="));

            if (highestResult != null) {
                source.sendFeedback(Text.literal(""));
                source.sendFeedback(Text.literal("§6Highest Similarity Found:"));
                source.sendFeedback(Text.literal(String.format("§f%s", highestResult.schematicPath)));
                source.sendFeedback(Text.literal(String.format("§aSimilarity: §f%.2f%%", highestResult.similarity * 100)));
                source.sendFeedback(Text.literal(String.format("§7Matching blocks: §f%d/%d",
                        highestResult.matchingBlocks, highestResult.totalBlocks)));

                // Show top 5 results if there are more
                if (results.size() > 1) {
                    source.sendFeedback(Text.literal(""));
                    source.sendFeedback(Text.literal("§7Top 5 Similar Schematics:"));

                    int count = 0;
                    for (SimilarityResult result : results) {
                        if (count >= 5) break;
                        source.sendFeedback(Text.literal(String.format("§7%d. §f%.1f%% §8- §7%s",
                                count + 1,
                                result.similarity * 100,
                                result.schematicPath)));
                        count++;
                    }
                }

                // Generate detailed report
                generateReport(schematicName, directory, results, source);

            } else {
                source.sendFeedback(Text.literal("§cNo similar schematics found"));
            }

            return 1;

        } catch (Exception e) {
            LOGGER.error("Error during similarity comparison", e);
            source.sendFeedback(Text.literal("§cError during comparison: " + e.getMessage()));
            return 0;
        }
    }

    /**
     * Extract the top (highest Y) blocks from each x,z position in the schematic
     */
    private static Map<BlockPos, BlockState> extractTopBlocks(LitematicaSchematic schematic) {
        Map<BlockPos, BlockState> topBlocks = new HashMap<>();

        for (String regionName : schematic.getAreas().keySet()) {
            LitematicaBlockStateContainer container = schematic.getSubRegionContainer(regionName);
            Box box = schematic.getAreas().get(regionName);
            BlockPos regionPos = schematic.getSubRegionPosition(regionName);

            if (container == null || box == null || regionPos == null) continue;

            BlockPos size = box.getSize();

            // For each x,z coordinate, find the top non-air block
            for (int x = 0; x < size.getX(); x++) {
                for (int z = 0; z < size.getZ(); z++) {
                    for (int y = size.getY() - 1; y >= 0; y--) {
                        BlockState state = container.get(x, y, z);
                        if (state != null && !state.isAir()) {
                            // Convert to global coordinates
                            BlockPos globalPos = new BlockPos(
                                    regionPos.getX() + x,
                                    regionPos.getY() + y,
                                    regionPos.getZ() + z
                            );
                            topBlocks.put(globalPos, state);
                            break; // Found the top block for this column
                        }
                    }
                }
            }
        }

        return topBlocks;
    }

    /**
     * Calculate similarity between two sets of blocks using normalized positions
     */
    private static double calculateSimilarity(Map<BlockPos, BlockState> blocks1, Map<BlockPos, BlockState> blocks2) {
        if (blocks1.isEmpty() || blocks2.isEmpty()) return 0.0;

        // Normalize coordinates to handle position-independent comparison
        BlockPos offset1 = findMinCorner(blocks1.keySet());
        BlockPos offset2 = findMinCorner(blocks2.keySet());

        Map<BlockPos, BlockState> normalized1 = normalizeBlocks(blocks1, offset1);
        Map<BlockPos, BlockState> normalized2 = normalizeBlocks(blocks2, offset2);

        // Find all unique positions
        Set<BlockPos> allPositions = new HashSet<>(normalized1.keySet());
        allPositions.addAll(normalized2.keySet());
        int totalPositions = allPositions.size();

        if (totalPositions == 0) return 0.0;

        int matchingBlocks = 0;

        // Count matching blocks
        for (BlockPos pos : allPositions) {
            BlockState state1 = normalized1.get(pos);
            BlockState state2 = normalized2.get(pos);

            if (state1 != null && state2 != null && state1.equals(state2)) {
                matchingBlocks++;
            }
        }

        return (double) matchingBlocks / totalPositions;
    }

    /**
     * Count the number of matching blocks (helper method for reporting)
     */
    private static int countMatchingBlocks(Map<BlockPos, BlockState> blocks1, Map<BlockPos, BlockState> blocks2) {
        BlockPos offset1 = findMinCorner(blocks1.keySet());
        BlockPos offset2 = findMinCorner(blocks2.keySet());

        Map<BlockPos, BlockState> normalized1 = normalizeBlocks(blocks1, offset1);
        Map<BlockPos, BlockState> normalized2 = normalizeBlocks(blocks2, offset2);

        Set<BlockPos> allPositions = new HashSet<>(normalized1.keySet());
        allPositions.addAll(normalized2.keySet());

        int matchingBlocks = 0;
        for (BlockPos pos : allPositions) {
            BlockState state1 = normalized1.get(pos);
            BlockState state2 = normalized2.get(pos);

            if (state1 != null && state2 != null && state1.equals(state2)) {
                matchingBlocks++;
            }
        }

        return matchingBlocks;
    }

    /**
     * Find the minimum corner of a set of positions
     */
    private static BlockPos findMinCorner(Set<BlockPos> positions) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;

        for (BlockPos pos : positions) {
            minX = Math.min(minX, pos.getX());
            minY = Math.min(minY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
        }

        return new BlockPos(minX, minY, minZ);
    }

    /**
     * Normalize block positions relative to an offset
     */
    private static Map<BlockPos, BlockState> normalizeBlocks(Map<BlockPos, BlockState> blocks, BlockPos offset) {
        Map<BlockPos, BlockState> normalized = new HashMap<>();

        for (Map.Entry<BlockPos, BlockState> entry : blocks.entrySet()) {
            BlockPos pos = entry.getKey();
            BlockPos normalizedPos = new BlockPos(
                    pos.getX() - offset.getX(),
                    pos.getY() - offset.getY(),
                    pos.getZ() - offset.getZ()
            );
            normalized.put(normalizedPos, entry.getValue());
        }

        return normalized;
    }

    /**
     * Find a schematic file by name (searches recursively)
     */
    private static File findSchematicFile(File baseDir, String name) {
        // First try direct path
        File direct = new File(baseDir, name);
        if (direct.exists() && direct.getName().endsWith(".litematic")) {
            return direct;
        }

        // Add .litematic extension if not present
        if (!name.endsWith(".litematic")) {
            direct = new File(baseDir, name + ".litematic");
            if (direct.exists()) {
                return direct;
            }
        }

        // Search recursively
        return findSchematicRecursive(baseDir, name);
    }

    private static File findSchematicRecursive(File dir, String name) {
        File[] files = dir.listFiles();
        if (files == null) return null;

        for (File file : files) {
            if (file.isDirectory()) {
                File found = findSchematicRecursive(file, name);
                if (found != null) return found;
            } else if (file.getName().equals(name) ||
                    file.getName().equals(name + ".litematic")) {
                return file;
            }
        }

        return null;
    }

    /**
     * Find all schematic files in a directory (recursively)
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
     * Generate a detailed similarity report
     */
    private static void generateReport(String referenceName, String directory,
                                       List<SimilarityResult> results,
                                       FabricClientCommandSource source) {
        try {
            File baseDir = DataManager.getSchematicsBaseDirectory();
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
            File reportFile = new File(baseDir, "similarity_report_" + timestamp + ".txt");

            try (PrintWriter writer = new PrintWriter(new FileWriter(reportFile))) {
                writer.println("Schematic Similarity Analysis Report");
                writer.println("=====================================");
                writer.println("Generated: " + LocalDateTime.now());
                writer.println("Reference Schematic: " + referenceName);
                writer.println("Search Directory: " + directory);
                writer.println("Total Schematics Analyzed: " + results.size());
                writer.println();
                writer.println("Results (sorted by similarity):");
                writer.println("--------------------------------");

                int rank = 1;
                for (SimilarityResult result : results) {
                    writer.println(String.format("%d. %.2f%% - %s",
                            rank++,
                            result.similarity * 100,
                            result.schematicPath));
                    writer.println(String.format("   Matching blocks: %d/%d",
                            result.matchingBlocks,
                            result.totalBlocks));
                    writer.println();
                }

                // Add summary statistics
                writer.println("Summary Statistics:");
                writer.println("-------------------");
                if (!results.isEmpty()) {
                    double avgSimilarity = results.stream()
                            .mapToDouble(r -> r.similarity)
                            .average()
                            .orElse(0.0);
                    writer.println(String.format("Average Similarity: %.2f%%", avgSimilarity * 100));
                    writer.println(String.format("Highest Similarity: %.2f%%", results.get(0).similarity * 100));
                    writer.println(String.format("Lowest Similarity: %.2f%%",
                            results.get(results.size() - 1).similarity * 100));
                }
            }

            source.sendFeedback(Text.literal("§aReport saved to: §f" + reportFile.getName()));

        } catch (Exception e) {
            LOGGER.error("Failed to generate report", e);
            source.sendFeedback(Text.literal("§cFailed to generate report: " + e.getMessage()));
        }
    }

    /**
     * Combined suggestions for both schematics and directories
     */
    private static void suggestSchematicsAndDirectories(SuggestionsBuilder builder) {
        File baseDir = DataManager.getSchematicsBaseDirectory();

        // Get current input to determine context
        String input = builder.getRemaining();

        // If input contains .litematic, we're likely done with schematic name, suggest directories
        if (input.contains(".litematic")) {
            // Extract everything after .litematic
            int index = input.lastIndexOf(".litematic") + 10;
            if (index < input.length()) {
                String prefix = input.substring(0, index) + " ";
                suggestDirectoriesWithPrefix(baseDir, "", builder, prefix);
            }
        } else {
            // Suggest schematics
            suggestSchematicsRecursive(baseDir, "", builder);
        }
    }

    private static void suggestSchematicsRecursive(File dir, String prefix, SuggestionsBuilder builder) {
        File[] files = dir.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isDirectory()) {
                String newPrefix = prefix.isEmpty() ? file.getName() : prefix + "/" + file.getName();
                suggestSchematicsRecursive(file, newPrefix, builder);
            } else if (file.getName().endsWith(".litematic")) {
                String suggestion = prefix.isEmpty() ? file.getName() : prefix + "/" + file.getName();
                // Add space after to prepare for directory argument
                builder.suggest(suggestion + " ");
            }
        }
    }

    private static void suggestDirectoriesWithPrefix(File baseDir, String currentPath,
                                                     SuggestionsBuilder builder, String prefix) {
        File currentDir = new File(baseDir, currentPath);
        File[] files = currentDir.listFiles();

        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    String relativePath = currentPath.isEmpty() ?
                            file.getName() : currentPath + "/" + file.getName();
                    builder.suggest(prefix + relativePath);
                    suggestDirectoriesWithPrefix(baseDir, relativePath, builder, prefix);
                }
            }
        }
    }
}