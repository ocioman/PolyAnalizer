package io.github.ocioman.hierarchytree.action;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

final class ActionTarget {
    private ActionTarget() {
    }

    static @Nullable PsiElement elementAtCaret(@NotNull AnActionEvent event) {
        var editor = event.getData(CommonDataKeys.EDITOR);
        var file = event.getData(CommonDataKeys.PSI_FILE);
        if (editor != null && file != null) {
            return file.findElementAt(editor.getCaretModel().getOffset());
        }
        return event.getData(CommonDataKeys.PSI_ELEMENT);
    }
}
