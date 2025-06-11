package hecklar.schemtictools;

import hecklar.schemtictools.Commands.ConvertCommand;
import hecklar.schemtictools.Commands.MaterialsCalculatorCommand;
import hecklar.schemtictools.Commands.SchematicBeamCommand;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.ModInitializer;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SchematicTools implements ClientModInitializer {
	public static final String MOD_ID = "schematic-tools";

	// This logger is used to write text to the console and the log file.
	// It is considered best practice to use your mod id as the logger's name.
	// That way, it's clear which mod wrote info, warnings, and errors.
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	private static final MinecraftClient client = MinecraftClient.getInstance();
	@Override
	public void onInitializeClient() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
			ConvertCommand.register(dispatcher);
			MaterialsCalculatorCommand.register(dispatcher);
			SchematicBeamCommand.register(dispatcher);
		});
	}
	public static void sendMessage(String message) {
		if (client.player != null) {
			client.player.sendMessage(Text.literal(message), false);
		}
	}
}