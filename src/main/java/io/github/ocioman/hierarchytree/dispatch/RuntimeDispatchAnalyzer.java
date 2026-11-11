package io.github.ocioman.hierarchytree.dispatch;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.JavaRecursiveElementVisitor;
import com.intellij.psi.PsiArrayType;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiPrimitiveType;
import com.intellij.psi.PsiSubstitutor;
import com.intellij.psi.PsiSuperExpression;
import com.intellij.psi.PsiType;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.SmartPsiElementPointer;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.MethodSignatureUtil;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;
import com.intellij.psi.util.TypeConversionUtil;
import io.github.ocioman.hierarchytree.model.AnalysisNode;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class RuntimeDispatchAnalyzer {
    private final StaticDispatchAnalyzer staticAnalyzer = new StaticDispatchAnalyzer();

    public @NotNull AnalysisNode analyze(@NotNull Project project, @NotNull DebuggerSnapshot snapshot) {
        PsiClass runtimeClass = findClass(project, snapshot.runtimeClassName());
        PsiClass declaringClass = findClass(project, snapshot.declaringClassName());
        PsiMethod observedMethod = findObservedMethod(declaringClass, snapshot);
        if (observedMethod == null && runtimeClass != null) {
            observedMethod = findHierarchyMethod(runtimeClass, snapshot);
        }

        var sections = new ArrayList<AnalysisNode>();
        sections.add(AnalysisNode.leaf(
                AnalysisNode.Kind.INFO,
                "Actual receiver: " + snapshot.runtimeClassName(),
                snapshot.receiverPresent() ? "this object observed in frame" : "no this: static method",
                runtimeClass == null ? null : pointer(runtimeClass)
        ));

        if (observedMethod == null) {
            sections.add(AnalysisNode.leaf(
                    AnalysisNode.Kind.WARNING,
                    "JVM method not mapped to PSI",
                    snapshot.declaringClassName() + "." + snapshot.methodName() + snapshot.jvmSignature(),
                    null
            ));
        }
        else {
            sections.add(AnalysisNode.leaf(
                    AnalysisNode.Kind.METHOD,
                    "Observed implementation: " + presentation(observedMethod),
                    snapshot.declaringClassName(),
                    pointer(observedMethod)
            ));
        }

        var call = findCallerCall(project, snapshot, observedMethod);
        sections.add(callerSection(snapshot, call));

        if (runtimeClass == null || observedMethod == null) {
            sections.add(AnalysisNode.leaf(
                    AnalysisNode.Kind.WARNING,
                    "Lookup unavailable",
                    "both runtime type and declaring method are required in PSI",
                    null
            ));
        }
        else {
            var lookup = lookupPath(runtimeClass, observedMethod.getContainingClass(), observedMethod, new HashSet<>());
            sections.add(lookup == null
                    ? AnalysisNode.leaf(
                            AnalysisNode.Kind.WARNING,
                            "Lookup path not found",
                            snapshot.runtimeClassName() + " → " + snapshot.declaringClassName(),
                            null
                    )
                    : AnalysisNode.node(
                            AnalysisNode.Kind.INFO,
                            "Lookup path",
                            "from dynamic type to observed implementation",
                            List.of(lookup),
                            null
                    ));
            sections.add(dispatchRule(observedMethod, call));
        }

        return AnalysisNode.node(
                observedMethod == null ? AnalysisNode.Kind.WARNING : AnalysisNode.Kind.METHOD,
                snapshot.methodName() + snapshot.jvmSignature(),
                "runtime · " + snapshot.runtimeClassName(),
                sections,
                observedMethod == null ? null : pointer(observedMethod)
        );
    }

    private @NotNull AnalysisNode callerSection(
            @NotNull DebuggerSnapshot snapshot,
            @Nullable PsiMethodCallExpression call
    ) {
        if (call != null) {
            return AnalysisNode.node(
                    AnalysisNode.Kind.INFO,
                    "Call site in caller frame",
                    "static selection reconstructed from source",
                    List.of(staticAnalyzer.analyze(call)),
                    pointer(call)
            );
        }
        return AnalysisNode.leaf(
                AnalysisNode.Kind.WARNING,
                "Call site unavailable",
                snapshot.callerLocation() == null
                        ? "caller frame absent or has no source position"
                        : "no compatible call found in the caller line",
                null
        );
    }

    private static @NotNull AnalysisNode dispatchRule(
            @NotNull PsiMethod observed,
            @Nullable PsiMethodCallExpression call
    ) {
        var detail = "first concrete implementation reached from the dynamic type";
        if (observed.hasModifierProperty(PsiModifier.STATIC)) {
            detail = "static method: dynamic type does not participate in selection";
        }
        else if (observed.hasModifierProperty(PsiModifier.PRIVATE)) {
            detail = "private method: not overridable";
        }
        else if (observed.hasModifierProperty(PsiModifier.FINAL)) {
            detail = "final method: not overridable";
        }
        else if (observed.isConstructor()) {
            detail = "constructor: not subject to overriding";
        }
        else if (call != null && call.getMethodExpression().getQualifierExpression() instanceof PsiSuperExpression) {
            detail = "super call: the frame confirms the subtype override bypass";
        }
        return AnalysisNode.leaf(AnalysisNode.Kind.INFO, "Dispatch rule", detail, pointer(observed));
    }

    private static @Nullable AnalysisNode lookupPath(
            @NotNull PsiClass current,
            @Nullable PsiClass target,
            @NotNull PsiMethod observed,
            @NotNull Set<PsiClass> visited
    ) {
        if (target == null || !visited.add(current)) {
            return null;
        }

        boolean isTarget = current.equals(target);
        var children = new ArrayList<AnalysisNode>();
        if (!isTarget) {
            for (PsiClass supertype : directSupertypes(current)) {
                var child = lookupPath(supertype, target, observed, visited);
                if (child != null) {
                    children.add(child);
                }
            }
            if (children.isEmpty()) {
                return null;
            }
        }

        boolean declaresSignature = declaresMethod(current, observed);
        var detail = isTarget ? "implementation executed, confirmed by frame"
                : declaresSignature ? "override present along the path"
                : "no override: searching supertype";
        return AnalysisNode.node(
                current.isInterface() ? AnalysisNode.Kind.INTERFACE_TYPE : AnalysisNode.Kind.CLASS_TYPE,
                qualifiedName(current),
                detail + (current.isInterface() && isTarget ? " · default implementation" : ""),
                children,
                pointer(current)
        );
    }

    private static @NotNull List<PsiClass> directSupertypes(@NotNull PsiClass psiClass) {
        var result = new ArrayList<PsiClass>();
        PsiClass superClass = psiClass.getSuperClass();
        if (superClass != null) {
            result.add(superClass);
        }
        result.addAll(Arrays.asList(psiClass.getInterfaces()));
        return result;
    }

    private static boolean declaresMethod(@NotNull PsiClass psiClass, @NotNull PsiMethod observed) {
        var expectedName = jvmMethodName(observed);
        var expectedSignature = jvmSignature(observed);
        var methods = new ArrayList<>(Arrays.asList(psiClass.getMethods()));
        methods.addAll(Arrays.asList(psiClass.getConstructors()));
        return methods.stream().anyMatch(method -> jvmMethodName(method).equals(expectedName)
                && jvmSignature(method).equals(expectedSignature));
    }

    private static @Nullable PsiMethod findObservedMethod(
            @Nullable PsiClass declaringClass,
            @NotNull DebuggerSnapshot snapshot
    ) {
        if (declaringClass == null) {
            return null;
        }
        var methods = new ArrayList<>(Arrays.asList(declaringClass.getMethods()));
        methods.addAll(Arrays.asList(declaringClass.getConstructors()));
        return methods.stream()
                .filter(method -> jvmMethodName(method).equals(snapshot.methodName()))
                .filter(method -> jvmSignature(method).equals(snapshot.jvmSignature()))
                .findFirst()
                .orElse(null);
    }

    private static @Nullable PsiMethod findHierarchyMethod(
            @NotNull PsiClass runtimeClass,
            @NotNull DebuggerSnapshot snapshot
    ) {
        if ("<init>".equals(snapshot.methodName())) {
            return null;
        }
        return Arrays.stream(runtimeClass.findMethodsByName(snapshot.methodName(), true))
                .filter(method -> jvmSignature(method).equals(snapshot.jvmSignature()))
                .filter(method -> qualifiedName(method.getContainingClass()).equals(snapshot.declaringClassName()))
                .findFirst()
                .orElse(null);
    }

    private static @Nullable PsiMethodCallExpression findCallerCall(
            @NotNull Project project,
            @NotNull DebuggerSnapshot snapshot,
            @Nullable PsiMethod observed
    ) {
        if (observed == null || snapshot.callerLocation() == null) {
            return null;
        }
        var location = snapshot.callerLocation();
        PsiFile file = com.intellij.psi.PsiManager.getInstance(project).findFile(location.file());
        Document document = FileDocumentManager.getInstance().getDocument(location.file());
        if (!(file instanceof com.intellij.psi.PsiJavaFile) || document == null || location.line() >= document.getLineCount()) {
            return null;
        }

        int start = document.getLineStartOffset(location.line());
        int end = document.getLineEndOffset(location.line());
        PsiElement element = file.findElementAt(start);
        PsiElement scope = PsiTreeUtil.getParentOfType(element, PsiMethod.class);
        if (scope == null) {
            scope = file;
        }
        var calls = new ArrayList<PsiMethodCallExpression>();
        int finalStart = start;
        int finalEnd = end;
        scope.accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitMethodCallExpression(@NotNull PsiMethodCallExpression call) {
                var range = call.getTextRange();
                if (range != null && range.intersects(finalStart, finalEnd)) {
                    calls.add(call);
                }
                super.visitMethodCallExpression(call);
            }
        });

        return calls.stream()
                .filter(call -> {
                    PsiMethod resolved = call.resolveMethod();
                    return resolved != null && methodsCompatible(observed, resolved);
                })
                .findFirst()
                .orElse(null);
    }

    private static boolean methodsCompatible(@NotNull PsiMethod observed, @NotNull PsiMethod resolved) {
        if (observed.equals(resolved)) {
            return true;
        }
        if (Arrays.asList(observed.findDeepestSuperMethods()).contains(resolved)) {
            return true;
        }
        return MethodSignatureUtil.isSubsignature(
                observed.getSignature(PsiSubstitutor.EMPTY),
                resolved.getSignature(PsiSubstitutor.EMPTY)
        );
    }

    private static @Nullable PsiClass findClass(@NotNull Project project, @NotNull String className) {
        var facade = JavaPsiFacade.getInstance(project);
        var scope = GlobalSearchScope.allScope(project);
        PsiClass result = facade.findClass(className, scope);
        if (result == null && className.indexOf('$') >= 0) {
            result = facade.findClass(className.replace('$', '.'), scope);
        }
        return result;
    }

    private static @NotNull String presentation(@NotNull PsiMethod method) {
        var parameters = Arrays.stream(method.getParameterList().getParameters())
                .map(parameter -> parameter.getType().getPresentableText())
                .toList();
        return method.getName() + "(" + String.join(", ", parameters) + ")";
    }

    private static @NotNull String jvmMethodName(@NotNull PsiMethod method) {
        return method.isConstructor() ? "<init>" : method.getName();
    }

    static @NotNull String jvmSignature(@NotNull PsiMethod method) {
        var descriptor = new StringBuilder("(");
        for (var parameter : method.getParameterList().getParameters()) {
            descriptor.append(jvmDescriptor(parameter.getType()));
        }
        descriptor.append(')');
        PsiType returnType = method.getReturnType();
        descriptor.append(returnType == null ? "V" : jvmDescriptor(returnType));
        return descriptor.toString();
    }

    private static @NotNull String jvmDescriptor(@NotNull PsiType type) {
        PsiType erased = TypeConversionUtil.erasure(type);
        if (erased instanceof PsiArrayType arrayType) {
            return "[" + jvmDescriptor(arrayType.getComponentType());
        }
        if (erased instanceof PsiPrimitiveType) {
            return switch (erased.getCanonicalText()) {
                case "void" -> "V";
                case "boolean" -> "Z";
                case "byte" -> "B";
                case "char" -> "C";
                case "short" -> "S";
                case "int" -> "I";
                case "long" -> "J";
                case "float" -> "F";
                case "double" -> "D";
                default -> throw new IllegalArgumentException("Unknown JVM primitive type: " + erased.getCanonicalText());
            };
        }
        if (erased instanceof PsiClassType classType) {
            PsiClass resolved = classType.resolve();
            var className = resolved == null ? classType.getCanonicalText() : binaryName(resolved);
            return "L" + className.replace('.', '/') + ";";
        }
        return "L" + erased.getCanonicalText().replace('.', '/') + ";";
    }

    private static @NotNull String binaryName(@NotNull PsiClass psiClass) {
        var nesting = new ArrayList<String>();
        for (PsiClass current = psiClass; current != null; current = current.getContainingClass()) {
            var name = current.getName();
            if (name == null) {
                return qualifiedName(psiClass);
            }
            nesting.addFirst(name);
        }
        var packageName = PsiUtil.getPackageName(psiClass);
        var binaryName = String.join("$", nesting);
        return packageName == null || packageName.isEmpty() ? binaryName : packageName + "." + binaryName;
    }

    private static @NotNull String qualifiedName(@Nullable PsiClass psiClass) {
        if (psiClass == null) {
            return "class unavailable";
        }
        var name = psiClass.getQualifiedName();
        return name != null ? name : psiClass.getName() == null ? psiClass.getText() : psiClass.getName();
    }

    private static @NotNull SmartPsiElementPointer<? extends PsiElement> pointer(@NotNull PsiElement element) {
        return SmartPointerManager.getInstance(element.getProject()).createSmartPsiElementPointer(element);
    }
}
