package io.github.ocioman.hierarchytree.ui;

import com.intellij.openapi.wm.ToolWindow;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.ui.content.ContentManager;
import io.github.ocioman.hierarchytree.model.AnalysisNode;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public final class HierarchyTreeWindowTest extends BasePlatformTestCase {
    public void testShowInitializesContentBeforeDisplayingAnalysis() {
        var contents = new AtomicInteger();
        var contentManager = (ContentManager) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{ContentManager.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("addContent")) {
                        contents.incrementAndGet();
                    }
                    return null;
                }
        );
        var toolWindow = (ToolWindow) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{ToolWindow.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getContentManager")) {
                        return contentManager;
                    }
                    if (method.getName().equals("show") && args != null && args.length == 1 && args[0] instanceof Runnable runnable) {
                        runnable.run();
                    }
                    return null;
                }
        );
        var window = HierarchyTreeWindow.getInstance(getProject());
        var root = new AnalysisNode(AnalysisNode.Kind.INFO, "Result", "", List.of(), null);

        window.showInToolWindow(toolWindow, AnalysisView.HIERARCHY, root);

        assertEquals(1, contents.get());
    }
}
