package io.github.ocioman.polyanalyzer.ui;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.ContentFactory;
import io.github.ocioman.polyanalyzer.model.AnalysisNode;
import org.jetbrains.annotations.NotNull;

@Service(Service.Level.PROJECT)
public final class PolyAnalyzerWindow {
    public static final String TOOL_WINDOW_ID = "PolyAnalyzer";

    private final Project project;
    private PolyAnalyzerPanel panel;

    public PolyAnalyzerWindow(@NotNull Project project) {
        this.project = project;
    }

    public static @NotNull PolyAnalyzerWindow getInstance(@NotNull Project project) {
        return project.getService(PolyAnalyzerWindow.class);
    }

    public void initialize(@NotNull ToolWindow toolWindow) {
        if (panel != null) {
            return;
        }
        panel = new PolyAnalyzerPanel();
        var content = ContentFactory.getInstance().createContent(panel, null, false);
        content.setPreferredFocusableComponent(panel);
        toolWindow.getContentManager().addContent(content);
    }

    public void show(@NotNull AnalysisView view, @NotNull AnalysisNode root) {
        ToolWindowManager.getInstance(project).invokeLater(() -> {
            var toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID);
            if (toolWindow == null) {
                return;
            }
            toolWindow.show(() -> showInToolWindow(toolWindow, view, root));
        });
    }

    void showInToolWindow(@NotNull ToolWindow toolWindow, @NotNull AnalysisView view, @NotNull AnalysisNode root) {
        if (panel == null) {
            initialize(toolWindow);
        }
        panel.show(view, root);
    }
}
