package io.github.lorenzoandreotta.hierarchytree;

import com.intellij.DynamicBundle;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.PropertyKey;

public final class HierarchyTreeBundle extends DynamicBundle {
    public static final HierarchyTreeBundle INSTANCE = new HierarchyTreeBundle();
    private static final @NonNls String BUNDLE = "messages.HierarchyTreeBundle";

    private HierarchyTreeBundle() {
        super(BUNDLE);
    }

    public static @Nls String message(
            @PropertyKey(resourceBundle = BUNDLE) String key,
            Object... parameters
    ) {
        return INSTANCE.getMessage(key, parameters);
    }
}
