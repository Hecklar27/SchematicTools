package hecklar.schemtictools.Render;

import net.minecraft.client.render.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.client.render.Camera;
import net.minecraft.client.MinecraftClient;
import net.minecraft.block.BlockState;
import com.mojang.blaze3d.systems.RenderSystem;
import org.joml.Matrix4f;
import java.util.*;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
public class SchematicBeamRenderer {
    private static final Set<BlockPos> incompleteBlocks = Collections.synchronizedSet(new HashSet<>());
    private static boolean renderingEnabled = false;
    private static final float BEAM_HEIGHT = 50.0f;
    private static final float BEAM_WIDTH = 0.2f;
    private static final float BEAM_ALPHA = 0.4f;

    // --- Management methods (unchanged) ---
    public static void addIncompleteBlock(BlockPos pos) {
        synchronized(incompleteBlocks) {
            incompleteBlocks.add(pos.toImmutable());
        }
    }

    public static void removeBlock(BlockPos pos) {
        synchronized(incompleteBlocks) {
            incompleteBlocks.remove(pos);
        }
    }
    public static void clearBlocks() {
        synchronized(incompleteBlocks) {
            incompleteBlocks.clear();
        }
    }

    public static void toggleRendering() {
        renderingEnabled = !renderingEnabled;
    }

    public static boolean isEnabled() {
        return renderingEnabled;
    }

    private static double getDistanceToCamera(BlockPos pos, Vec3d cameraPos) {
        double dx = (pos.getX() + 0.5) - cameraPos.x;
        double dy = (pos.getY() + 0.5) - cameraPos.y;
        double dz = (pos.getZ() + 0.5) - cameraPos.z;
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * Renders the beams for incomplete blocks.
     * IMPROVED SOLUTION: Using proper translucent rendering with better alpha blending
     */
    public static void render(MatrixStack matrices, Camera camera, VertexConsumerProvider vertexConsumers) {
        if (!renderingEnabled || incompleteBlocks.isEmpty() || MinecraftClient.getInstance().world == null) {
            return;
        }

        Vec3d cameraPos = camera.getPos();
        List<BlockPos> sortedBlocks;
        synchronized(incompleteBlocks) {
            sortedBlocks = new ArrayList<>(incompleteBlocks);
        }

        // Sort blocks from farthest to nearest for correct transparency rendering
        sortedBlocks.sort(Comparator.comparingDouble(pos -> getDistanceToCamera((BlockPos) pos, cameraPos)).reversed());

        // Push matrix state
        matrices.push();

        // Enable proper blending for transparency
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull(); // Disable backface culling for better transparency

        Matrix4f positionMatrix = matrices.peek().getPositionMatrix();

        // Render translucent faces using debug rendering
        for (BlockPos pos : sortedBlocks) {
            renderBeamFacesDebug(matrices, vertexConsumers, pos, cameraPos);
        }

        // Render borders using getLines()
        VertexConsumer borderBuffer = vertexConsumers.getBuffer(RenderLayer.getLines());
        for (BlockPos pos : sortedBlocks) {
            renderBeamBorders(borderBuffer, positionMatrix, pos, cameraPos);
        }

        // Restore render state
        RenderSystem.enableCull();
        RenderSystem.disableBlend();

        matrices.pop();
    }
    // Use debug rendering methods which handle vertex format automatically
    private static void renderBeamFacesDebug(MatrixStack matrices, VertexConsumerProvider vertexConsumers, BlockPos pos, Vec3d camera) {
        float x = (float)(pos.getX() - camera.x + 0.5);
        float y = (float)(pos.getY() - camera.y + 1.0);
        float z = (float)(pos.getZ() - camera.z + 0.5);

        float beamHeight = calculateActualBeamHeight(pos);
        if (beamHeight <= 0) return;

        float minX = x - BEAM_WIDTH;
        float maxX = x + BEAM_WIDTH;
        float minY = y;
        float maxY = y + beamHeight;
        float minZ = z - BEAM_WIDTH;
        float maxZ = z + BEAM_WIDTH;

        // Use debug filled box which works with position + color only
        VertexConsumer buffer = vertexConsumers.getBuffer(RenderLayer.getDebugFilledBox());
        Matrix4f matrix = matrices.peek().getPositionMatrix();

        // Draw each face as two triangles with correct vertex ordering
        
        // North face (-Z) - Two triangles
        renderTriangle(buffer, matrix,
                minX, minY, minZ,  // bottom-left
                minX, maxY, minZ,  // top-left  
                maxX, maxY, minZ,  // top-right
                1.0f, 0.0f, 0.0f, BEAM_ALPHA, 0.0f, 0.0f);
        renderTriangle(buffer, matrix,
                minX, minY, minZ,  // bottom-left
                maxX, maxY, minZ,  // top-right
                maxX, minY, minZ,  // bottom-right
                1.0f, 0.0f, 0.0f, BEAM_ALPHA, 0.0f, BEAM_ALPHA);

        // South face (+Z) - Two triangles
        renderTriangle(buffer, matrix,
                maxX, minY, maxZ,  // bottom-right
                maxX, maxY, maxZ,  // top-right
                minX, maxY, maxZ,  // top-left
                1.0f, 0.0f, 0.0f, BEAM_ALPHA, 0.0f, 0.0f);
        renderTriangle(buffer, matrix,
                maxX, minY, maxZ,  // bottom-right
                minX, maxY, maxZ,  // top-left
                minX, minY, maxZ,  // bottom-left
                1.0f, 0.0f, 0.0f, BEAM_ALPHA, 0.0f, BEAM_ALPHA);
                
        // West face (-X) - Two triangles
        renderTriangle(buffer, matrix,
                minX, minY, maxZ,  // bottom-far
                minX, maxY, maxZ,  // top-far
                minX, maxY, minZ,  // top-near
                1.0f, 0.0f, 0.0f, BEAM_ALPHA, 0.0f, 0.0f);
        renderTriangle(buffer, matrix,
                minX, minY, maxZ,  // bottom-far
                minX, maxY, minZ,  // top-near
                minX, minY, minZ,  // bottom-near
                1.0f, 0.0f, 0.0f, BEAM_ALPHA, 0.0f, BEAM_ALPHA);

        // East face (+X) - Two triangles
        renderTriangle(buffer, matrix,
                maxX, minY, minZ,  // bottom-near
                maxX, maxY, minZ,  // top-near
                maxX, maxY, maxZ,  // top-far
                1.0f, 0.0f, 0.0f, BEAM_ALPHA, 0.0f, 0.0f);
        renderTriangle(buffer, matrix,
                maxX, minY, minZ,  // bottom-near
                maxX, maxY, maxZ,  // top-far
                maxX, minY, maxZ,  // bottom-far
                1.0f, 0.0f, 0.0f, BEAM_ALPHA, 0.0f, BEAM_ALPHA);
                
        // Bottom face - Two triangles
        renderTriangle(buffer, matrix,
                minX, minY, maxZ,  // far-left
                minX, minY, minZ,  // near-left
                maxX, minY, minZ,  // near-right
                1.0f, 0.0f, 0.0f, BEAM_ALPHA, BEAM_ALPHA, BEAM_ALPHA);
        renderTriangle(buffer, matrix,
                minX, minY, maxZ,  // far-left
                maxX, minY, minZ,  // near-right
                maxX, minY, maxZ,  // far-right
                1.0f, 0.0f, 0.0f, BEAM_ALPHA, BEAM_ALPHA, BEAM_ALPHA);
                
        // Top face - Two triangles
        renderTriangle(buffer, matrix,
                minX, maxY, minZ,  // near-left
                minX, maxY, maxZ,  // far-left
                maxX, maxY, maxZ,  // far-right
                1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f);
        renderTriangle(buffer, matrix,
                minX, maxY, minZ,  // near-left
                maxX, maxY, maxZ,  // far-right
                maxX, maxY, minZ,  // near-right
                1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f);
    }

    // Helper method to render a triangle with proper vertex ordering
    private static void renderTriangle(VertexConsumer buffer, Matrix4f matrix,
                                      float x1, float y1, float z1,  // vertex 1
                                      float x2, float y2, float z2,  // vertex 2  
                                      float x3, float y3, float z3,  // vertex 3
                                      float r, float g, float b, 
                                      float alpha1, float alpha2, float alpha3) {
        
        buffer.vertex(matrix, x1, y1, z1).color(r, g, b, alpha1);
        buffer.vertex(matrix, x2, y2, z2).color(r, g, b, alpha2);
        buffer.vertex(matrix, x3, y3, z3).color(r, g, b, alpha3);
    }

    // Helper method to render a quad with gradient alpha and proper vertex ordering
    private static void renderQuad(VertexConsumer buffer, Matrix4f matrix,
                                   float x1, float y1, float z1,  // vertex 1
                                   float x2, float y2, float z2,  // vertex 2
                                   float x3, float y3, float z3,  // vertex 3
                                   float x4, float y4, float z4,  // vertex 4
                                   float r, float g, float b, float alphaBottom, float alphaTop) {

        // Determine if this is vertical face (has different Y values) or horizontal
        boolean isVerticalFace = (y1 != y3) || (y2 != y4);
        
        if (isVerticalFace) {
            // For vertical faces: use gradient from bottom (full alpha) to top (transparent)
            // Determine alpha based on Y coordinate (lower Y = bottom = more opaque)
            float alpha1 = (y1 <= Math.min(y2, Math.min(y3, y4))) ? alphaBottom : alphaTop;
            float alpha2 = (y2 <= Math.min(y1, Math.min(y3, y4))) ? alphaBottom : alphaTop;
            float alpha3 = (y3 >= Math.max(y1, Math.max(y2, y4))) ? alphaTop : alphaBottom;
            float alpha4 = (y4 >= Math.max(y1, Math.max(y2, y3))) ? alphaTop : alphaBottom;
            
            // Render quad vertices in order (this creates 2 triangles: 1-2-3 and 1-3-4)
            buffer.vertex(matrix, x1, y1, z1).color(r, g, b, alpha1);
            buffer.vertex(matrix, x2, y2, z2).color(r, g, b, alpha2);
            buffer.vertex(matrix, x3, y3, z3).color(r, g, b, alpha3);
            buffer.vertex(matrix, x4, y4, z4).color(r, g, b, alpha4);
        } else {
            // For horizontal faces: use consistent alpha
            float alpha = (y1 <= y2) ? alphaBottom : alphaTop;
            
            buffer.vertex(matrix, x1, y1, z1).color(r, g, b, alpha);
            buffer.vertex(matrix, x2, y2, z2).color(r, g, b, alpha);
            buffer.vertex(matrix, x3, y3, z3).color(r, g, b, alpha);
            buffer.vertex(matrix, x4, y4, z4).color(r, g, b, alpha);
        }
    }
    private static void renderBeamBorders(VertexConsumer buffer, Matrix4f matrix, BlockPos pos, Vec3d camera) {
        float x = (float)(pos.getX() - camera.x + 0.5);
        float y = (float)(pos.getY() - camera.y + 1.0);
        float z = (float)(pos.getZ() - camera.z + 0.5);

        float beamHeight = calculateActualBeamHeight(pos);
        if (beamHeight <= 0) return;

        float minX = x - BEAM_WIDTH;
        float maxX = x + BEAM_WIDTH;
        float minY = y;
        float maxY = y + beamHeight;
        float minZ = z - BEAM_WIDTH;
        float maxZ = z + BEAM_WIDTH;

        // Vertical edges
        buffer.vertex(matrix, minX, minY, minZ).color(0.0f, 0.0f, 0.0f, 1.0f).normal(0.0f, 1.0f, 0.0f);
        buffer.vertex(matrix, minX, maxY, minZ).color(0.0f, 0.0f, 0.0f, 0.0f).normal(0.0f, 1.0f, 0.0f);

        buffer.vertex(matrix, maxX, minY, minZ).color(0.0f, 0.0f, 0.0f, 1.0f).normal(0.0f, 1.0f, 0.0f);
        buffer.vertex(matrix, maxX, maxY, minZ).color(0.0f, 0.0f, 0.0f, 0.0f).normal(0.0f, 1.0f, 0.0f);

        buffer.vertex(matrix, minX, minY, maxZ).color(0.0f, 0.0f, 0.0f, 1.0f).normal(0.0f, 1.0f, 0.0f);
        buffer.vertex(matrix, minX, maxY, maxZ).color(0.0f, 0.0f, 0.0f, 0.0f).normal(0.0f, 1.0f, 0.0f);

        buffer.vertex(matrix, maxX, minY, maxZ).color(0.0f, 0.0f, 0.0f, 1.0f).normal(0.0f, 1.0f, 0.0f);
        buffer.vertex(matrix, maxX, maxY, maxZ).color(0.0f, 0.0f, 0.0f, 0.0f).normal(0.0f, 1.0f, 0.0f);
        // Bottom edges
        buffer.vertex(matrix, minX, minY, minZ).color(0.0f, 0.0f, 0.0f, 1.0f).normal(0.0f, 1.0f, 0.0f);
        buffer.vertex(matrix, maxX, minY, minZ).color(0.0f, 0.0f, 0.0f, 1.0f).normal(0.0f, 1.0f, 0.0f);

        buffer.vertex(matrix, minX, minY, maxZ).color(0.0f, 0.0f, 0.0f, 1.0f).normal(0.0f, 1.0f, 0.0f);
        buffer.vertex(matrix, maxX, minY, maxZ).color(0.0f, 0.0f, 0.0f, 1.0f).normal(0.0f, 1.0f, 0.0f);

        buffer.vertex(matrix, minX, minY, minZ).color(0.0f, 0.0f, 0.0f, 1.0f).normal(0.0f, 1.0f, 0.0f);
        buffer.vertex(matrix, minX, minY, maxZ).color(0.0f, 0.0f, 0.0f, 1.0f).normal(0.0f, 1.0f, 0.0f);

        buffer.vertex(matrix, maxX, minY, minZ).color(0.0f, 0.0f, 0.0f, 1.0f).normal(0.0f, 1.0f, 0.0f);
        buffer.vertex(matrix, maxX, minY, maxZ).color(0.0f, 0.0f, 0.0f, 1.0f).normal(0.0f, 1.0f, 0.0f);

        // Top edges
        buffer.vertex(matrix, minX, maxY, minZ).color(0.0f, 0.0f, 0.0f, 0.0f).normal(0.0f, 1.0f, 0.0f);
        buffer.vertex(matrix, maxX, maxY, minZ).color(0.0f, 0.0f, 0.0f, 0.0f).normal(0.0f, 1.0f, 0.0f);

        buffer.vertex(matrix, minX, maxY, maxZ).color(0.0f, 0.0f, 0.0f, 0.0f).normal(0.0f, 1.0f, 0.0f);
        buffer.vertex(matrix, maxX, maxY, maxZ).color(0.0f, 0.0f, 0.0f, 0.0f).normal(0.0f, 1.0f, 0.0f);

        buffer.vertex(matrix, minX, maxY, minZ).color(0.0f, 0.0f, 0.0f, 0.0f).normal(0.0f, 1.0f, 0.0f);
        buffer.vertex(matrix, minX, maxY, maxZ).color(0.0f, 0.0f, 0.0f, 0.0f).normal(0.0f, 1.0f, 0.0f);

        buffer.vertex(matrix, maxX, maxY, minZ).color(0.0f, 0.0f, 0.0f, 0.0f).normal(0.0f, 1.0f, 0.0f);
        buffer.vertex(matrix, maxX, maxY, maxZ).color(0.0f, 0.0f, 0.0f, 0.0f).normal(0.0f, 1.0f, 0.0f);
    }
    private static float calculateActualBeamHeight(BlockPos pos) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return 0;

        BlockPos.Mutable checkPos = new BlockPos.Mutable();
        for (int dy = 1; dy <= BEAM_HEIGHT; dy++) {
            checkPos.set(pos.getX(), pos.getY() + dy, pos.getZ());
            if (!client.world.isInBuildLimit(checkPos)) {
                return dy - 1;
            }
            BlockState state = client.world.getBlockState(checkPos);
            if (!state.isAir() && state.getOpacity() > 0) {
                return (float)dy;
            }
        }
        return BEAM_HEIGHT;
    }
}