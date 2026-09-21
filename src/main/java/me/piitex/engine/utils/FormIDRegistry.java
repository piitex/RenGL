package me.piitex.engine.utils;

import me.piitex.engine.utils.flags.GeneratedClass;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mints unique {@link FormID}s for one {@link Namespace}, auto-assigning a category
 * byte per distinct category key the first time it's seen, then handing out
 * sequential local IDs within that category.
 * <p>
 * Every category is tracked under two different strings: a collision-safe internal
 * key (a Class's fully-qualified name, when {@link #generate(Class)} is used), and a
 * separate, short display label (that Class's simple name) used only to build a
 * human-readable {@link FormID#getLabel()}. See {@link FormID}'s class docs for why
 * collision-safety and readability call for two different strings rather than one
 * compromise between them.
 * <p>
 * Category assignment is dynamic and order-dependent: the first category key this
 * registry sees in a given run gets code 1, the second gets code 2, and so on. That
 * means the same category can end up with a different byte on two separate runs of the
 * program if construction order is not identical. That is harmless for IDs that are
 * purely in-memory and session-local, which is what this class assumes, but it is not
 * safe to rely on if a FormID is persisted to disk, sent over a network, or compared
 * across two process runs. Supporting that would require a fixed, explicitly declared
 * category table instead, following the same idea as {@link Namespace} one level down.
 */
@GeneratedClass(
        prompter = "piitex",
        model = "Gemini 3.1",
        reviewed = true
)
public final class FormIDRegistry {
    private final Namespace namespace;

    // Category mapping (fully-qualified key -> assigned byte code)
    private final Map<String, Integer> categoryCodes = new ConcurrentHashMap<>();
    // Assigned byte code -> short display name, used only for FormID#getLabel()
    private final Map<Integer, String> categoryLabels = new ConcurrentHashMap<>();
    private final AtomicInteger nextCategoryCode = new AtomicInteger(1);

    // Sequential ID counters per category code.
    private final Map<Integer, AtomicInteger> localCounters = new ConcurrentHashMap<>();

    public FormIDRegistry(Namespace namespace) {
        this.namespace = namespace;
    }

    /**
     * Registers a category key dynamically if it doesn't exist, returning its code.
     * displayLabel is only stored the first time a given key is seen (i.e. whichever
     * call first registers a category wins its label). See the class-level docs above
     * for why key and displayLabel are allowed to differ.
     */
    public int getOrRegisterCategory(String categoryKey, String displayLabel) {
        return categoryCodes.computeIfAbsent(categoryKey, key -> {
            int code = nextCategoryCode.getAndIncrement();
            if (code > 0xFF) {
                throw new IllegalStateException("Maximum category limit (255) exceeded.");
            }
            categoryLabels.put(code, displayLabel);
            return code;
        });
    }

    /**
     * Generates a unique FormID for a given category key, using that same key as both
     * the internal collision-safe key and the display label. Prefer
     * {@link #generate(Class)} when you have a Class available, so the label can be
     * the short simple name rather than whatever string you pass here.
     */
    public FormID generate(String categoryKey) {
        return generate(categoryKey, categoryKey);
    }

    /**
     * Generates a unique FormID using the given class's fully-qualified name as the
     * collision-safe category key, and its simple name as the human-readable label. A
     * {@code me.piitex.engine.ui.overlays.TextOverlay} therefore gets the label
     * "TextOverlay" rather than its full package path.
     */
    public FormID generate(Class<?> type) {
        String simpleName = type.getSimpleName();
        // Anonymous classes return "" from getSimpleName(), so fall back to the full
        // name in that case rather than showing a blank label.
        String displayLabel = simpleName.isEmpty() ? type.getName() : simpleName;
        return generate(type.getName(), displayLabel);
    }

    private FormID generate(String categoryKey, String displayLabel) {
        int categoryCode = getOrRegisterCategory(categoryKey, displayLabel);

        AtomicInteger counter = localCounters.computeIfAbsent(categoryCode, k -> new AtomicInteger(1));
        int localId = counter.getAndIncrement();

        if (localId > 0xFFFFFF) {
            throw new IllegalStateException("Maximum ID limit (16,777,215) for category '" + categoryKey + "' exceeded.");
        }

        String label = namespace.name() + ":" + categoryLabels.get(categoryCode) + "#" + localId;
        return FormID.create(namespace.value(), categoryCode, localId, label);
    }

}