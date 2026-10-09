package me.piitex.engine.render3d;

import me.piitex.engine.render3d.scene.Material;
import me.piitex.engine.render3d.scene.Mesh;
import me.piitex.engine.render3d.scene.Model;
import me.piitex.engine.render3d.scene.Node3D;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What a glTF file says about placing textures: {@code KHR_texture_transform} and the second set of texture coordinates.
 */
class ModelPlacementTest {
    private static final Path FILE = Path.of("src/test/resources/models/placed.gltf");

    private static void collect(Node3D node, List<Node3D> into) {
        if (node.getMesh() != null) into.add(node);
        for (Node3D child : node.getChildren()) collect(child, into);
    }

    @Test
    void materialsGetTheirTextureTransform() throws Exception {
        Model model = Model.load(FILE);
        List<Node3D> nodes = new ArrayList<>();
        collect(model.root(), nodes);
        assertEquals(2, nodes.size());
        Material placed = null, plain = null;
        for (Node3D n : nodes) {
            if (n.getMaterial().getUvRotation() != 0f) placed = n.getMaterial();
            else plain = n.getMaterial();
        }
        assertNotNull(placed);
        assertNotNull(plain);
        assertEquals(0.1f, placed.getUvOffsetU(), 1e-6f);
        assertEquals(0.2f, placed.getUvOffsetV(), 1e-6f);
        assertEquals(2f, placed.getUvScaleU(), 1e-6f);
        assertEquals(3f, placed.getUvScaleV(), 1e-6f);
        assertEquals(0.5f, placed.getUvRotation(), 1e-6f);
        assertEquals(0f, plain.getUvOffsetU());
        assertEquals(1f, plain.getUvScaleU());
        assertEquals(0f, plain.getUvRotation());
    }

    @Test
    void textureCoordinateSetsAndTheSecondSetOfCoordinatesLoad() throws Exception {
        Model model = Model.load(FILE);
        List<Node3D> nodes = new ArrayList<>();
        collect(model.root(), nodes);
        Node3D placed = null, plain = null;
        for (Node3D n : nodes) {
            if (n.getMaterial().getUvRotation() != 0f) placed = n;
            else plain = n;
        }
        Material m = placed.getMaterial();
        assertEquals(0, m.getTexCoordSet(Material.Slot.BASE_COLOR));
        assertEquals(1, m.getTexCoordSet(Material.Slot.OCCLUSION));
        assertEquals(0, plain.getMaterial().getTexCoordSet(Material.Slot.OCCLUSION));

        Mesh mesh = placed.getMesh();
        assertTrue(mesh.hasUvs2(), "TEXCOORD_1 came through");
        float[] uvs2 = mesh.uvs2();
        assertEquals(mesh.vertexCount() * 2, uvs2.length);
        // Each vertex carries its own second coordinates: 0.5 to 0.75 from the file.
        for (float uv : uvs2) assertTrue(uv >= 0.5f - 1e-6f && uv <= 0.75f + 1e-6f, "coordinate " + uv);
        assertFalse(plain.getMesh().hasUvs2());
    }

    @Test
    void aFileWithoutTheExtensionLeavesTheDefaults() throws Exception {
        Model model = Model.load(Path.of("src/test/resources/models/bar.gltf"));
        List<Node3D> nodes = new ArrayList<>();
        collect(model.root(), nodes);
        Material m = nodes.getFirst().getMaterial();
        assertEquals(1f, m.getUvScaleU());
        assertEquals(0f, m.getUvOffsetU());
        assertEquals(0, m.getTexCoordSet(Material.Slot.BASE_COLOR));
    }

    @Test
    void anInMemoryGlbOrGltfCarriesItToo() throws Exception {
        Model model = Model.load("placed.gltf", Files.readAllBytes(FILE));
        List<Node3D> nodes = new ArrayList<>();
        collect(model.root(), nodes);
        boolean found = false;
        for (Node3D n : nodes) found |= n.getMaterial().getUvRotation() == 0.5f;
        assertTrue(found);
    }
}
