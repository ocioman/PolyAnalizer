package io.github.ocioman.polyanalyzer;

import com.intellij.DynamicBundle;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.PropertyKey;

public final class PolyAnalyzerBundle extends DynamicBundle {
    public static final PolyAnalyzerBundle INSTANCE = new PolyAnalyzerBundle();
    private static final @NonNls String BUNDLE = "messages.PolyAnalyzerBundle";

    private PolyAnalyzerBundle() {
        super(BUNDLE);
    }

    public static @Nls String message(
            @PropertyKey(resourceBundle = BUNDLE) String key,
            Object... parameters
    ) {
        return INSTANCE.getMessage(key, parameters);
    }
}
