package io.github.lorenzoandreotta.hierarchytree.ui;

import io.github.lorenzoandreotta.hierarchytree.model.AnalysisNode;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

public final class HierarchyTreePanelTest {
    @Test
    public void exposesHierarchyCompileTimeAndRuntimeViews() {
        var panel = new HierarchyTreePanel();

        assertEquals(3, panel.getTabCount());
    }

    @Test
    public void selectsTheViewReceivingAnAnalysis() {
        var panel = new HierarchyTreePanel();
        var root = new AnalysisNode(AnalysisNode.Kind.INFO, "Risultato", "", List.of(), null);

        panel.show(AnalysisView.COMPILE_TIME, root);

        assertEquals(1, panel.getSelectedTabIndex());
    }
}
