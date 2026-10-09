package me.piitex.engine.entity;

import me.piitex.engine.property.PropertyInfo;
import me.piitex.engine.property.TypeInfo;
import me.piitex.engine.utils.flags.GeneratedClass;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;

@GeneratedClass(prompter = "piitex", model = "Claude Sonnet 5.5", reviewed = false)
class ComponentTooltipTest {
    @Test
    void everyPropertyOfTheEnginesComponentsSaysWhatItDoes() {
        for (Class<? extends Component> type : List.of(SpriteRenderer.class, BoxCollider.class, CircleCollider.class)) {
            for (PropertyInfo property : TypeInfo.of(type).getProperties()) {
                assertFalse(property.getTooltip().isBlank(), type.getSimpleName() + "." + property.getName() + " has no tooltip");
            }
        }
    }
}
