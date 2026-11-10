package io.github.lorenzoandreotta.hierarchytree.dispatch;

import com.intellij.debugger.engine.DebugProcessImpl;
import com.intellij.debugger.engine.JavaStackFrame;
import com.intellij.debugger.engine.SuspendContextImpl;
import com.intellij.debugger.engine.events.DebuggerContextCommandImpl;
import com.intellij.openapi.project.Project;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XSourcePosition;
import com.intellij.xdebugger.frame.XStackFrame;
import com.intellij.xdebugger.frame.XExecutionStack;
import com.sun.jdi.Method;
import com.sun.jdi.ObjectReference;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Consumer;

public final class DebuggerSnapshotCapturer {
    private DebuggerSnapshotCapturer() {
    }

    public static void capture(
            @NotNull Project project,
            @NotNull XDebugSession session,
            @NotNull JavaStackFrame frame,
            @NotNull Consumer<DebuggerSnapshot> onSuccess,
            @NotNull Consumer<String> onError
    ) {
        var suspendContext = session.getSuspendContext();
        var executionStack = suspendContext == null ? null : suspendContext.getActiveExecutionStack();
        if (executionStack == null) {
            onError.accept("Nessuno stack di esecuzione attivo");
            return;
        }

        int callerIndex = frame.getDescriptor().getUiIndex() + 1;
        executionStack.computeStackFrames(callerIndex, new XExecutionStack.XStackFrameContainer() {
            @Override
            public void addStackFrames(@NotNull List<? extends XStackFrame> frames, boolean last) {
                XSourcePosition callerPosition = frames.isEmpty() ? null : frames.getFirst().getSourcePosition();
                var callerLocation = toLocation(callerPosition);
                scheduleCapture(project, frame, callerLocation, onSuccess, onError);
            }

            @Override
            public void errorOccurred(@NotNull String errorMessage) {
                onError.accept(errorMessage);
            }

            @Override
            public boolean isObsolete() {
                return !session.isSuspended();
            }
        });
    }

    private static void scheduleCapture(
            @NotNull Project project,
            @NotNull JavaStackFrame frame,
            DebuggerSnapshot.@Nullable SourceLocation callerLocation,
            @NotNull Consumer<DebuggerSnapshot> onSuccess,
            @NotNull Consumer<String> onError
    ) {
        if (!(frame.getDescriptor().getDebugProcess() instanceof DebugProcessImpl debugProcess)) {
            onError.accept("Il frame non appartiene a un debugger Java");
            return;
        }
        var debuggerContext = debugProcess.getDebuggerContext();
        var managerThread = debugProcess.getManagerThread();
        if (managerThread == null) {
            onError.accept("Debugger manager thread non disponibile");
            return;
        }

        managerThread.schedule(new DebuggerContextCommandImpl(debuggerContext, frame.getStackFrameProxy().threadProxy()) {
            @Override
            public @NotNull Priority getPriority() {
                return Priority.NORMAL;
            }

            @Override
            public void threadAction(@NotNull SuspendContextImpl suspendContext) {
                try {
                    Method method = frame.getDescriptor().getMethod();
                    if (method == null) {
                        onError.accept("Metodo JVM non disponibile nel frame");
                        return;
                    }
                    ObjectReference receiver = frame.getDescriptor().getThisObject();
                    var runtimeClass = receiver == null
                            ? method.declaringType().name()
                            : receiver.referenceType().name();
                    onSuccess.accept(new DebuggerSnapshot(
                            runtimeClass,
                            method.declaringType().name(),
                            method.name(),
                            method.signature(),
                            receiver != null,
                            callerLocation
                    ));
                }
                catch (RuntimeException exception) {
                    onError.accept("Frame non più valido: " + exception.getMessage());
                }
            }

            @Override
            protected void commandCancelled() {
                onError.accept("Acquisizione annullata: la sessione non è più sospesa");
            }
        });
    }

    private static DebuggerSnapshot.@Nullable SourceLocation toLocation(XSourcePosition position) {
        return position == null ? null : new DebuggerSnapshot.SourceLocation(position.getFile(), position.getLine());
    }
}
