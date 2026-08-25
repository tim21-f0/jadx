package jadx.gui.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jadx.api.JavaNode;
import jadx.gui.treemodel.JClass;
import jadx.gui.treemodel.JNode;
import jadx.gui.treemodel.TextNode;

/**
 * A FilterableTreeModel provides dynamic filtering over the decompilation package tree.
 * This filtering is done at the model level, to prevent oddities such as zero-width rows disrupting
 * a keyboard user experience or otherwise causing problems.
 *
 * This class is specialised to filter the main UI pane displaying the decompilation tree. If the
 * fullly qualified path of a tree element is obtainable, filtering will check against that, falling
 * back to the standard name if the former is unavailable.
 */
class FilterableTreeModel extends DefaultTreeModel {
	private static final Logger LOG = LoggerFactory.getLogger(FilterableTreeModel.class);

	// The UI locks up when trying to expand many results.
	// The compromise here is to cap the number of results we are willing to expand by default when
	// setting a filter.
	private int filterExpansionThreshold;

	// the filter string
	private String filter;

	// Pre-computed tree paths matching the filter
	// Filtering happens in a two phase process
	// 1. Filter set (in background) pre computes the results
	// 2. UI update (in UI thread) with results
	private final List<TreePath> filteredTreePaths;

	// filterLock ensures no race conditions between the two phase filtering process
	// and any other accesses to tree elements depending on the filter by other tree listeners
	private final ReentrantLock filterLock;

	/**
	 * Constructs a FilterableTreeModel with given root node, to be used as the root of the unfiltered
	 * tree.
	 *
	 * @param root the root node. Passed directly to the constructor for DefaultTreeModel.
	 */
	public FilterableTreeModel(TreeNode root, int filterExpansionThreshold) {
		super(root);
		this.filterExpansionThreshold = filterExpansionThreshold;
		this.filter = "";
		this.filteredTreePaths = new ArrayList<>();
		this.filterLock = new ReentrantLock(true); // Fair reentrant lock guarantees FIFO
	}

	/**
	 * Sets the filter of the tree by pre-computing tree paths matching the filter and refreshing the
	 * tree's node structure.
	 *
	 * This calls `nodeStructureChanged` on the root of the tree, implying a complete refresh of the
	 * tree data. Unfortunately the declarative nature of the filtering makes it infeasible to give a
	 * more specific refresh, which would potentially preserve open more of the current state of the
	 * tree.
	 *
	 * @param newFilter the new filter string, or "" to unset the filter.
	 */
	public void setFilter(String newFilter) {
		this.filterLock.lock();
		try {
			this.filter = newFilter;
			LOG.debug("New class filter '{}'", this.filter);
			collectFilteredPaths();
			// setFilter may be invoked from a Timer or background thread; this means we may not be in the swing
			// UI thread, but nodeStructureChanged must be run on the swing thread!
			SwingUtilities.invokeLater(() -> this.nodeStructureChanged((TreeNode) getRoot()));
		} finally {
			this.filterLock.unlock();
		}
	}

	/**
	 * Filter thread safe nodeStructureChanged event.
	 * This should ensure that all treeListeners get the same filter value per event.
	 */
	@Override
	public void nodeStructureChanged(TreeNode node) {
		this.filterLock.lock();
		try {
			super.nodeStructureChanged(node);
		} finally {
			this.filterLock.unlock();
		}
	}

	/**
	 * Expands the filtered tree paths in the UI, should be called from a treeStructureChanged listener
	 * after a filter has been set.
	 *
	 * @param tree - tree ui component on which to make the filtered paths visible
	 */
	public void makeFilteredPathsVisible(JTree tree) {
		this.filterLock.lock();
		try {
			int count = 0;
			for (TreePath path : this.filteredTreePaths) {
				tree.makeVisible(path);
				++count;
				if (count >= this.filterExpansionThreshold) {
					LOG.warn("Capping displayed results for filter '{}' to {}", this.filter,
							this.filterExpansionThreshold);
					break;
				}
			}
		} finally {
			this.filterLock.unlock();
		}
	}

	public void setFilterExpansionThreshold(int newThreshold) {
		this.filterExpansionThreshold = newThreshold;
	}

	private void collectFilteredPaths() {
		this.filteredTreePaths.clear();
		if (this.filter.isEmpty()) {
			return;
		}

		TreeNode rootNode = (TreeNode) this.getRoot();

		if (rootNode == null) {
			return;
		}

		TreePath rootPath = new TreePath(this.getPathToRoot(rootNode));

		collectFilteredPaths(rootPath);
	}

	private void collectFilteredPaths(TreePath path) {
		if (path.getLastPathComponent() instanceof JClass) {
			filteredTreePaths.add(path);
		} else {
			int childrenCount = this.getChildCount(path.getLastPathComponent());

			if (childrenCount == 0) {
				// this is a leaf node that has not passed through a class
				// e.g. a resource
				filteredTreePaths.add(path);
			}

			for (int i = 0; i < childrenCount; i++) {
				Object child = this.getChild(path.getLastPathComponent(), i);
				collectFilteredPaths(path.pathByAddingChild(child));
			}
		}
	}

	/**
	 * Determines if a given node matches the current filter.
	 *
	 * @param node the node in question
	 * @return true if the filter is considered matched and the node should be displayed in the tree.
	 */
	private boolean matchesFilter(Object node) {
		// `JNode`s are elements in the tree that correspond to a Jadx structure e.g. a JClass or JMethod.
		// Most elements in the tree should be these; top level elements such as the root 'source code' node
		// are not, so we unconditionally show non-JNodes.
		if (node instanceof JNode) {
			JavaNode javaNode = ((JNode) node).getJavaNode();

			// if possible, retrieve the fully qualified name (i.e. including full pacakge path) for filtering,
			// else rely on the default name.
			String name = ((JNode) node).getName();
			if (javaNode != null) {
				name = javaNode.getFullName();
			}

			// there are still some cases where the default name is still null. In this case it's better to show
			// these than hide.
			if (name == null) {
				return true;
			}

			// if we match the filter, non-case-sensitively, display the node.
			if (name.toLowerCase().contains(filter.toLowerCase())) {
				return true;
			}

			// if we do not match the filter but any of our children do, display the node.
			for (TreeNode x : (Iterable<TreeNode>) ((JNode) node).children()::asIterator) {
				if (x instanceof TextNode) {
					continue;
				}
				if (matchesFilter(x)) {
					return true;
				}

			}

			// otherwise, hide the node.
			return false;
		}

		return false;
	}

	@Override
	public Object getChild(Object parent, int index) {
		// It is worth noting that both this method and the one below it can be called from the UI thread
		// so may cause UI lock up for users trying to expand the tree during filter execution.
		// This has not been observed to be too much of an issue during testing since most filters will
		// be quicker than said user interactions.
		this.filterLock.lock();
		try {
			// whilst getFilteredChildren acts the same as just going to the backing directly when no filter is
			// set, it must construct the entire list, which requires N calls to getChild on backing (in
			// getUnfilteredChildren) rather than 1 if we short-circuit it like this.
			if (filter.equals("")) {
				return super.getChild(parent, index);
			}

			return getFilteredChildren(parent).get(index);
		} finally {
			this.filterLock.unlock();
		}
	}

	@Override
	public int getChildCount(Object parent) {
		this.filterLock.lock();
		try {
			if (filter.equals("")) {
				return super.getChildCount(parent);
			}

			return getFilteredChildren(parent).size();
		} finally {
			this.filterLock.unlock();
		}
	}

	private List<Object> getUnfilteredChildren(Object parent) {

		int numChildren = super.getChildCount(parent);
		List<Object> results = new ArrayList<>();

		for (int i = 0; i < numChildren; i++) {
			results.add(super.getChild(parent, i));
		}

		return results;
	}

	private List<Object> getFilteredChildren(Object parent) {
		List<Object> unfiltered = getUnfilteredChildren(parent);

		return unfiltered.stream().filter(obj -> matchesFilter(obj)).collect(Collectors.toList());
	}

}
