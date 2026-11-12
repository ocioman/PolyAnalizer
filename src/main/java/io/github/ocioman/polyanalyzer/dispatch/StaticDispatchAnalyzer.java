package io.github.ocioman.polyanalyzer.dispatch;

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
import io.github.ocioman.polyanalyzer.model.AnalysisNode;
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
                "Selected signature: " + signature(selectedMethod, selectedResult.getSubstitutor()),
                qualifiedName(selectedMethod.getContainingClass()),
                selectedMethodPath(call, selectedMethod),
                pointer(selectedMethod)
        );
        var candidateSection = AnalysisNode.node(
                AnalysisNode.Kind.INFO,
                "Candidates",
                "selection determined by the IntelliJ Java resolver",
                candidateNodes(call, candidates, selectedCandidate, selectedResult),
                null
        );
        var runtime = AnalysisNode.leaf(
                isDynamicallyDispatched(call, selectedMethod) ? AnalysisNode.Kind.INFO : AnalysisNode.Kind.WARNING,
                "Runtime dispatch",
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
                        "Ambiguous call",
                        "no single candidate is more specific than the others",
                        pointer(call)
                )
                : AnalysisNode.leaf(
                        AnalysisNode.Kind.WARNING,
                        "Unresolved method",
                        "the resolver found no applicable signature",
                        pointer(call)
                );
        return AnalysisNode.node(
                AnalysisNode.Kind.WARNING,
                call.getMethodExpression().getReferenceName(),
                "static selection unavailable",
                List.of(warning, AnalysisNode.node(
                        AnalysisNode.Kind.INFO,
                        "Candidates",
                        "results returned by the resolver",
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
            return List.of(AnalysisNode.leaf(AnalysisNode.Kind.INFO, "No candidates", "", null));
        }

        return candidates.stream().map(candidate -> {
            var arguments = argumentNodes(call, candidate);
            var children = new ArrayList<AnalysisNode>(arguments);
            if (selectedCandidate != null && candidate.equals(selectedCandidate)) {
                children.addAll(specificityNodes(selectedCandidate, candidates));
            }
            var status = new ArrayList<String>();
            if (selectedResult != null && selectedResult.getElement() == candidate.method()) {
                status.add("selected");
            }
            status.add(candidate.applicable() ? "applicable" : "not applicable");
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
            var actualText = actual == null ? "type unavailable" : actual.getPresentableText();
            var formalText = formal == null ? "type unavailable" : formal.getPresentableText();
            nodes.add(AnalysisNode.leaf(
                    AnalysisNode.Kind.ARGUMENT,
                    "Argument " + (index + 1) + ": " + actualText + " → " + formalText,
                    conversion(actual, formal),
                    pointer(arguments[index])
            ));
        }
        if (nodes.isEmpty()) {
            nodes.add(AnalysisNode.leaf(AnalysisNode.Kind.ARGUMENT, "No arguments", "zero-parameter method", null));
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
                    "Comparison with " + signature(other.method(), other.result().getSubstitutor()),
                    comparison.detail(),
                    pointer(other.method())
            ));
        }
        if (nodes.isEmpty()) {
            nodes.add(AnalysisNode.leaf(
                    AnalysisNode.Kind.INFO,
                    "No other applicable candidate",
                    "no specificity comparisons needed",
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
            return new SpecificityComparison(false, "different parameter count; varargs and arity determine selection");
        }

        var strictReasons = new ArrayList<String>();
        for (int index = 0; index < selectedParameters.length; index++) {
            PsiType selectedType = selected.result().getSubstitutor().substitute(selectedParameters[index].getType());
            PsiType otherType = other.result().getSubstitutor().substitute(otherParameters[index].getType());
            if (selectedType == null || otherType == null || !TypeConversionUtil.isAssignable(otherType, selectedType)) {
                return new SpecificityComparison(
                        false,
                        "the resolver confirms the selection, but formal types do not form a complete subtype chain"
                );
            }
            if (!TypeConversionUtil.isAssignable(selectedType, otherType) || !selectedType.equals(otherType)) {
                strictReasons.add("parameter " + (index + 1) + ": " + selectedType.getPresentableText()
                        + " is more specific than " + otherType.getPresentableText());
            }
        }
        if (strictReasons.isEmpty()) {
            return new SpecificityComparison(false, "formal types equivalent after generic substitution");
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
                    "Receiver path unavailable",
                    "unresolved static type",
                    null
            ));
        }

        var path = findPath(receiverClass, declaringClass, new HashSet<>());
        if (path == null) {
            return List.of(AnalysisNode.leaf(
                    AnalysisNode.Kind.WARNING,
                    "No path from receiver",
                    qualifiedName(receiverClass) + " → " + qualifiedName(declaringClass),
                    null
            ));
        }

        var nodes = new ArrayList<AnalysisNode>();
        for (int index = 0; index < path.size(); index++) {
            PsiClass type = path.get(index);
            var detail = index == 0 ? "static receiver type"
                    : index == path.size() - 1 ? "inherited signature selected here"
                    : "hierarchy traversal";
            if (path.size() == 1) {
                detail = "receiver and declaration match";
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
            return "conversion unavailable";
        }
        if (actual.equals(formal)) {
            return "direct assignment";
        }
        if (TypeConversionUtil.isPrimitiveWrapper(actual) && formal instanceof PsiPrimitiveType) {
            return "unboxing and assignment conversion";
        }
        if (actual instanceof PsiPrimitiveType && TypeConversionUtil.isPrimitiveWrapper(formal)) {
            return "boxing and assignment conversion";
        }
        if (TypeConversionUtil.isAssignable(formal, actual)) {
            if (actual instanceof PsiPrimitiveType && formal instanceof PsiPrimitiveType) {
                return "primitive conversion allowed";
            }
            return "actual type convertible to formal type";
        }
        return "type not convertible";
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
            return "static method: no dynamic dispatch";
        }
        if (method.hasModifierProperty(PsiModifier.PRIVATE)) {
            return "private method: not overridable";
        }
        if (method.hasModifierProperty(PsiModifier.FINAL)) {
            return "final method: not overridable";
        }
        if (call.getMethodExpression().getQualifierExpression() instanceof PsiSuperExpression) {
            return "super call: implementation selected without ordinary virtual dispatch";
        }
        return "requires the actual receiver type captured by the debugger";
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
            return "class unavailable";
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
