package me.piitex.engine.render3d.scene.file;

import me.piitex.engine.render3d.math.Vec3;
import me.piitex.engine.render3d.scene.Node3D;
import me.piitex.engine.render3d.scene.Scene3D;
import me.piitex.engine.utils.flags.GeneratedClass;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Folders: groups that only hold nodes, so a scene can be kept tidy without anything in them being moved by them.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Sonnet 5.5", reviewed = false)
class SceneFolderTest {
    private static <T> SceneProperty<T> property(java.util.List<SceneProperty<T>> list, String name) {
        return list.stream().filter(p -> p.getName().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void aFolderHoldsNodesAndHasNoPlaceOfItsOwn() {
        Scene3D scene = Scene3DFile.parse("""
                { "format": 1, "nodes": [
                  { "name": "Props", "folder": true, "position": [5, 5, 5], "scale": [2, 2, 2], "children": [
                    { "name": "Crate", "shape": "CUBE", "position": [1, 0, 0] } ] }
                ] }""", Path.of(""), false);
        Node3D props = Scene3DFile.find(scene, "Props");
        assertTrue(Scene3DFile.isFolder(props));
        assertEquals(Vec3.ZERO, props.getPosition(), "a position in the file is not kept");
        assertEquals(Vec3.ONE, props.getScale());
        assertEquals(new Vec3(1f, 0f, 0f), Scene3DFile.find(scene, "Crate").worldMatrix().transformPoint(Vec3.ZERO));
    }

    @Test
    void aFolderIsSavedAsOneAndCarriesNoTransform() {
        Scene3D scene = Scene3DFile.parse("""
                { "format": 1, "nodes": [
                  { "name": "Props", "folder": true, "children": [ { "name": "Crate", "shape": "CUBE" } ] },
                  { "name": "Group", "position": [1, 2, 3], "children": [ { "name": "Ball", "shape": "SPHERE" } ] }
                ] }""", Path.of(""), false);
        String saved = Scene3DFile.toJson(scene);
        assertEquals(1, saved.split("\"folder\": true", -1).length - 1, saved);
        assertEquals(saved, Scene3DFile.toJson(Scene3DFile.parse(saved, Path.of(""), false)));
        assertTrue(Scene3DFile.isFolder(Scene3DFile.find(Scene3DFile.parse(saved, Path.of(""), false), "Props")));
        assertFalse(Scene3DFile.isFolder(Scene3DFile.find(scene, "Group")), "a plain group is not a folder");
        assertEquals(new Vec3(1f, 2f, 3f), Scene3DFile.find(scene, "Group").getPosition());
    }

    @Test
    void movingATurningOrSizingAFolderThroughItsPropertiesDoesNothing() {
        Scene3D scene = Scene3DFile.parse("{ \"format\": 1, \"nodes\": [ { \"name\": \"Props\", \"folder\": true } ] }", Path.of(""), false);
        Node3D props = Scene3DFile.find(scene, "Props");
        property(SceneKinds.node(), "position").set(props, new Vec3(3f, 0f, 0f));
        property(SceneKinds.node(), "rotation").set(props, new Vec3(0f, 90f, 0f));
        property(SceneKinds.node(), "scale").set(props, new Vec3(4f, 4f, 4f));
        assertEquals(Vec3.ZERO, props.getPosition());
        assertEquals(Vec3.ONE, props.getScale());
        assertFalse(property(SceneKinds.node(), "position").appliesTo(props));
        assertFalse(property(SceneKinds.node(), "body").appliesTo(props));
        assertTrue(property(SceneKinds.node(), "visible").appliesTo(props), "a folder can still be hidden");
    }

    @Test
    void makingAGroupAFolderPutsItBackAtTheOriginAndTurningItOffLetsItMoveAgain() {
        Node3D group = SceneKinds.newNode(Shape.NONE, "Group");
        group.setPosition(2f, 0f, 0f);
        group.setScale(3f);
        SceneProperty<Node3D> folder = property(SceneKinds.node(), "folder");
        assertTrue(folder.appliesTo(group));
        folder.set(group, true);
        assertTrue(Scene3DFile.isFolder(group));
        assertEquals(Vec3.ZERO, group.getPosition());
        assertEquals(Vec3.ONE, group.getScale());
        folder.set(group, false);
        property(SceneKinds.node(), "position").set(group, new Vec3(0f, 1f, 0f));
        assertEquals(new Vec3(0f, 1f, 0f), group.getPosition());
    }

    @Test
    void onlyAGroupCanBeAFolder() {
        Node3D cube = SceneKinds.newNode(Shape.CUBE, "Crate");
        assertFalse(property(SceneKinds.node(), "folder").appliesTo(cube));
        assertFalse(Scene3DFile.isFolder(cube));
    }
}
