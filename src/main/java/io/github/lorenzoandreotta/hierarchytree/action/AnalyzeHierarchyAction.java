package io.github.lorenzoandreotta.hierarchytree.action;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.concurrency.AppExecutorUtil;
import io.github.lorenzoandreotta.hierarchytree.hierarchy.HierarchyAnalyzer;
import io.github.lorenzoandreotta.hierarchytree.ui.AnalysisView;
import io.github.lorenzoandreotta.hierarchytree.ui.HierarchyTreeWindow;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class AnalyzeHierarchyAction extends AnAction {
    private final HierarchyAnalyzer analyzer = new HierarchyAnalyzer();

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        event.getPresentation().setEnabledAndVisible(findClass(event.getData(CommonDataKeys.PSI_ELEMENT)) != null);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        PsiClass selected = ReadAction.compute(() -> findClass(event.getData(CommonDataKeys.PSI_ELEMENT)));
        if (project == null || selected == null) {
            return;
        }

        var pointer = SmartPointerManager.getInstance(project).createSmartPsiElementPointer(selected);
        ReadAction.nonBlocking(() -> {
                    PsiClass current = pointer.getElement();
                    if (current == null || !current.isValid()) {
                        throw new ProcessCanceledException();
                    }
                    return analyzer.analyze(current);
                })
                .inSmartMode(project)
                .expireWith(project)
                .coalesceBy(this, pointer)
                .finishOnUiThread(ModalityState.defaultModalityState(), root ->
                        HierarchyTreeWindow.getInstance(project).show(AnalysisView.HIERARCHY, root))
                .submit(AppExecutorUtil.getAppExecutorService());
    }

    private static @Nullable PsiClass findClass(@Nullable PsiElement element) {
        return element == null ? null : PsiTreeUtil.getParentOfType(element, PsiClass.class, false);
    }
}
