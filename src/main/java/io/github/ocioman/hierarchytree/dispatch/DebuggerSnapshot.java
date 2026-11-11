package io.github.ocioman.hierarchytree.dispatch;

import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

public record DebuggerSnapshot(
        @NotNull String runtimeClassName,
        @NotNull String declaringClassName,
        @NotNull String methodName,
        @NotNull String jvmSignature,
        boolean receiverPresent,
        @Nullable SourceLocation callerLocation
) {
    public DebuggerSnapshot {
        Objects.requireNonNull(runtimeClassName);
        Objects.requireNonNull(declaringClassName);
        Objects.requireNonNull(methodName);
        Objects.requireNonNull(jvmSignature);
    }

    public record SourceLocation(@NotNull VirtualFile file, int line) {
        public SourceLocation {
            Objects.requireNonNull(file);
            if (line < 0) {
                throw new IllegalArgumentException("Il numero di riga deve essere non negativo");
            }
        }
    }
}
