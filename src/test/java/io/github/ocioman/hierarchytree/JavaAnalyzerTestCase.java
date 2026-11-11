package io.github.ocioman.hierarchytree;

import com.intellij.pom.java.LanguageLevel;
import com.intellij.testFramework.IdeaTestUtil;
import com.intellij.testFramework.builders.JavaModuleFixtureBuilder;
import com.intellij.testFramework.fixtures.JavaCodeInsightFixtureTestCase;

public abstract class JavaAnalyzerTestCase extends JavaCodeInsightFixtureTestCase {
    @Override
    protected void tuneFixture(JavaModuleFixtureBuilder<?> moduleBuilder) {
        moduleBuilder.addJdk(System.getProperty("java.home"));
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        IdeaTestUtil.setProjectLanguageLevel(getProject(), LanguageLevel.JDK_21);
    }
}
