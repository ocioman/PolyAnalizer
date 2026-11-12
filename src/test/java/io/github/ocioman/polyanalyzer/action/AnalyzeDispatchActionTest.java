package io.github.ocioman.polyanalyzer.action;

import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.TestActionEvent;
import io.github.ocioman.polyanalyzer.JavaAnalyzerTestCase;

public final class AnalyzeDispatchActionTest extends JavaAnalyzerTestCase {
    public void testActionIsEnabledAtMethodCall() {
        configureCall();
        var event = testEvent(null);
        var action = new AnalyzeDispatchAction();

        action.update(event);

        assertTrue(event.getPresentation().isEnabled());
        assertTrue(event.getPresentation().isVisible());
    }

    public void testCaretTakesPrecedenceOverDirectElement() {
        configureCall();
        var containingMethod = PsiTreeUtil.getParentOfType(
                myFixture.getFile().findElementAt(myFixture.getCaretOffset()),
                com.intellij.psi.PsiMethod.class
        );
        var event = testEvent(containingMethod);
        var action = new AnalyzeDispatchAction();

        action.update(event);

        assertNotSame(containingMethod, ActionTarget.elementAtCaret(event));
        assertTrue(event.getPresentation().isEnabled());
        assertTrue(event.getPresentation().isVisible());
    }

    private void configureCall() {
        myFixture.configureByText("Main.java", """
                class Main {
                    void call() {
                        cho<caret>ose("text");
                    }

                    void choose(Object value) {}
                    void choose(String value) {}
                }
                """);
    }

    private com.intellij.openapi.actionSystem.AnActionEvent testEvent(PsiElement directElement) {
        DataContext context = dataId -> {
            if (CommonDataKeys.PSI_FILE.getName().equals(dataId)) {
                return myFixture.getFile();
            }
            if (CommonDataKeys.EDITOR.getName().equals(dataId)) {
                return myFixture.getEditor();
            }
            if (CommonDataKeys.PSI_ELEMENT.getName().equals(dataId)) {
                return directElement;
            }
            return null;
        };
        return TestActionEvent.createTestEvent(context);
    }
}
