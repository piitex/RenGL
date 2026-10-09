package me.piitex.engine.render3d.scene.file;

import me.piitex.engine.render3d.scene.Light;
import me.piitex.engine.utils.flags.GeneratedClass;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;

@GeneratedClass(prompter = "piitex", model = "Claude Sonnet 5.5", reviewed = false)
class SceneKindsTooltipTest {
    private static void check(String what, List<? extends SceneProperty<?>> properties) {
        for (SceneProperty<?> property : properties) {
            assertFalse(property.getTooltip().isBlank(), what + "." + property.getName() + " has no tooltip");
        }
    }

    @Test
    void everyPropertyOfEveryPartSaysWhatItDoes() {
        check("node", SceneKinds.node());
        check("material", SceneKinds.material());
        check("camera", SceneKinds.camera());
        check("world", SceneKinds.world());
        for (Light.Type type : Light.Type.values()) check("light " + type, SceneKinds.light(type));
        for (JointKind kind : JointKind.values()) check("joint " + kind, SceneKinds.joint(kind));
    }
}
