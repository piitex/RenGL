package me.piitex.engine.ui.file;

import me.piitex.engine.ui.Element;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.containers.SplitContainer;
import me.piitex.engine.ui.containers.TabContainer;
import me.piitex.engine.ui.layout.DockLayout;
import me.piitex.engine.utils.flags.GeneratedClass;

import java.util.ArrayList;
import java.util.List;

/**
 * How a kind of container holds the elements a layout file puts in it. Most just keep them in order, but a tab
 * container also has a bar of its own, a split container a divider, and some hold a fixed number: this says which of
 * what {@link Container#getElements()} returns are the layout's own, and how to add and take them out.
 * <p>
 * Callers go through {@link UiKinds#children}, {@link UiKinds#add} and {@link UiKinds#remove} rather than using one of
 * these directly.
 */
@GeneratedClass(prompter = "piitex", model = "Claude Sonnet 5.5", reviewed = false)
public interface UiChildren {
    /** The elements of {@code container} that belong to the layout, in order. */
    List<Element> of(Container container);

    /**
     * Adds {@code child} as the {@code index}th element; the last when {@code index} is past the end or negative. The
     * container must have room, see {@link #full}.
     */
    void add(Container container, Element child, int index);

    /** Takes {@code child} out, leaving it free to go somewhere else. */
    void remove(Container container, Element child);

    /** Why {@code container} can't take another element, or null if it can. */
    default String full(Container container) {
        return null;
    }

    /** Makes {@code child} visible, such as by choosing its tab. */
    default void show(Container container, Element child) {
    }

    /**
     * Whether {@code container} decides the width ({@code horizontal}) or the height of {@code child}, so a size
     * set on the child would be undone.
     */
    default boolean sizes(Container container, Element child, boolean horizontal) {
        return false;
    }

    /** Elements kept in order, one after another: containers and the layouts that arrange them in turn. */
    UiChildren PLAIN = new UiChildren() {
        @Override
        public List<Element> of(Container container) {
            return new ArrayList<>(container.getElements().values());
        }

        /**
         * A container keeps its children under draw indexes, so a child can only be added on top; putting one between
         * others takes the ones after it off and adds them back.
         */
        @Override
        public void add(Container container, Element child, int index) {
            List<Element> children = of(container);
            int at = index < 0 ? children.size() : Math.min(index, children.size());
            if (at == children.size()) {
                container.addElement(child);
            } else {
                for (int i = at; i < children.size(); i++) container.removeElement(children.get(i));
                container.addElement(child);
                for (int i = at; i < children.size(); i++) container.addElement(children.get(i));
            }
        }

        @Override
        public void remove(Container container, Element child) {
            container.removeElement(child);
        }
    };

    /** Elements that hold nothing a layout can edit, such as a number field with its own buttons inside. */
    UiChildren NONE = new UiChildren() {
        @Override
        public List<Element> of(Container container) {
            return List.of();
        }

        @Override
        public void add(Container container, Element child, int index) {
            throw new UnsupportedOperationException("this kind of element holds no others");
        }

        @Override
        public void remove(Container container, Element child) {
        }

        @Override
        public String full(Container container) {
            return "This kind of element holds no others";
        }
    };

    /** A tab container: each element is the content of a tab, titled by its {@code tabTitle}. */
    UiChildren TABS = new UiChildren() {
        @Override
        public List<Element> of(Container container) {
            List<Element> contents = new ArrayList<>();
            for (TabContainer.Tab tab : ((TabContainer) container).getTabs()) contents.add(tab.getContent());
            return contents;
        }

        @Override
        public void add(Container container, Element child, int index) {
            TabContainer tabs = (TabContainer) container;
            int at = index < 0 ? tabs.getTabCount() : Math.min(index, tabs.getTabCount());
            tabs.addTab(at, UiKinds.tabTitle(child), child);
        }

        @Override
        public void remove(Container container, Element child) {
            TabContainer.Tab tab = tabOf((TabContainer) container, child);
            if (tab != null) ((TabContainer) container).removeTab(tab);
        }

        @Override
        public void show(Container container, Element child) {
            TabContainer.Tab tab = tabOf((TabContainer) container, child);
            if (tab != null) ((TabContainer) container).selectTab(tab);
        }

        @Override
        public boolean sizes(Container container, Element child, boolean horizontal) {
            return true;
        }
    };

    /** A split container: the first and second pane. */
    UiChildren SPLIT = new UiChildren() {
        @Override
        public List<Element> of(Container container) {
            SplitContainer split = (SplitContainer) container;
            List<Element> panes = new ArrayList<>();
            if (split.getFirst() != null) panes.add(split.getFirst());
            if (split.getSecond() != null) panes.add(split.getSecond());
            return panes;
        }

        @Override
        public void add(Container container, Element child, int index) {
            SplitContainer split = (SplitContainer) container;
            List<Element> panes = of(container);
            if (panes.size() >= 2) throw new IllegalStateException(full(container));
            panes.add(index < 0 ? panes.size() : Math.min(index, panes.size()), child);
            // The panes are placed by which side they are on, so a pane that is added before another moves it over.
            for (Element pane : of(container)) split.removeElement(pane);
            split.setFirst(panes.get(0));
            if (panes.size() > 1) split.setSecond(panes.get(1));
        }

        @Override
        public void remove(Container container, Element child) {
            container.removeElement(child);
        }

        @Override
        public String full(Container container) {
            return of(container).size() >= 2 ? "A split holds two elements; put them in a container to add more" : null;
        }

        @Override
        public boolean sizes(Container container, Element child, boolean horizontal) {
            return true;
        }
    };

    /** A dock layout: elements in order, each with the {@code dock} it was given. */
    UiChildren DOCK = new UiChildren() {
        @Override
        public List<Element> of(Container container) {
            return PLAIN.of(container);
        }

        @Override
        public void add(Container container, Element child, int index) {
            ((DockLayout) container).setDock(child, UiKinds.dockOf(child));
            PLAIN.add(container, child, index);
        }

        @Override
        public void remove(Container container, Element child) {
            container.removeElement(child);
        }

        @Override
        public boolean sizes(Container container, Element child, boolean horizontal) {
            DockLayout.Dock dock = ((DockLayout) container).getDock(child);
            return switch (dock) {
                case TOP, BOTTOM -> horizontal;
                case LEFT, RIGHT -> !horizontal;
                case FILL -> true;
            };
        }
    };

    private static TabContainer.Tab tabOf(TabContainer tabs, Element content) {
        for (TabContainer.Tab tab : tabs.getTabs()) if (tab.getContent() == content) return tab;
        return null;
    }
}
