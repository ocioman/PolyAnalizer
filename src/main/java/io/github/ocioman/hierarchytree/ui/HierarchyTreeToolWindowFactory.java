package io.github.ocioman.hierarchytree.ui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import org.jetbrains.annotations.NotNull;

import javax.swing.UIManager;
import java.awt.Color;

public final class HierarchyTreeToolWindowFactory implements ToolWindowFactory {
    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        HierarchyTreeWindow.getInstance(project).initialize(toolWindow);
        ApplicationManager.getApplication().invokeLater(() -> {
            UIManager.getLookAndFeelDefaults().put("ToolWindow.Button.selectedForeground", Color.WHITE);
            toolWindow.setIcon(IconLoader.getIcon("/icons/polyAnalizerToolWindow.svg", HierarchyTreeToolWindowFactory.class));
        });
    }
}
