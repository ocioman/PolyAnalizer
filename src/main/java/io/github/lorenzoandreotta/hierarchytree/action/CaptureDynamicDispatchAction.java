package io.github.lorenzoandreotta.hierarchytree.action;

import com.intellij.debugger.engine.JavaStackFrame;
import com.intellij.openapi.util.CheckedDisposable;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.project.Project;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XDebugSessionListener;
import com.intellij.xdebugger.XDebuggerManager;
import io.github.lorenzoandreotta.hierarchytree.dispatch.DebuggerSnapshotCapturer;
import io.github.lorenzoandreotta.hierarchytree.dispatch.RuntimeDispatchAnalyzer;
import io.github.lorenzoandreotta.hierarchytree.model.AnalysisNode;
import io.github.lorenzoandreotta.hierarchytree.ui.AnalysisView;
import io.github.lorenzoandreotta.hierarchytree.ui.HierarchyTreeWindow;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class CaptureDynamicDispatchAction extends AnAction {
    private final RuntimeDispatchAnalyzer analyzer = new RuntimeDispatchAnalyzer();

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.EDT;
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        var session = currentSession(event.getProject());
        event.getPresentation().setEnabledAndVisible(
                session != null && session.isSuspended() && session.getCurrentStackFrame() instanceof JavaStackFrame
        );
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        var session = currentSession(project);
        if (project == null || session == null || !session.isSuspended()
                || !(session.getCurrentStackFrame() instanceof JavaStackFrame frame)) {
            showMessage(project, "Seleziona un frame Java in una sessione sospesa");
            return;
        }

        CheckedDisposable lifetime = Disposer.newCheckedDisposable(project, "Hierarchy Tree debugger capture");
        session.addSessionListener(new XDebugSessionListener() {
            @Override
            public void sessionResumed() {
                showMessage(project, "Sessione ripresa: acquisire nuovamente il frame");
                Disposer.dispose(lifetime);
            }

            @Override
            public void sessionStopped() {
                showMessage(project, "Sessione terminata: il risultato runtime non è più aggiornato");
                Disposer.dispose(lifetime);
            }
        }, lifetime);

        DebuggerSnapshotCapturer.capture(project, session, frame, snapshot -> {
            if (lifetime.isDisposed()) {
                return;
            }
            ReadAction.nonBlocking(() -> analyzer.analyze(project, snapshot))
                    .inSmartMode(project)
                    .expireWith(project)
                    .expireWhen(() -> !session.isSuspended() || lifetime.isDisposed())
                    .coalesceBy(this, session)
                    .finishOnUiThread(ModalityState.defaultModalityState(), root ->
                            HierarchyTreeWindow.getInstance(project).show(AnalysisView.RUNTIME, root))
                    .submit(AppExecutorUtil.getAppExecutorService());
        }, message -> {
            showMessage(project, message);
            Disposer.dispose(lifetime);
        });
    }

    private static @Nullable XDebugSession currentSession(@Nullable Project project) {
        return project == null ? null : XDebuggerManager.getInstance(project).getCurrentSession();
    }

    private static void showMessage(@Nullable Project project, @NotNull String message) {
        if (project == null || project.isDisposed()) {
            return;
        }
        var root = AnalysisNode.leaf(
                AnalysisNode.Kind.WARNING,
                "Dispatch dinamico non disponibile",
                message,
                null
        );
        HierarchyTreeWindow.getInstance(project).show(AnalysisView.RUNTIME, root);
    }
}
