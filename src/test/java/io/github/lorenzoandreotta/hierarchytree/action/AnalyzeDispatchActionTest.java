package io.github.lorenzoandreotta.hierarchytree.action;

import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.testFramework.TestActionEvent;
import io.github.lorenzoandreotta.hierarchytree.JavaAnalyzerTestCase;

public final class AnalyzeDispatchActionTest extends JavaAnalyzerTestCase {
    public void testActionIsEnabledAtMethodCall() {
        myFixture.configureByText("Main.java", """
                class Main {
                    void call() {
                        cho<caret>ose("text");
                    }

                    void choose(Object value) {}
                    void choose(String value) {}
                }
                """);
        DataContext context = dataId -> {
            if (CommonDataKeys.PSI_FILE.getName().equals(dataId)) {
                return myFixture.getFile();
            }
            if (CommonDataKeys.EDITOR.getName().equals(dataId)) {
                return myFixture.getEditor();
            }
            return null;
        };
        var event = TestActionEvent.createTestEvent(context);
        var action = new AnalyzeDispatchAction();

        action.update(event);

        assertTrue(event.getPresentation().isEnabled());
        assertTrue(event.getPresentation().isVisible());
    }
}
