package io.github.lorenzoandreotta.hierarchytree.ui;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.ContentFactory;
import io.github.lorenzoandreotta.hierarchytree.model.AnalysisNode;
import org.jetbrains.annotations.NotNull;

@Service(Service.Level.PROJECT)
public final class HierarchyTreeWindow {
    public static final String TOOL_WINDOW_ID = "Hierarchy Tree";

    private final Project project;
    private HierarchyTreePanel panel;

    public HierarchyTreeWindow(@NotNull Project project) {
        this.project = project;
    }

    public static @NotNull HierarchyTreeWindow getInstance(@NotNull Project project) {
        return project.getService(HierarchyTreeWindow.class);
    }

    public void initialize(@NotNull ToolWindow toolWindow) {
        if (panel != null) {
            return;
        }
        panel = new HierarchyTreePanel();
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
