package io.github.lorenzoandreotta.hierarchytree.dispatch;

import com.intellij.psi.JavaResolveResult;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiEllipsisType;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiPrimitiveType;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiSubstitutor;
import com.intellij.psi.PsiSuperExpression;
import com.intellij.psi.PsiType;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.SmartPsiElementPointer;
import com.intellij.psi.infos.MethodCandidateInfo;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.TypeConversionUtil;
import io.github.lorenzoandreotta.hierarchytree.model.AnalysisNode;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class StaticDispatchAnalyzer {
    public @NotNull AnalysisNode analyze(@NotNull PsiMethodCallExpression call) {
        var selectedResult = call.resolveMethodGenerics();
        PsiMethod selectedMethod = selectedResult.getElement() instanceof PsiMethod method ? method : null;
        var candidates = candidates(call);

        if (selectedMethod == null) {
            return unresolvedCall(call, candidates);
        }

        var selectedCandidate = candidates.stream()
                .filter(candidate -> candidate.method().equals(selectedMethod))
                .findFirst()
                .orElse(null);
        var selected = AnalysisNode.node(
                AnalysisNode.Kind.METHOD,
                "Firma selezionata: " + signature(selectedMethod, selectedResult.getSubstitutor()),
                qualifiedName(selectedMethod.getContainingClass()),
                selectedMethodPath(call, selectedMethod),
                pointer(selectedMethod)
        );
        var candidateSection = AnalysisNode.node(
                AnalysisNode.Kind.INFO,
                "Candidati",
                "selezione determinata dal resolver Java di IntelliJ",
                candidateNodes(call, candidates, selectedCandidate, selectedResult),
                null
        );
        var runtime = AnalysisNode.leaf(
                isDynamicallyDispatched(call, selectedMethod) ? AnalysisNode.Kind.INFO : AnalysisNode.Kind.WARNING,
                "Dispatch runtime",
                runtimeDetail(call, selectedMethod),
                null
        );
        return AnalysisNode.node(
                AnalysisNode.Kind.METHOD,
                call.getMethodExpression().getReferenceName(),
                "compile time · " + signature(selectedMethod, selectedResult.getSubstitutor()),
                List.of(selected, candidateSection, runtime),
                pointer(call)
        );
    }

    private @NotNull AnalysisNode unresolvedCall(
            @NotNull PsiMethodCallExpression call,
            @NotNull List<Candidate> candidates
    ) {
        var candidateNodes = candidateNodes(call, candidates, null, null);
        var warning = candidates.size() > 1
                ? AnalysisNode.leaf(
                        AnalysisNode.Kind.WARNING,
                        "Chiamata ambigua",
                        "nessun candidato unico è più specifico degli altri",
                        pointer(call)
                )
                : AnalysisNode.leaf(
                        AnalysisNode.Kind.WARNING,
                        "Metodo non risolto",
                        "il resolver non ha trovato una firma applicabile",
                        pointer(call)
                );
        return AnalysisNode.node(
                AnalysisNode.Kind.WARNING,
                call.getMethodExpression().getReferenceName(),
                "selezione statica non disponibile",
                List.of(warning, AnalysisNode.node(
                        AnalysisNode.Kind.INFO,
                        "Candidati",
                        "risultati restituiti dal resolver",
                        candidateNodes,
                        null
                )),
                pointer(call)
        );
    }

    private static @NotNull List<Candidate> candidates(@NotNull PsiMethodCallExpression call) {
        var results = new java.util.LinkedHashMap<PsiMethod, JavaResolveResult>();
        for (JavaResolveResult result : call.getMethodExpression().multiResolve(true)) {
            if (result.getElement() instanceof PsiMethod method) {
                results.put(method, result);
            }
        }

        PsiClass receiver = receiverClass(call, call.resolveMethod());
        var methodName = call.getMethodExpression().getReferenceName();
        if (receiver != null && methodName != null) {
            for (PsiMethod method : receiver.findMethodsByName(methodName, true)) {
                results.putIfAbsent(method, candidateInfo(call, method));
            }
        }

        return results.values().stream()
                .map(result -> candidate(call, result))
                .sorted(Comparator.comparing(candidate -> signature(candidate.method(), candidate.result().getSubstitutor())))
                .toList();
    }

    private static @NotNull MethodCandidateInfo candidateInfo(
            @NotNull PsiMethodCallExpression call,
            @NotNull PsiMethod method
    ) {
        var argumentTypes = Arrays.stream(call.getArgumentList().getExpressions())
                .map(PsiExpression::getType)
                .toArray(PsiType[]::new);
        return new MethodCandidateInfo(
                method,
                PsiSubstitutor.EMPTY,
                false,
                false,
                call.getArgumentList(),
                call,
                argumentTypes,
                null
        );
    }

    private static @NotNull Candidate candidate(
            @NotNull PsiMethodCallExpression call,
            @NotNull JavaResolveResult result
    ) {
        var method = (PsiMethod) result.getElement();
        var applicable = result instanceof MethodCandidateInfo methodCandidate
                ? methodCandidate.isApplicable()
                : result.isValidResult();
        var differentArity = call.getArgumentList().getExpressions().length
                != method.getParameterList().getParametersCount();
        var varargs = method.isVarArgs() && (differentArity
                || result instanceof MethodCandidateInfo methodCandidate && methodCandidate.isVarargs());
        return new Candidate(result, method, applicable, varargs);
    }

    private static @NotNull List<AnalysisNode> candidateNodes(
            @NotNull PsiMethodCallExpression call,
            @NotNull List<Candidate> candidates,
            @Nullable Candidate selectedCandidate,
            @Nullable JavaResolveResult selectedResult
    ) {
        if (candidates.isEmpty()) {
            return List.of(AnalysisNode.leaf(AnalysisNode.Kind.INFO, "Nessun candidato", "", null));
        }

        return candidates.stream().map(candidate -> {
            var arguments = argumentNodes(call, candidate);
            var children = new ArrayList<AnalysisNode>(arguments);
            if (selectedCandidate != null && candidate.equals(selectedCandidate)) {
                children.addAll(specificityNodes(selectedCandidate, candidates));
            }
            var status = new ArrayList<String>();
            if (selectedResult != null && selectedResult.getElement() == candidate.method()) {
                status.add("selezionato");
            }
            status.add(candidate.applicable() ? "applicabile" : "non applicabile");
            if (candidate.varargs()) {
                status.add("varargs");
            }
            return AnalysisNode.node(
                    AnalysisNode.Kind.CANDIDATE,
                    signature(candidate.method(), candidate.result().getSubstitutor()),
                    String.join(" · ", status) + " · " + qualifiedName(candidate.method().getContainingClass()),
                    children,
                    pointer(candidate.method())
            );
        }).toList();
    }

    private static @NotNull List<AnalysisNode> argumentNodes(
            @NotNull PsiMethodCallExpression call,
            @NotNull Candidate candidate
    ) {
        var arguments = call.getArgumentList().getExpressions();
        var parameters = candidate.method().getParameterList().getParameters();
        var nodes = new ArrayList<AnalysisNode>();
        for (int index = 0; index < arguments.length; index++) {
            PsiType actual = arguments[index].getType();
            PsiType formal = formalType(candidate, parameters, index);
            var actualText = actual == null ? "tipo non disponibile" : actual.getPresentableText();
            var formalText = formal == null ? "tipo non disponibile" : formal.getPresentableText();
            nodes.add(AnalysisNode.leaf(
                    AnalysisNode.Kind.ARGUMENT,
                    "Argomento " + (index + 1) + ": " + actualText + " → " + formalText,
                    conversion(actual, formal),
                    pointer(arguments[index])
            ));
        }
        if (nodes.isEmpty()) {
            nodes.add(AnalysisNode.leaf(AnalysisNode.Kind.ARGUMENT, "Nessun argomento", "metodo a zero parametri", null));
        }
        return nodes;
    }

    private static @NotNull List<AnalysisNode> specificityNodes(
            @NotNull Candidate selected,
            @NotNull List<Candidate> candidates
    ) {
        var nodes = new ArrayList<AnalysisNode>();
        for (Candidate other : candidates) {
            if (other.equals(selected) || !other.applicable()) {
                continue;
            }
            var comparison = compareSpecificity(selected, other);
            nodes.add(AnalysisNode.leaf(
                    comparison.moreSpecific() ? AnalysisNode.Kind.INFO : AnalysisNode.Kind.WARNING,
                    "Confronto con " + signature(other.method(), other.result().getSubstitutor()),
                    comparison.detail(),
                    pointer(other.method())
            ));
        }
        if (nodes.isEmpty()) {
            nodes.add(AnalysisNode.leaf(
                    AnalysisNode.Kind.INFO,
                    "Nessun altro candidato applicabile",
                    "non servono confronti di specificità",
                    null
            ));
        }
        return nodes;
    }

    private static @NotNull SpecificityComparison compareSpecificity(
            @NotNull Candidate selected,
            @NotNull Candidate other
    ) {
        var selectedParameters = selected.method().getParameterList().getParameters();
        var otherParameters = other.method().getParameterList().getParameters();
        if (selectedParameters.length != otherParameters.length) {
            return new SpecificityComparison(false, "numero di parametri diverso; varargs e arità determinano la scelta");
        }

        var strictReasons = new ArrayList<String>();
        for (int index = 0; index < selectedParameters.length; index++) {
            PsiType selectedType = selected.result().getSubstitutor().substitute(selectedParameters[index].getType());
            PsiType otherType = other.result().getSubstitutor().substitute(otherParameters[index].getType());
            if (selectedType == null || otherType == null || !TypeConversionUtil.isAssignable(otherType, selectedType)) {
                return new SpecificityComparison(
                        false,
                        "il resolver conferma la scelta, ma i tipi formali non formano una catena di sottotipi completa"
                );
            }
            if (!TypeConversionUtil.isAssignable(selectedType, otherType) || !selectedType.equals(otherType)) {
                strictReasons.add("parametro " + (index + 1) + ": " + selectedType.getPresentableText()
                        + " è più specifico di " + otherType.getPresentableText());
            }
        }
        if (strictReasons.isEmpty()) {
            return new SpecificityComparison(false, "tipi formali equivalenti dopo la sostituzione generica");
        }
        return new SpecificityComparison(true, String.join("; ", strictReasons));
    }

    private static @NotNull List<AnalysisNode> selectedMethodPath(
            @NotNull PsiMethodCallExpression call,
            @NotNull PsiMethod selected
    ) {
        PsiClass declaringClass = selected.getContainingClass();
        PsiClass receiverClass = receiverClass(call, selected);
        if (declaringClass == null || receiverClass == null) {
            return List.of(AnalysisNode.leaf(
                    AnalysisNode.Kind.WARNING,
                    "Percorso del receiver non disponibile",
                    "tipo statico non risolto",
                    null
            ));
        }

        var path = findPath(receiverClass, declaringClass, new HashSet<>());
        if (path == null) {
            return List.of(AnalysisNode.leaf(
                    AnalysisNode.Kind.WARNING,
                    "Nessun percorso dal receiver",
                    qualifiedName(receiverClass) + " → " + qualifiedName(declaringClass),
                    null
            ));
        }

        var nodes = new ArrayList<AnalysisNode>();
        for (int index = 0; index < path.size(); index++) {
            PsiClass type = path.get(index);
            var detail = index == 0 ? "tipo statico del receiver"
                    : index == path.size() - 1 ? "firma ereditata e selezionata qui"
                    : "attraversamento della gerarchia";
            if (path.size() == 1) {
                detail = "receiver e dichiarazione coincidono";
            }
            nodes.add(AnalysisNode.leaf(
                    type.isInterface() ? AnalysisNode.Kind.INTERFACE_TYPE : AnalysisNode.Kind.CLASS_TYPE,
                    qualifiedName(type),
                    detail,
                    pointer(type)
            ));
        }
        return nodes;
    }

    private static @Nullable List<PsiClass> findPath(
            @NotNull PsiClass current,
            @NotNull PsiClass target,
            @NotNull Set<PsiClass> visited
    ) {
        if (current.equals(target)) {
            return List.of(current);
        }
        if (!visited.add(current)) {
            return null;
        }
        for (PsiClassType supertype : current.getSuperTypes()) {
            PsiClass resolved = supertype.resolve();
            if (resolved == null) {
                continue;
            }
            var path = findPath(resolved, target, visited);
            if (path != null) {
                var result = new ArrayList<PsiClass>();
                result.add(current);
                result.addAll(path);
                return result;
            }
        }
        return null;
    }

    private static @Nullable PsiClass receiverClass(
            @NotNull PsiMethodCallExpression call,
            @Nullable PsiMethod selected
    ) {
        PsiExpression qualifier = call.getMethodExpression().getQualifierExpression();
        if (qualifier instanceof PsiReferenceExpression reference
                && reference.resolve() instanceof PsiClass referencedClass) {
            return referencedClass;
        }
        if (qualifier != null && qualifier.getType() instanceof PsiClassType classType) {
            return classType.resolve();
        }
        PsiClass enclosing = PsiTreeUtil.getParentOfType(call, PsiClass.class);
        return enclosing != null || selected == null ? enclosing : selected.getContainingClass();
    }

    private static @Nullable PsiType formalType(
            @NotNull Candidate candidate,
            @NotNull PsiParameter[] parameters,
            int argumentIndex
    ) {
        if (parameters.length == 0) {
            return null;
        }
        int parameterIndex = Math.min(argumentIndex, parameters.length - 1);
        PsiType formal = candidate.result().getSubstitutor().substitute(parameters[parameterIndex].getType());
        if (candidate.varargs() && argumentIndex >= parameters.length - 1 && formal instanceof PsiEllipsisType ellipsis) {
            return ellipsis.getComponentType();
        }
        return formal;
    }

    private static @NotNull String conversion(@Nullable PsiType actual, @Nullable PsiType formal) {
        if (actual == null || formal == null) {
            return "conversione non disponibile";
        }
        if (actual.equals(formal)) {
            return "assegnazione diretta";
        }
        if (TypeConversionUtil.isPrimitiveWrapper(actual) && formal instanceof PsiPrimitiveType) {
            return "unboxing e conversione per assegnazione";
        }
        if (actual instanceof PsiPrimitiveType && TypeConversionUtil.isPrimitiveWrapper(formal)) {
            return "boxing e conversione per assegnazione";
        }
        if (TypeConversionUtil.isAssignable(formal, actual)) {
            if (actual instanceof PsiPrimitiveType && formal instanceof PsiPrimitiveType) {
                return "conversione primitiva ammessa";
            }
            return "tipo effettivo convertibile nel tipo formale";
        }
        return "tipo non convertibile";
    }

    private static boolean isDynamicallyDispatched(
            @NotNull PsiMethodCallExpression call,
            @NotNull PsiMethod method
    ) {
        return !method.isConstructor()
                && !method.hasModifierProperty(PsiModifier.STATIC)
                && !method.hasModifierProperty(PsiModifier.PRIVATE)
                && !method.hasModifierProperty(PsiModifier.FINAL)
                && !(call.getMethodExpression().getQualifierExpression() instanceof PsiSuperExpression);
    }

    private static @NotNull String runtimeDetail(
            @NotNull PsiMethodCallExpression call,
            @NotNull PsiMethod method
    ) {
        if (method.hasModifierProperty(PsiModifier.STATIC)) {
            return "metodo statico: nessun dispatch dinamico";
        }
        if (method.hasModifierProperty(PsiModifier.PRIVATE)) {
            return "metodo privato: non sovrascrivibile";
        }
        if (method.hasModifierProperty(PsiModifier.FINAL)) {
            return "metodo final: non sovrascrivibile";
        }
        if (call.getMethodExpression().getQualifierExpression() instanceof PsiSuperExpression) {
            return "chiamata super: implementazione selezionata senza dispatch virtuale ordinario";
        }
        return "richiede il tipo reale del receiver acquisito dal debugger";
    }

    private static @NotNull String signature(@NotNull PsiMethod method, @NotNull PsiSubstitutor substitutor) {
        var parameters = Arrays.stream(method.getParameterList().getParameters())
                .map(parameter -> {
                    PsiType type = substitutor.substitute(parameter.getType());
                    return type == null ? parameter.getType().getPresentableText() : type.getPresentableText();
                })
                .toList();
        return method.getName() + "(" + String.join(", ", parameters) + ")";
    }

    private static @NotNull String qualifiedName(@Nullable PsiClass psiClass) {
        if (psiClass == null) {
            return "classe non disponibile";
        }
        var name = psiClass.getQualifiedName();
        return name != null ? name : Objects.requireNonNullElse(psiClass.getName(), psiClass.getText());
    }

    private static @NotNull SmartPsiElementPointer<? extends PsiElement> pointer(@NotNull PsiElement element) {
        return SmartPointerManager.getInstance(element.getProject()).createSmartPsiElementPointer(element);
    }

    private record Candidate(
            @NotNull JavaResolveResult result,
            @NotNull PsiMethod method,
            boolean applicable,
            boolean varargs
    ) {
    }

    private record SpecificityComparison(boolean moreSpecific, @NotNull String detail) {
    }
}
