package io.github.ocioman.hierarchytree.action;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.concurrency.AppExecutorUtil;
import io.github.ocioman.hierarchytree.dispatch.StaticDispatchAnalyzer;
import io.github.ocioman.hierarchytree.ui.AnalysisView;
import io.github.ocioman.hierarchytree.ui.HierarchyTreeWindow;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class AnalyzeDispatchAction extends AnAction {
    private final StaticDispatchAnalyzer analyzer = new StaticDispatchAnalyzer();

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        event.getPresentation().setEnabledAndVisible(findCall(ActionTarget.elementAtCaret(event)) != null);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        PsiMethodCallExpression selected = ReadAction.compute(() -> findCall(ActionTarget.elementAtCaret(event)));
        if (project == null || selected == null) {
            return;
        }

        var pointer = SmartPointerManager.getInstance(project).createSmartPsiElementPointer(selected);
        ReadAction.nonBlocking(() -> {
                    PsiMethodCallExpression current = pointer.getElement();
                    if (current == null || !current.isValid()) {
                        throw new ProcessCanceledException();
                    }
                    return analyzer.analyze(current);
                })
                .inSmartMode(project)
                .expireWith(project)
                .coalesceBy(this, pointer)
                .finishOnUiThread(ModalityState.defaultModalityState(), root ->
                        HierarchyTreeWindow.getInstance(project).show(AnalysisView.COMPILE_TIME, root))
                .submit(AppExecutorUtil.getAppExecutorService());
    }

    private static @Nullable PsiMethodCallExpression findCall(@Nullable PsiElement element) {
        return element == null ? null : PsiTreeUtil.getParentOfType(element, PsiMethodCallExpression.class, false);
    }
}
