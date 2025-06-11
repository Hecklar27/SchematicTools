package hecklar.schemtictools.Commands;

import com.mojang.brigadier.CommandDispatcher;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3i;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.malilib.gui.Message.MessageType;
import fi.dy.masa.malilib.util.InfoUtils;

import java.io.File;
import java.util.ArrayDeque;
import java.util.Queue;

public class ConvertCommand {

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(ClientCommandManager.literal("tools")
                .then(ClientCommandManager.literal("convert")
                        .then(ClientCommandManager.argument("path", StringArgumentType.greedyString())
                                .suggests((context, builder) -> {
                                    suggestPaths(DataManager.getSchematicsBaseDirectory(), "", builder);
                                    return builder.buildFuture();
                                })
                                .then(ClientCommandManager.argument("recursive", BoolArgumentType.bool())
                                        .executes(context -> {
                                            String path = StringArgumentType.getString(context, "path");
                                            boolean recursive = BoolArgumentType.getBool(context, "recursive");
                                            return executeConversionForPath(path, recursive, context.getSource());
                                        }))
                                .executes(context -> {
                                    String path = StringArgumentType.getString(context, "path");
                                    return executeConversionForPath(path, false, context.getSource());
                                }))));
    }

    private static void suggestPaths(File baseDir, String currentPath, SuggestionsBuilder builder) {
        File currentDir = new File(baseDir, currentPath);
        File[] files = currentDir.listFiles();

        if (files != null) {
            for (File file : files) {
                String relativePath = currentPath.isEmpty() ? file.getName() : currentPath + "/" + file.getName();

                if (file.isDirectory()) {
                    builder.suggest(relativePath + "/");
                    suggestPaths(baseDir, relativePath, builder);
                } else if (file.getName().endsWith(".nbt")) {
                    builder.suggest(relativePath);
                }
            }
        }
    }

    private static int executeConversionForPath(String path, boolean recursive, FabricClientCommandSource source) {
        File baseDir = DataManager.getSchematicsBaseDirectory();
        File targetPath = new File(baseDir, path);

        if (!targetPath.exists()) {
            InfoUtils.showGuiOrInGameMessage(MessageType.ERROR, "Path not found: " + path);
            return 0;
        }

        if (targetPath.isFile()) {
            return convertSingleFile(targetPath, source);
        } else {
            return convertDirectory(targetPath, recursive, source);
        }
    }

    private static int convertDirectory(File directory, boolean recursive, FabricClientCommandSource source) {
        int successCount = 0;
        int failureCount = 0;
        Queue<File> directories = new ArrayDeque<>();
        directories.add(directory);

        while (!directories.isEmpty()) {
            File currentDir = directories.poll();
            File[] files = currentDir.listFiles();

            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory() && recursive) {
                        directories.add(file);
                    } else if (file.getName().endsWith(".nbt")) {
                        if (convertSingleFile(file, source) > 0) {
                            successCount++;
                        } else {
                            failureCount++;
                        }
                    }
                }
            }
        }

        InfoUtils.showGuiOrInGameMessage(MessageType.INFO,
                String.format("Conversion complete. Success: %d, Failed: %d", successCount, failureCount));
        return successCount;
    }

    private static int convertSingleFile(File nbtFile, FabricClientCommandSource source) {
        try {
            // Read NBT file
            NbtCompound nbtData = LitematicaSchematic.readNbtFromFile(nbtFile.toPath().toFile());
            if (nbtData == null) {
                InfoUtils.showGuiOrInGameMessage(MessageType.ERROR, "Failed to read NBT data from: " + nbtFile.getName());
                return 0;
            }

            // Get structure size and create proper area selection
            Vec3i size = null;
            String name = nbtFile.getName().replace(".nbt", "");
            boolean isVanilla = nbtData.contains("size") && nbtData.contains("blocks");
            boolean isSponge = LitematicaSchematic.isValidSpongeSchematic(nbtData);

            if (isVanilla) {
                // For vanilla structures, read the size list directly from the NBT
                if (nbtData.contains("size") && nbtData.get("size") instanceof NbtList) {
                    NbtList sizeList = (NbtList) nbtData.get("size");
                    if (sizeList.size() == 3) {
                        size = new Vec3i(
                                sizeList.getInt(0),
                                sizeList.getInt(1),
                                sizeList.getInt(2)
                        );
                    }
                }
            } else if (isSponge) {
                size = LitematicaSchematic.readSizeFromTagSponge(nbtData);
            } else {
                InfoUtils.showGuiOrInGameMessage(MessageType.ERROR,
                        "Unsupported NBT format for " + nbtFile.getName() + " - must be vanilla structure or Sponge schematic");
                return 0;
            }

            if (size == null) {
                InfoUtils.showGuiOrInGameMessage(MessageType.ERROR,
                        "Failed to read structure size from: " + nbtFile.getName());
                return 0;
            }

            // Rest of the method remains the same...
            // Create area selection with proper size
            AreaSelection selection = new AreaSelection();
            selection.setName(name);

            // Create a box with the correct dimensions
            BlockPos originPos = source.getPlayer().getBlockPos();
            BlockPos endPos = originPos.add(size.getX() - 1, size.getY() - 1, size.getZ() - 1);
            Box box = new Box(originPos, endPos, name);

            // Add the box to selection
            selection.addSubRegionBox(box, true);

            // Create schematic placeholder
            LitematicaSchematic schematic = LitematicaSchematic.createFromWorld(
                    source.getPlayer().getWorld(),
                    selection,
                    new LitematicaSchematic.SchematicSaveInfo(false, false),
                    source.getPlayer().getName().getString(),
                    InfoUtils.INFO_MESSAGE_CONSUMER
            );

            if (schematic == null) {
                InfoUtils.showGuiOrInGameMessage(MessageType.ERROR,
                        "Failed to create schematic for: " + nbtFile.getName());
                return 0;
            }

            // Convert based on format
            boolean conversionSuccess;
            if (isVanilla) {
                conversionSuccess = schematic.readFromVanillaStructure(name, nbtData);
            } else {
                conversionSuccess = schematic.readFromSpongeSchematic(name, nbtData);
            }

            if (!conversionSuccess) {
                InfoUtils.showGuiOrInGameMessage(MessageType.ERROR,
                        "Failed to convert: " + nbtFile.getName());
                return 0;
            }

            // Save converted schematic - preserve directory structure
            String relativePath = nbtFile.getParent().substring(
                    DataManager.getSchematicsBaseDirectory().toString().length()
            );
            File outputDir = new File(DataManager.getSchematicsBaseDirectory(), relativePath);
            outputDir.mkdirs();

            File outputFile = new File(outputDir, name + ".litematic");
            if (schematic.writeToFile(outputDir.toPath().toFile(), name + ".litematic", true)) {
                InfoUtils.showGuiOrInGameMessage(MessageType.SUCCESS,
                        "Converted: " + outputFile.getName());
                return 1;
            } else {
                InfoUtils.showGuiOrInGameMessage(MessageType.ERROR,
                        "Failed to save: " + outputFile.getName());
                return 0;
            }

        } catch (Exception e) {
            InfoUtils.showGuiOrInGameMessage(MessageType.ERROR,
                    "Error converting " + nbtFile.getName() + ": " + e.getMessage());
            e.printStackTrace();
            return 0;
        }
    }
}
