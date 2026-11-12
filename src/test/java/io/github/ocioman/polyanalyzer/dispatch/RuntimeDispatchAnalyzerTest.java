package io.github.ocioman.polyanalyzer.dispatch;

import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiFile;
import io.github.ocioman.polyanalyzer.JavaAnalyzerTestCase;
import io.github.ocioman.polyanalyzer.model.AnalysisNode;

import java.util.Arrays;
import java.util.function.Predicate;

public final class RuntimeDispatchAnalyzerTest extends JavaAnalyzerTestCase {
    private final RuntimeDispatchAnalyzer analyzer = new RuntimeDispatchAnalyzer();

    public void testShowsPathToInheritedImplementation() {
        myFixture.addClass("package demo; public class Animal { public void speak() {} }");
        myFixture.addClass("package demo; public class Dog extends Animal {}");
        myFixture.addClass("package demo; public class Puppy extends Dog {}");
        var snapshot = new DebuggerSnapshot("demo.Puppy", "demo.Animal", "speak", "()V", true, null);

        AnalysisNode result = analyzer.analyze(getProject(), snapshot);

        assertNotNull(descendant(result, node -> node.label().equals("demo.Puppy")
                && node.detail().contains("no override")));
        assertNotNull(descendant(result, node -> node.label().equals("demo.Animal")
                && node.detail().contains("implementation executed")));
    }

    public void testShowsObservedOverride() {
        myFixture.addClass("package demo; public class Animal { public void speak() {} }");
        myFixture.addClass("package demo; public class Dog extends Animal { public void speak() {} }");
        var snapshot = new DebuggerSnapshot("demo.Dog", "demo.Dog", "speak", "()V", true, null);

        AnalysisNode result = analyzer.analyze(getProject(), snapshot);

        assertNotNull(descendant(result, node -> node.label().equals("demo.Dog")
                && node.detail().contains("implementation executed")));
    }

    public void testFindsDefaultInterfaceImplementation() {
        myFixture.addClass("package demo; public interface Speaker { default void speak() {} }");
        myFixture.addClass("package demo; public class Dog implements Speaker {}");
        var snapshot = new DebuggerSnapshot("demo.Dog", "demo.Speaker", "speak", "()V", true, null);

        AnalysisNode result = analyzer.analyze(getProject(), snapshot);

        assertNotNull(descendant(result, node -> node.label().equals("demo.Speaker")
                && node.detail().contains("default implementation")));
    }

    public void testReconstructsCallerStaticDispatch() {
        myFixture.addClass("package demo; public class Animal { public void speak() {} }");
        myFixture.addClass("package demo; public class Dog extends Animal {}");
        PsiFile caller = myFixture.configureByText("Main.java", """
                package demo;
                class Main {
                    void call(Animal receiver) {
                        receiver.speak();
                    }
                }
                """);
        var location = new DebuggerSnapshot.SourceLocation(caller.getVirtualFile(), 3);
        var snapshot = new DebuggerSnapshot("demo.Dog", "demo.Animal", "speak", "()V", true, location);

        AnalysisNode result = analyzer.analyze(getProject(), snapshot);

        assertNotNull(descendant(result, node -> node.label().startsWith("Selected signature: speak()")));
    }

    public void testUsesJvmSignatureToSelectOverloadedMethod() {
        PsiClass animal = myFixture.addClass("""
                package demo;
                public class Animal {
                    public void speak() {}
                    public void speak(String prefix) {}
                }
                """);
        assertEquals(2, animal.getMethods().length);
        var overloaded = Arrays.stream(animal.getMethods())
                .filter(method -> method.getParameterList().getParametersCount() == 1)
                .findFirst()
                .orElseThrow();
        assertEquals("(Ljava/lang/String;)V", RuntimeDispatchAnalyzer.jvmSignature(overloaded));
        var snapshot = new DebuggerSnapshot(
                "demo.Animal",
                "demo.Animal",
                "speak",
                "(Ljava/lang/String;)V",
                true,
                null
        );

        AnalysisNode result = analyzer.analyze(getProject(), snapshot);

        assertNotNull(result.toString(), descendant(result, node -> node.label().equals("Observed implementation: speak(String)")));
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
