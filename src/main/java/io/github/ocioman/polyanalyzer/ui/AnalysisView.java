package io.github.ocioman.polyanalyzer.ui;

public enum AnalysisView {
    HIERARCHY(0),
    COMPILE_TIME(1),
    RUNTIME(2);

    private final int tabIndex;

    AnalysisView(int tabIndex) {
        this.tabIndex = tabIndex;
    }

    public int tabIndex() {
        return tabIndex;
    }
}
