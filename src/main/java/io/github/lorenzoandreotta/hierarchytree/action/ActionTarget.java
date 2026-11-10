package io.github.lorenzoandreotta.hierarchytree.action;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

final class ActionTarget {
    private ActionTarget() {
    }

    static @Nullable PsiElement elementAtCaret(@NotNull AnActionEvent event) {
        var direct = event.getData(CommonDataKeys.PSI_ELEMENT);
        if (direct != null) {
            return direct;
        }

        var editor = event.getData(CommonDataKeys.EDITOR);
        var file = event.getData(CommonDataKeys.PSI_FILE);
        return editor == null || file == null ? null : file.findElementAt(editor.getCaretModel().getOffset());
    }
}
