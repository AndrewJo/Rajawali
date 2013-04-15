package rajawali.scenegraph;

import java.util.ArrayList;
import java.util.List;

import rajawali.bounds.BoundingBox;
import rajawali.bounds.BoundingSphere;
import rajawali.bounds.IBoundingVolume;
import rajawali.math.Number3D;
import rajawali.util.RajLog;


public abstract class A_nAABBTree extends BoundingBox implements IGraphNode {

	protected A_nAABBTree mParent; //Parent partition;
	protected A_nAABBTree[] mChildren; //Child partitions
	protected Number3D mChildLengths; //Lengths of each side of the child nodes
	
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
	 * Default constructor
	 */
	protected A_nAABBTree() {
		super();
	}
	
	/**
	 * Performs the necessary process to destroy this node
	 */
	protected abstract void destroy();
	
	/**
	 * Calculates the side lengths that child nodes
	 * of this node should have.
	 */
	protected void calculateChildSideLengths() {
		//Determine the distance on each axis
		Number3D temp = Number3D.subtract(mTransformedMax, mTransformedMin);
		temp.multiply(0.5f); //Divide it in half
		float overlap = 1.0f + mOverlap/100.0f;
		temp.multiply(overlap);
		temp.absoluteValue();
		mChildLengths.setAllFrom(temp);
	}
	
	/**
	 * Sets the bounding volume of this node. This should only be called
	 * for a root node with no children. This sets the initial root node
	 * to have a volume ~8x the member, centered on the member.
	 * 
	 * @param object IGraphNodeMember the member we will be basing
	 * our bounds on. 
	 */
	protected void setBounds(IGraphNodeMember member) {
		RajLog.d("[" + this.getClass().getName() + "] Setting bounds based on member: " + member);
		if (mMembers.size() != 0 && mParent != null) {return;}
		IBoundingVolume volume = member.getTransformedBoundingVolume();
		BoundingBox bcube = null;
		BoundingSphere bsphere = null;
		Number3D position = member.getScenePosition();
		double span_y = 0;
		double span_x = 0;
		double span_z = 0;
		if (volume == null) {
			span_x = 5.0;
			span_y = 5.0;
			span_z = 5.0;
		} else {
			if (volume instanceof BoundingBox) {
				bcube = (BoundingBox) volume;
				Number3D min = bcube.getTransformedMin();
				Number3D max = bcube.getTransformedMax();
				span_x = (max.x - min.x);
				span_y = (max.y - min.y);
				span_z = (max.z - min.z);
			} else if (volume instanceof BoundingSphere) {
				bsphere = (BoundingSphere) volume;
				span_x = 2.0*bsphere.getScaledRadius();
				span_y = span_x;
				span_z = span_x;
			}
		}
		mMin.x = (float) (position.x - span_x);
		mMin.y = (float) (position.y - span_y);
		mMin.z = (float) (position.z - span_z);
		mMax.x = (float) (position.x + span_x);
		mMax.y = (float) (position.y + span_y);
		mMax.z = (float) (position.z + span_z);
		mTransformedMin.setAllFrom(mMin);
		mTransformedMax.setAllFrom(mMax);
		calculatePoints();
		calculateChildSideLengths();
	}
	
	/**
	 * Sets the bounding volume of this node to that of the specified
	 * child. This should only be called for a root node during a shrink
	 * operation. 
	 * 
	 * @param child int Which octant to match.
	 */
	protected void setBounds(int child) {
		A_nAABBTree new_bounds = mChildren[child];
		mMin.setAllFrom(new_bounds.mMin);
		mMax.setAllFrom(new_bounds.mMax);
		mTransformedMin.setAllFrom(mMin);
		mTransformedMax.setAllFrom(mMax);
		calculatePoints();
		calculateChildSideLengths();
	}
	
	/**
	 * Sets the threshold for growing the tree.
	 * 
	 * @param threshold int containing the new threshold.
	 */
	public void setGrowThreshold(int threshold) {
		mGrowThreshold = threshold;
	}

	/**
	 * Sets the threshold for shrinking the tree.
	 * 
	 * @param threshold int containing the new threshold.
	 */
	public void setShrinkThreshold(int threshold) {
		mShrinkThreshold = threshold;
	}

	/**
	 * Sets the threshold for merging this node.
	 * 
	 * @param threshold int containing the new threshold.
	 */
	public void setMergeThreshold(int threshold) {
		mMergeThreshold = threshold;
	}

	/**
	 * Sets the threshold for splitting this node.
	 * 
	 * @param threshold int containing the new threshold.
	 */
	public void setSplitThreshold(int threshold) {
		mSplitThreshold = threshold;
	}
	
	/**
	 * Adds the specified object to this node's internal member
	 * list and sets the node attribute on the member to this
	 * node.
	 * 
	 * @param object IGraphNodeMember to be added.
	 */
	protected abstract void addToMembers(IGraphNodeMember object);
	
	/**
	 * Removes the specified object from this node's internal member
	 * list and sets the node attribute on the member to null.
	 * 
	 * @param object IGraphNodeMember to be removed.
	 */
	protected abstract void removeFromMembers(IGraphNodeMember object);
	
	/**
	 * Adds the specified object to the scenegraph's outside member
	 * list and sets the node attribute on the member to null.
	 * 
	 * @param object IGraphNodeMember to be added.
	 */
	protected abstract void addToOutside(IGraphNodeMember object);
	
	/**
	 * Returns a list of all members of this node and any decendent nodes.
	 * 
	 * @param shouldClear boolean indicating if the search should clear the lists.
	 * @return ArrayList of IGraphNodeMembers.
	 */
	protected abstract ArrayList<IGraphNodeMember> getAllMembersRecursively(boolean shouldClear);
	
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
	
	/*
	 * (non-Javadoc)
	 * @see rajawali.scenegraph.IGraphNode#clear()
	 */
	public void clear() {
		mMembers.clear();
		if (mOutside != null) {
			mOutside.clear();
		}
	}
	
	/*
	 * (non-Javadoc)
	 * @see rajawali.scenegraph.IGraphNode#addChildrenRecursively(boolean)
	 */
	public void addChildrenRecursively(boolean recursive) {
		mRecursiveAdd = recursive;
	}

	/*
	 * (non-Javadoc)
	 * @see rajawali.scenegraph.IGraphNode#removeChildrenRecursively(boolean)
	 */
	public void removeChildrenRecursively(boolean recursive) {
		mRecursiveRemove = recursive;
	}
}
