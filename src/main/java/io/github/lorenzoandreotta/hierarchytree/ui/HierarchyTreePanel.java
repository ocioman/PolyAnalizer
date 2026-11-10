package io.github.lorenzoandreotta.hierarchytree.ui;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ReadAction;
import com.intellij.pom.Navigatable;
import com.intellij.ui.treeStructure.Tree;
import io.github.lorenzoandreotta.hierarchytree.HierarchyTreeBundle;
import io.github.lorenzoandreotta.hierarchytree.model.AnalysisNode;

import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeSelectionModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

public final class HierarchyTreePanel extends JPanel {
    private final Tree hierarchyTree = createTree();
    private final Tree compileTimeTree = createTree();
    private final Tree runtimeTree = createTree();
    private final JTabbedPane tabs = new JTabbedPane();

    public HierarchyTreePanel() {
        super(new BorderLayout());
        tabs.addTab(HierarchyTreeBundle.message("hierarchy.tab"), new JScrollPane(hierarchyTree));
        tabs.addTab(HierarchyTreeBundle.message("compile.time.tab"), new JScrollPane(compileTimeTree));
        tabs.addTab(HierarchyTreeBundle.message("runtime.tab"), new JScrollPane(runtimeTree));
        add(tabs, BorderLayout.CENTER);
    }

    public void show(AnalysisView view, AnalysisNode root) {
        var tree = switch (view) {
            case HIERARCHY -> hierarchyTree;
            case COMPILE_TIME -> compileTimeTree;
            case RUNTIME -> runtimeTree;
        };
        tree.setModel(new DefaultTreeModel(toSwingNode(root)));
        for (int row = 0; row < tree.getRowCount() && row < 30; row++) {
            tree.expandRow(row);
        }
        tabs.setSelectedIndex(view.tabIndex());
    }

    public int getTabCount() {
        return tabs.getTabCount();
    }

    public int getSelectedTabIndex() {
        return tabs.getSelectedIndex();
    }

    private static DefaultMutableTreeNode toSwingNode(AnalysisNode node) {
        var swingNode = new DefaultMutableTreeNode(node);
        for (AnalysisNode child : node.children()) {
            swingNode.add(toSwingNode(child));
        }
        return swingNode;
    }

    private static Tree createTree() {
        var root = new DefaultMutableTreeNode(HierarchyTreeBundle.message("empty.analysis"));
        var tree = new Tree(new DefaultTreeModel(root));
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.setCellRenderer(new AnalysisNodeRenderer());
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2) {
                    navigate(tree, event);
                }
            }
        });
        return tree;
    }

    private static void navigate(Tree tree, MouseEvent event) {
        var path = tree.getPathForLocation(event.getX(), event.getY());
        if (path == null || !(path.getLastPathComponent() instanceof DefaultMutableTreeNode node)
                || !(node.getUserObject() instanceof AnalysisNode analysisNode)
                || analysisNode.navigationTarget() == null) {
            return;
        }

        var element = ReadAction.compute(() -> {
            var target = analysisNode.navigationTarget().getElement();
            return target != null && target.isValid() ? target : null;
        });
        if (element instanceof Navigatable navigatable) {
            navigatable.navigate(true);
        }
    }

    private static final class AnalysisNodeRenderer extends DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(
                javax.swing.JTree tree,
                Object value,
                boolean selected,
                boolean expanded,
                boolean leaf,
                int row,
                boolean hasFocus
        ) {
            super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus);
            if (value instanceof DefaultMutableTreeNode node
                    && node.getUserObject() instanceof AnalysisNode analysisNode) {
                setText(analysisNode.presentableText());
                setIcon(switch (analysisNode.kind()) {
                    case WARNING -> AllIcons.General.Warning;
                    case INFO -> AllIcons.General.Information;
                    case INTERFACE_TYPE -> AllIcons.Nodes.Interface;
                    case METHOD, CANDIDATE -> AllIcons.Nodes.Method;
                    case ARGUMENT -> AllIcons.Nodes.Parameter;
                    default -> AllIcons.Nodes.Class;
                });
            }
            return this;
        }
    }
}
