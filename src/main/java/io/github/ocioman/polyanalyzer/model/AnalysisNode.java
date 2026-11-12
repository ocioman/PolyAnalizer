package io.github.ocioman.polyanalyzer.model;

import com.intellij.psi.PsiElement;
import com.intellij.psi.SmartPsiElementPointer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

public record AnalysisNode(
        @NotNull Kind kind,
        @NotNull String label,
        @NotNull String detail,
        @NotNull List<AnalysisNode> children,
        @Nullable SmartPsiElementPointer<? extends PsiElement> navigationTarget
) {
    public AnalysisNode {
        Objects.requireNonNull(kind);
        Objects.requireNonNull(label);
        Objects.requireNonNull(detail);
        children = List.copyOf(children);
    }

    public static AnalysisNode node(
            @NotNull Kind kind,
            @NotNull String label,
            @NotNull String detail,
            @NotNull List<AnalysisNode> children,
            @Nullable SmartPsiElementPointer<? extends PsiElement> navigationTarget
    ) {
        return new AnalysisNode(kind, label, detail, children, navigationTarget);
    }

    public static AnalysisNode leaf(
            @NotNull Kind kind,
            @NotNull String label,
            @NotNull String detail,
            @Nullable SmartPsiElementPointer<? extends PsiElement> navigationTarget
    ) {
        return new AnalysisNode(kind, label, detail, List.of(), navigationTarget);
    }

    public @NotNull String presentableText() {
        return detail.isEmpty() ? label : label + " — " + detail;
    }

    public enum Kind {
        SELECTED_TYPE,
        SUPERTYPES,
        SUBTYPES,
        WILDCARDS,
        CLASS_TYPE,
        INTERFACE_TYPE,
        WILDCARD_EXTENDS,
        WILDCARD_SUPER,
        METHOD,
        CANDIDATE,
        ARGUMENT,
        INFO,
        WARNING
    }
}
