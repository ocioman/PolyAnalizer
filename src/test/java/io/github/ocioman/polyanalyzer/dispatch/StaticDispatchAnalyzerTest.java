package io.github.ocioman.polyanalyzer.dispatch;

import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.util.PsiTreeUtil;
import io.github.ocioman.polyanalyzer.JavaAnalyzerTestCase;
import io.github.ocioman.polyanalyzer.model.AnalysisNode;

import java.util.function.Predicate;

public final class StaticDispatchAnalyzerTest extends JavaAnalyzerTestCase {
    private final StaticDispatchAnalyzer analyzer = new StaticDispatchAnalyzer();

    public void testShowsInheritedSignaturePath() {
        myFixture.addClass("package demo; public class Animal { public void speak() {} }");
        myFixture.addClass("package demo; public class Dog extends Animal {}");
        PsiFile file = myFixture.configureByText("Main.java", """
                package demo;
                class Main {
                    void call(Dog receiver) {
                        receiver.spe<caret>ak();
                    }
                }
                """);

        AnalysisNode result = analyzer.analyze(callAtCaret(file));

        assertNotNull(descendant(result, node -> node.label().startsWith("Selected signature: speak()")));
        assertNotNull(descendant(result, node -> node.label().equals("demo.Animal")
                && node.detail().contains("inherited signature")));
    }

    public void testExplainsMostSpecificParameter() {
        PsiFile file = myFixture.configureByText("Main.java", """
                class Main {
                    void choose(Object value) {}
                    void choose(String value) {}
                    void call() {
                        cho<caret>ose("text");
                    }
                }
                """);

        AnalysisNode result = analyzer.analyze(callAtCaret(file));

        assertNotNull(descendant(result, node -> node.label().startsWith("Selected signature: choose(String)")));
        assertNotNull(result.toString(), descendant(result, node -> node.detail().contains("String is more specific than Object")));
    }

    public void testWarnsForAmbiguousCall() {
        myFixture.addClass("public interface First {}");
        myFixture.addClass("public interface Second {}");
        myFixture.addClass("public class Both implements First, Second {}");
        PsiFile file = myFixture.configureByText("Main.java", """
                class Main {
                    void choose(First value) {}
                    void choose(Second value) {}
                    void call() {
                        cho<caret>ose(new Both());
                    }
                }
                """);

        AnalysisNode result = analyzer.analyze(callAtCaret(file));

        assertNotNull(descendant(result, node -> node.kind() == AnalysisNode.Kind.WARNING
                && node.label().equals("Ambiguous call")));
    }

    public void testMarksVariableArityInvocation() {
        PsiFile file = myFixture.configureByText("Main.java", """
                class Main {
                    void format(Object... values) {}
                    void call() {
                        form<caret>at("first", "second");
                    }
                }
                """);

        AnalysisNode result = analyzer.analyze(callAtCaret(file));

        assertNotNull(result.toString(), descendant(result, node -> node.kind() == AnalysisNode.Kind.CANDIDATE
                && node.detail().contains("varargs")));
        assertNotNull(descendant(result, node -> node.label().equals("Argument 2: String → Object")));
    }

    private PsiMethodCallExpression callAtCaret(PsiFile file) {
        return PsiTreeUtil.getParentOfType(
                file.findElementAt(myFixture.getCaretOffset()),
                PsiMethodCallExpression.class
        );
    }

    private static AnalysisNode descendant(AnalysisNode node, Predicate<AnalysisNode> predicate) {
        if (predicate.test(node)) {
            return node;
        }
        for (AnalysisNode child : node.children()) {
            AnalysisNode match = descendant(child, predicate);
            if (match != null) {
                return match;
            }
        }
        return null;
    }
}
