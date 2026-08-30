package jadx.gui.ui;

import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Future;

import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jadx.api.ResourceType;
import jadx.gui.jobs.SimpleTask;
import jadx.gui.jobs.TaskStatus;
import jadx.gui.treemodel.JNode;
import jadx.gui.treemodel.JResource;
import jadx.gui.treemodel.JRoot;
import jadx.gui.treemodel.TextNode;
import jadx.gui.utils.UiUtils;

/**
 * A FilterableTreeModel provides dynamic filtering over the decompilation package tree.
 * This filtering is done at the model level, to prevent oddities such as zero-width rows disrupting
 * a keyboard user experience or otherwise causing problems.
 * <p>
 * This class is specialized to filter the main UI pane displaying the decompilation tree. If the
 * fully qualified path of a tree element is obtainable, filtering will check against that, falling
 * back to the standard name if the former is unavailable.
 */
class FilterableTreeModel extends DefaultTreeModel {
	private static final Logger LOG = LoggerFactory.getLogger(FilterableTreeModel.class);

	private final MainWindow mainWindow;

	/**
	 * The UI locks up when trying to expand many results.
	 * The compromise here is to cap the number of results we are willing to expand by default when
	 * setting a filter.
	 */
	private int filterExpansionThreshold;

	/**
	 * The filter string
	 */
	private String filter;

	/**
	 * Pre-computed tree paths matching the filter
	 * Filtering happens in a two phase process
	 * 1. Filter set (in background) pre-computes the results
	 * 2. UI update (in UI thread) with results
	 */
	private final List<TreePath> filteredTreePaths;

	/**
	 * All nodes to filtered paths (including middle nodes)
	 */
	private final Set<TreeNode> filteredTreeNodes;

	public FilterableTreeModel(MainWindow mainWindow, TreeNode root, int filterExpansionThreshold) {
		super(root);
		this.mainWindow = mainWindow;
		this.filterExpansionThreshold = filterExpansionThreshold;
		this.filter = "";
		this.filteredTreePaths = new ArrayList<>();
		this.filteredTreeNodes = new HashSet<>();
	}

	/**
	 * Sets the filter of the tree by pre-computing tree paths matching the filter and refreshing the
	 * tree's node structure.
	 * <p>
	 * This calls `nodeStructureChanged` on the root of the tree, implying a complete refresh of the
	 * tree data. Unfortunately the declarative nature of the filtering makes it infeasible to give a
	 * more specific refresh, which would potentially preserve open more of the current state of the
	 * tree.
	 *
	 * @param newFilter the new filter string, or "" to unset the filter.
	 */
	public synchronized void setFilter(String newFilter) {
		this.filter = newFilter;
		LOG.debug("New class filter '{}'", newFilter);
		applyFilterFieldOutline("");
		collectFilteredPaths();
		SwingUtilities.invokeLater(() -> this.nodeStructureChanged((TreeNode) getRoot()));
	}

	/**
	 * Filter thread safe nodeStructureChanged event.
	 * This should ensure that all treeListeners get the same filter value per event.
	 */
	@Override
	public synchronized void nodeStructureChanged(TreeNode node) {
		super.nodeStructureChanged(node);
	}

	/**
	 * Expands the filtered tree paths in the UI, should be called from a treeStructureChanged listener
	 * after a filter has been set.
	 *
	 * @param tree - tree ui component on which to make the filtered paths visible
	 */
	public synchronized void makeFilteredPathsVisible(JTree tree) {
		int limit = Math.max(0, filterExpansionThreshold);
		int count = 0;
		for (TreePath path : filteredTreePaths) {
			tree.makeVisible(path);
			if (limit != 0 && count++ > limit) {
				LOG.warn("Capping displayed results for filter '{}' to {}", filter, limit);
				applyFilterFieldOutline("warning");
				break;
			}
		}
	}

	private void applyFilterFieldOutline(String outlineType) {
		UiUtils.uiRun(() -> mainWindow.getTreeFilterField().putClientProperty("JComponent.outline", outlineType));
	}

	public void setFilterExpansionThreshold(int newThreshold) {
		this.filterExpansionThreshold = newThreshold;
	}

	private void collectFilteredPaths() {
		UiUtils.notUiThreadGuard();
		filteredTreePaths.clear();
		filteredTreeNodes.clear();
		if (filter.isEmpty()) {
			return;
		}
		JRoot rootNode = (JRoot) this.getRoot();
		if (rootNode == null) {
			return;
		}
		Enumeration<TreeNode> en = rootNode.breadthFirstEnumeration();
		while (en.hasMoreElements()) {
			TreeNode node = en.nextElement();
			if (matchesFilter(node)) {
				TreePath path = new TreePath(this.getPathToRoot(node));
				filteredTreePaths.add(path);
				addPathNodes(node);
			}
		}
		if (LOG.isDebugEnabled()) {
			LOG.debug("Filtered tree paths: {}, nodes: {}", filteredTreePaths.size(), filteredTreeNodes.size());
		}
		if (filteredTreePaths.isEmpty()) {
			applyFilterFieldOutline("error");
		}
	}

	private void addPathNodes(TreeNode node) {
		if (!filteredTreeNodes.add(node)) {
			return;
		}
		TreeNode parent = node.getParent();
		while (parent != null) {
			if (!filteredTreeNodes.add(parent)) {
				break;
			}
			parent = parent.getParent();
		}
	}

	/**
	 * Determines if a given node matches the current filter.
	 *
	 * @param node the node in question
	 * @return true if the filter is considered matched and the node should be displayed in the tree.
	 */
	private boolean matchesFilter(Object node) {
		if (node instanceof TextNode) {
			return false;
		}
		if (node instanceof JResource) {
			JResource res = (JResource) node;
			if (res.getType() == JResource.JResType.FILE && res.getResFile().getType() == ResourceType.ARSC) {
				loadInnerResources(res);
			}
		}
		if (node instanceof JNode) {
			JNode jNode = (JNode) node;
			String name = jNode.makeString();
			if (name == null) {
				LOG.warn("Node {} has null UI string", node);
				return false;
			}
			return name.toLowerCase().contains(filter.toLowerCase());
		}
		return false;
	}

	private void loadInnerResources(JResource res) {
		// load inner resource of resource.arsc
		SimpleTask loadTask = res.getLoadTask();
		if (loadTask != null) {
			try {
				Future<TaskStatus> load = mainWindow.getBackgroundExecutor().executeWithFuture(loadTask);
				load.get(); // wait for completion
			} catch (Exception e) {
				LOG.warn("Failed to load resource", e);
			}
		}
	}

	@Override
	public synchronized Object getChild(Object parent, int index) {
		if (filter.isEmpty()) {
			return super.getChild(parent, index);
		}
		int i = 0;
		Enumeration<? extends TreeNode> en = ((TreeNode) parent).children();
		while (en.hasMoreElements()) {
			TreeNode child = en.nextElement();
			if (filteredTreeNodes.contains(child)) {
				if (i == index) {
					return child;
				}
				i++;
			}
		}
		throw new IllegalArgumentException("No child at index " + index);
	}

	@Override
	public synchronized int getChildCount(Object parent) {
		if (filter.isEmpty()) {
			return super.getChildCount(parent);
		}
		TreeNode parentNode = (TreeNode) parent;
		if (!filteredTreeNodes.contains(parentNode)) {
			return 0;
		}
		int count = 0;
		Enumeration<? extends TreeNode> en = parentNode.children();
		while (en.hasMoreElements()) {
			TreeNode child = en.nextElement();
			if (filteredTreeNodes.contains(child)) {
				count++;
			}
		}
		return count;
	}
}
