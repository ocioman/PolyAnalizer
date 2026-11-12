package io.github.ocioman.polyanalyzer.ui;

import io.github.ocioman.polyanalyzer.model.AnalysisNode;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

public final class PolyAnalyzerPanelTest {
    @Test
    public void exposesHierarchyCompileTimeAndRuntimeViews() {
        var panel = new PolyAnalyzerPanel();

        assertEquals(3, panel.getTabCount());
    }

    @Test
    public void selectsTheViewReceivingAnAnalysis() {
        var panel = new PolyAnalyzerPanel();
        var root = new AnalysisNode(AnalysisNode.Kind.INFO, "Result", "", List.of(), null);

        panel.show(AnalysisView.COMPILE_TIME, root);

        assertEquals(1, panel.getSelectedTabIndex());
    }
}
