package rajawali.scenegraph;

import java.util.List;

import rajawali.bounds.BoundingBox;
import rajawali.math.Number3D;


public abstract class A_nAABBTree extends BoundingBox implements IGraphNode {

	protected boolean mSplit = false; //Have we split to child partitions
	protected List<IGraphNodeMember> mMembers; //A list of all the member objects
	protected List<IGraphNodeMember> mOutside; //A list of all the objects outside the root

	protected int mOverlap = 0; //Partition overlap
	protected int mGrowThreshold = 5; //Threshold at which to grow the graph
	protected int mShrinkThreshold = 2; //Threshold at which to shrink the graph
	protected int mSplitThreshold = 5; //Threshold at which to split the node
	protected int mMergeThreshold = 2; //Threshold at which to merge the node

	protected boolean mRecursiveAdd = true; //Default to recursive add
	protected boolean mRecursiveRemove = true; //Default to recursive remove.

	protected float[] mMMatrix = new float[16]; //A model matrix to use for drawing the bounds of this node.
	protected Number3D mPosition; //This node's center point in 3D space.
	
	/**
	 * Internal method for adding an object to the graph. This method will determine if
	 * it gets added to this node or moved to a child node.
	 * 
	 * @param object IGraphNodeMember to be added.
	 */
	protected abstract void internalAddObject(IGraphNodeMember object);
	
	/**
	 * Adds an object back into the graph when shrinking.
	 * 
	 * @param object The object to be handled.
	 */
	protected abstract void shrinkAddObject(IGraphNodeMember object);
	
	/**
	 * Splits this node into child nodes.
	 */
	protected abstract void split();
	
	/**
	 * Merges this child nodes into their parent node. 
	 */
	protected abstract void merge();
	
	/**
	 * Grows the tree.
	 */
	protected abstract void grow();
	
	/**
	 * Initializes the storage elements of the tree.
	 */
	protected abstract void init();
	
	/**
	 * Shrinks the tree.
	 */
	protected abstract void shrink();
	
	/**
	 * Determines if this node can be merged.
	 * 
	 * @return boolean indicating merge status.
	 */
	protected abstract boolean canMerge();
}
