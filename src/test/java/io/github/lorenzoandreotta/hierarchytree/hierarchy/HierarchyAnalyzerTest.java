package io.github.lorenzoandreotta.hierarchytree.hierarchy;

import com.intellij.psi.PsiClass;
import io.github.lorenzoandreotta.hierarchytree.JavaAnalyzerTestCase;
import io.github.lorenzoandreotta.hierarchytree.model.AnalysisNode;

import java.util.function.Predicate;

public final class HierarchyAnalyzerTest extends JavaAnalyzerTestCase {
    private final HierarchyAnalyzer analyzer = new HierarchyAnalyzer();

    public void testBuildsClassAndInterfaceRelations() {
        PsiClass animal = myFixture.addClass("package demo; public class Animal {}");
        myFixture.addClass("package demo; public interface Pet {}");
        PsiClass dog = myFixture.addClass("package demo; public class Dog extends Animal implements Pet {}");
        myFixture.addClass("package demo; public class Puppy extends Dog {}");

        AnalysisNode animalResult = analyzer.analyze(animal);
        AnalysisNode subtypes = child(animalResult, "Sottotipi");
        assertNotNull(descendant(subtypes, "demo.Dog"));
        assertNotNull(descendant(subtypes, "demo.Puppy"));

        AnalysisNode dogResult = analyzer.analyze(dog);
        AnalysisNode supertypes = child(dogResult, "Supertipi");
        AnalysisNode animalNode = descendant(supertypes, "Animal");
        AnalysisNode petNode = descendant(supertypes, "Pet");
        assertNotNull(animalNode);
        assertNotNull(petNode);
        assertTrue(animalNode.detail().startsWith("extends ·"));
        assertTrue(petNode.detail().startsWith("implements ·"));
    }

    public void testFindsExtendsAndSuperWildcards() {
        PsiClass dog = myFixture.addClass("package demo; public class Dog {}");
        myFixture.configureByText("Usage.java", """
                package demo;
                import java.util.List;
                class Usage {
                    List<? extends Dog> producers;
                    List<? super Dog> consumers;
                }
                """);

        AnalysisNode result = analyzer.analyze(dog);
        AnalysisNode wildcards = child(result, "Wildcard");

        assertNotNull(descendant(wildcards, node -> node.kind() == AnalysisNode.Kind.WILDCARD_EXTENDS));
        assertNotNull(descendant(wildcards, node -> node.kind() == AnalysisNode.Kind.WILDCARD_SUPER));
    }

    public void testRepresentsInterfaceDiamondWithoutInfiniteRecursion() {
        myFixture.addClass("package demo; public interface Root {}");
        myFixture.addClass("package demo; public interface Left extends Root {}");
        myFixture.addClass("package demo; public interface Right extends Root {}");
        PsiClass implementation = myFixture.addClass(
                "package demo; public class Implementation implements Left, Right {}"
        );

        AnalysisNode result = analyzer.analyze(implementation);
        AnalysisNode supertypes = child(result, "Supertipi");

        assertEquals(2, count(supertypes, "Root"));
    }

    private static AnalysisNode child(AnalysisNode node, String label) {
        return node.children().stream()
                .filter(candidate -> candidate.label().equals(label))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Nodo assente: " + label));
    }

    private static AnalysisNode descendant(AnalysisNode node, String labelFragment) {
        return descendant(node, candidate -> candidate.label().contains(labelFragment));
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

    private static int count(AnalysisNode node, String labelFragment) {
        int own = node.label().contains(labelFragment) ? 1 : 0;
        return own + node.children().stream().mapToInt(child -> count(child, labelFragment)).sum();
    }
}
