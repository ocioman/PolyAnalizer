package io.github.lorenzoandreotta.hierarchytree.hierarchy;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypeElement;
import com.intellij.psi.PsiWildcardType;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.SmartPsiElementPointer;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.ProjectScope;
import com.intellij.psi.search.searches.ClassInheritorsSearch;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.PsiTreeUtil;
import io.github.lorenzoandreotta.hierarchytree.model.AnalysisNode;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class HierarchyAnalyzer {
    private static final Comparator<PsiClass> CLASS_ORDER = Comparator.comparing(
            HierarchyAnalyzer::qualifiedName,
            String.CASE_INSENSITIVE_ORDER
    );

    public @NotNull AnalysisNode analyze(@NotNull PsiClass selected) {
        if (!selected.isValid()) {
            throw new IllegalArgumentException("La classe selezionata non è più valida");
        }

        var supertypes = AnalysisNode.node(
                AnalysisNode.Kind.SUPERTYPES,
                "Supertipi",
                "classi e interfacce da cui il tipo selezionato eredita",
                supertypeChildren(selected, pathWith(selected)),
                null
        );
        var subtypes = subtypeSection(selected);
        var wildcards = wildcardSection(selected);
        return AnalysisNode.node(
                AnalysisNode.Kind.SELECTED_TYPE,
                qualifiedName(selected),
                "tipo selezionato",
                List.of(supertypes, subtypes, wildcards),
                pointer(selected)
        );
    }

    private @NotNull AnalysisNode subtypeSection(@NotNull PsiClass selected) {
        return AnalysisNode.node(
                AnalysisNode.Kind.SUBTYPES,
                "Sottotipi",
                "classi e interfacce del progetto",
                subtypeChildren(selected, pathWith(selected)),
                null
        );
    }

    private @NotNull List<AnalysisNode> supertypeChildren(
            @NotNull PsiClass current,
            @NotNull Set<PsiClass> path
    ) {
        ProgressManager.checkCanceled();
        var children = new ArrayList<AnalysisNode>();
        for (PsiClassType supertype : current.getSuperTypes()) {
            PsiClass resolved = supertype.resolve();
            if (resolved == null) {
                children.add(AnalysisNode.leaf(
                        AnalysisNode.Kind.WARNING,
                        supertype.getPresentableText(),
                        "supertipo non risolto",
                        null
                ));
                continue;
            }

            var relation = relation(current, resolved);
            var kind = resolved.isInterface() ? AnalysisNode.Kind.INTERFACE_TYPE : AnalysisNode.Kind.CLASS_TYPE;
            var nextPath = extend(path, resolved);
            var grandchildren = path.contains(resolved)
                    ? List.of(AnalysisNode.leaf(AnalysisNode.Kind.INFO, "Già mostrato", "ciclo interrotto", null))
                    : supertypeChildren(resolved, nextPath);
            children.add(AnalysisNode.node(
                    kind,
                    supertype.getPresentableText(),
                    relation + " · " + qualifiedName(resolved),
                    grandchildren,
                    pointer(resolved)
            ));
        }
        children.sort(Comparator.comparing(AnalysisNode::label, String.CASE_INSENSITIVE_ORDER));
        return children;
    }

    private @NotNull List<AnalysisNode> subtypeChildren(
            @NotNull PsiClass current,
            @NotNull Set<PsiClass> path
    ) {
        ProgressManager.checkCanceled();
        GlobalSearchScope scope = ProjectScope.getProjectScope(current.getProject());
        var directSubtypes = new ArrayList<>(ClassInheritorsSearch.search(
                current,
                scope,
                false,
                true,
                false
        ).findAll());
        directSubtypes.sort(CLASS_ORDER);

        var children = new ArrayList<AnalysisNode>();
        for (PsiClass subtype : directSubtypes) {
            var nextPath = extend(path, subtype);
            var grandchildren = path.contains(subtype)
                    ? List.of(AnalysisNode.leaf(AnalysisNode.Kind.INFO, "Già mostrato", "ciclo interrotto", null))
                    : subtypeChildren(subtype, nextPath);
            children.add(AnalysisNode.node(
                    subtype.isInterface() ? AnalysisNode.Kind.INTERFACE_TYPE : AnalysisNode.Kind.CLASS_TYPE,
                    qualifiedName(subtype),
                    relation(subtype, current),
                    grandchildren,
                    pointer(subtype)
            ));
        }
        return children;
    }

    private @NotNull AnalysisNode wildcardSection(@NotNull PsiClass selected) {
        var usages = new ArrayList<AnalysisNode>();
        GlobalSearchScope scope = ProjectScope.getProjectScope(selected.getProject());
        for (var reference : ReferencesSearch.search(selected, scope, false).findAll()) {
            ProgressManager.checkCanceled();
            var wildcard = enclosingWildcard(reference.getElement());
            if (wildcard == null) {
                continue;
            }

            PsiType bound = wildcard.getBound();
            if (!(bound instanceof PsiClassType classType) || !selected.equals(classType.resolve())) {
                continue;
            }

            var allowedTypes = wildcard.isExtends()
                    ? subtypeChildren(selected, pathWith(selected))
                    : supertypeChildren(selected, pathWith(selected));
            var allowedSection = AnalysisNode.node(
                    wildcard.isExtends() ? AnalysisNode.Kind.SUBTYPES : AnalysisNode.Kind.SUPERTYPES,
                    "Tipi ammessi",
                    wildcard.isExtends() ? "il bound e i suoi sottotipi" : "il bound e i suoi supertipi",
                    allowedTypes,
                    null
            );
            usages.add(AnalysisNode.node(
                    wildcard.isExtends() ? AnalysisNode.Kind.WILDCARD_EXTENDS : AnalysisNode.Kind.WILDCARD_SUPER,
                    wildcard.getPresentableText(),
                    sourceLocation(reference.getElement()),
                    List.of(allowedSection),
                    pointer(reference.getElement())
            ));
        }
        usages.sort(Comparator.comparing(AnalysisNode::detail, String.CASE_INSENSITIVE_ORDER));

        var children = usages.isEmpty()
                ? List.of(AnalysisNode.leaf(
                        AnalysisNode.Kind.INFO,
                        "Nessuna wildcard con questo bound",
                        "ambito: progetto",
                        null
                ))
                : usages;
        return AnalysisNode.node(
                AnalysisNode.Kind.WILDCARDS,
                "Wildcard",
                "? extends e ? super che usano il tipo selezionato come bound",
                children,
                null
        );
    }

    private static @Nullable PsiWildcardType enclosingWildcard(@NotNull PsiElement element) {
        var current = PsiTreeUtil.getParentOfType(element, PsiTypeElement.class);
        while (current != null) {
            if (current.getType() instanceof PsiWildcardType wildcard && wildcard.isBounded()) {
                return wildcard;
            }
            current = PsiTreeUtil.getParentOfType(current, PsiTypeElement.class);
        }
        return null;
    }

    private static @NotNull String relation(@NotNull PsiClass subtype, @NotNull PsiClass supertype) {
        if (subtype.isInterface() && supertype.isInterface()) {
            return "extends interfaccia";
        }
        if (supertype.isInterface()) {
            return "implements";
        }
        return "extends";
    }

    private static @NotNull String qualifiedName(@NotNull PsiClass psiClass) {
        var qualifiedName = psiClass.getQualifiedName();
        if (qualifiedName != null) {
            return qualifiedName;
        }
        var name = psiClass.getName();
        return name != null ? name : psiClass.getText();
    }

    private static @NotNull String sourceLocation(@NotNull PsiElement element) {
        PsiFile file = element.getContainingFile();
        if (file == null) {
            return "posizione sconosciuta";
        }
        Document document = file.getViewProvider().getDocument();
        int line = document == null ? -1 : document.getLineNumber(element.getTextOffset()) + 1;
        return line < 0 ? file.getName() : file.getName() + ":" + line;
    }

    private static @NotNull Set<PsiClass> pathWith(@NotNull PsiClass psiClass) {
        var path = new HashSet<PsiClass>();
        path.add(psiClass);
        return path;
    }

    private static @NotNull Set<PsiClass> extend(@NotNull Set<PsiClass> path, @NotNull PsiClass next) {
        var extended = new HashSet<>(path);
        extended.add(next);
        return extended;
    }

    private static @NotNull SmartPsiElementPointer<? extends PsiElement> pointer(@NotNull PsiElement element) {
        return SmartPointerManager.getInstance(element.getProject()).createSmartPsiElementPointer(element);
    }
}
