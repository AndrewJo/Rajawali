package rajawali.scenegraph;

import java.util.ArrayList;
import java.util.List;

import android.opengl.Matrix;

import rajawali.BaseObject3D;
import rajawali.Camera;
import rajawali.bounds.BoundingBox;
import rajawali.bounds.IBoundingVolume;
import rajawali.math.Number3D;
import rajawali.util.RajLog;


/**
 * Octree implementation specific to the Rajawali library. This implementation
 * uses the methodology described in the tutorial listed below by Paramike.
 * 
 * By default, this tree will recursively add the children of added objects and
 * recursively remove the children of removed objects. 
 * 
 * Child partitions will inherit the behavior (recursive add, division threshold, etc.)
 * of the root node in the graph.
 * 
 * @author Jared Woolston (jwoolston@tenkiv.com)
 * @see {@link http://www.piko3d.com/tutorials/space-partitioning-tutorial-piko3ds-dynamic-octree}
 */
public class Octree extends BoundingBox implements IGraphNode {

	//TODO: Check that mOutside of child nodes is always 0
	
	private static final int CHILD_COUNT = 8;

	protected Octree mParent; //Parent partition;
	protected Octree[] mChildren; //Child partitions
	protected boolean mSplit = false; //Have we split to child partitions
	protected ArrayList<IGraphNodeMember> mMembers; //A list of all the member objects
	protected ArrayList<IGraphNodeMember> mOutside; //A list of all the objects outside the root

	protected int mOverlap = 10; //Partition overlap
	protected int mGrowThreshold = 5; //Threshold at which to grow the graph
	protected int mShrinkThreshold = 2; //Threshold at which to shrink the graph
	protected int mSplitThreshold = 5; //Threshold at which to split the node
	protected int mMergeThreshold = 2; //Threshold at which to merge the node
	
	protected boolean mRecursiveAdd = true; //Default to recursive add
	protected boolean mRecursiveRemove = true; //Default to recursive remove.

	protected float[] mMMatrix = new float[16];
	protected Number3D mPosition;

	/**
	 * Default constructor. Initializes the root node with default merge/division
	 * behavior.
	 */
	public Octree() {
		super();
		init();
	}

	/**
	 * Constructor to setup root node with specified merge/split and
	 * grow/shrink behavior.
	 * 
	 * @param maxMembers int containing the divide threshold count. When more 
	 * members than this are added, a partition will divide into 8 children.
	 * @param minMembers int containing the merge threshold count. When fewer
	 * members than this exist, a partition will recursively merge to its ancestors.
	 * @param overlap int containing the percentage overlap between two adjacent
	 * partitions. This allows objects to be nested deeper in the tree when they
	 * would ordinarily span a boundry.
	 */
	public Octree(int mergeThreshold, int splitThreshold, int shrinkThreshold, int growThreshold, int overlap) {
		this(null, mergeThreshold, splitThreshold, shrinkThreshold, growThreshold, overlap);
	}

	/**
	 * Constructor to setup a child node with specified merge/split and 
	 * grow/shrink behavior.
	 * 
	 * @param parent Octree which is the parent of this partition.
	 * @param maxMembers int containing the divide threshold count. When more 
	 * members than this are added, a partition will divide into 8 children.
	 * @param minMembers int containing the merge threshold count. When fewer
	 * members than this exist, a partition will recursively merge to its ancestors.
	 * @param overlap int containing the percentage overlap between two adjacent
	 * partitions. This allows objects to be nested deeper in the tree when they
	 * would ordinarily span a boundry.
	 */
	public Octree(Octree parent, int mergeThreshold, int splitThreshold, int shrinkThreshold, int growThreshold, int overlap) {
		super();
		mParent = parent;
		mMergeThreshold = mergeThreshold;
		mSplitThreshold = splitThreshold;
		mShrinkThreshold = shrinkThreshold;
		mGrowThreshold = growThreshold;
		mOverlap = overlap;
		init();
	}

	/**
	 * Initializes the storage elements of the tree.
	 */
	protected void init() {
		//Pre-allocate storage here to favor modification speed
		mPosition = new Number3D(0, 0, 0);
		mChildren = new Octree[CHILD_COUNT];
		mMembers = new ArrayList<IGraphNodeMember>();
		mOutside = new ArrayList<IGraphNodeMember>();
	}
	
	/**
	 * Performs the necessary process to destroy this node
	 */
	protected void destroy() {
		//TODO: Implement
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
	 * Adds the specified object to this nodes internal member
	 * list and sets the node parameter on the member to this
	 * node.
	 * 
	 * @param object IGraphNodeMember to be added.
	 */
	protected void addToMembers(IGraphNodeMember object) {
		mMembers.add(object);
		object.setGraphNode(this);
	}
	
	protected List<IGraphNodeMember> getAllMembersRecursively() {
		ArrayList<IGraphNodeMember> members = new ArrayList<IGraphNodeMember>();
		
		return members;
	}
	
	/**
	 * Sets the bounding volume of this node. This should only be called
	 * for a root node with no children.
	 * 
	 * @param object IGraphNodeMember the member we will be basing
	 * our bounds on. 
	 */
	protected void setBounds(IGraphNodeMember member) {
		//TODO: Implement
		RajLog.d("[" + this.getClass().getName() + "] Setting bounds based on member: " + member);
		if (mMembers.size() != 0 && mParent != null) {return;}
		BaseObject3D object = (BaseObject3D) member;
		BoundingBox bcube = object.getGeometry().getBoundingBox();
		bcube.transform(object.getModelMatrix());
		RajLog.d("[" + this.getClass().getName() + "] Member bounding volume: " + bcube);
		RajLog.d("[" + this.getClass().getName() + "] Member bounding position: " + object.getPosition());
		Number3D position = object.getPosition();
		Number3D min = bcube.getMin();
		Number3D max = bcube.getMax();
		double span_y = (2.0 * (max.y - min.y));
		double span_x = (2.0 * (max.x - min.x));
		double span_z = (2.0 * (max.z - min.z));
		mMin.x = (float) (position.x - (span_x/2.0));
		mMin.y = (float) (position.y - (span_y/2.0));
		mMin.z = (float) (position.z - (span_z/2.0));
		mMax.x = (float) (position.x + (span_x/2.0));
		mMax.y = (float) (position.y + (span_y/2.0));
		mMax.z = (float) (position.z + (span_z/2.0));
		calculatePoints();
	}
	
	protected void internalAddObject(IGraphNodeMember object) {
		//TODO: Implement a batch process for this to save excessive splitting/merging
		if (mSplit) {
			//Check if the object fits in our children
			for (int i = 0; i < CHILD_COUNT; ++i) {
				if (mChildren[i].contains(object.getBoundingVolume())) {
					mChildren[i].addObject(object);
					return; //Ensures only one child gets the object 
					//TODO: Verify this is the desired behavior
				}
			}
			//It didn't fit in any of the children, so store it here
			addToMembers(object);
		} else {
			//We just add it to this node, then check if we should split
			addToMembers(object);
			if (mMembers.size() >= mSplitThreshold) {
				split();
			}
		}
	}
	
	/**
	 * Splits this node into {@link CHILD_COUNT} child nodes.
	 */
	protected void split() {
		RajLog.d("[" + this.getClass().getName() + "] Spliting node: " + this);
		//Populate child array
		for (int i = 0; i < CHILD_COUNT; ++i) {
			mChildren[i] = new Octree(this, mMergeThreshold,
					mSplitThreshold, mShrinkThreshold, mGrowThreshold, mOverlap);
		}
		for (int j = 0; j < CHILD_COUNT; ++j) {
			int member_count = mMembers.size();
			//Keep a list of members we have removed
			ArrayList<IGraphNodeMember> removed = new ArrayList<IGraphNodeMember>();
			for (int i = 0; i < member_count; ++i) {
				IGraphNodeMember member = mMembers.get(i);
				if (mChildren[j].contains(member.getBoundingVolume())) {
					//If the member fits in this child, move it to that child
					mChildren[j].addObject(member);
					removed.add(member);
				}
			}
			//Now remove all of the members marked for removal
			mMembers.removeAll(removed);
		}
		mSplit = true; //Flag that we have split
	}
	
	/**
	 * Merges this child nodes into their parent node. 
	 * 
	 */
	protected void merge() {
		RajLog.d("[" + this.getClass().getName() + "] Merge nodes called on node: " + this);
		if (mParent.canMerge()) {
			mParent.merge();
		} else {
			for (int i = 0; i < CHILD_COUNT; ++i) {
				//Add all the members of all the children
				for (IGraphNodeMember member : mChildren[i].getAllMembersRecursively()) {
					addObject(member);
				}
				mChildren[i].destroy();
				mChildren[i] = null;
			}
		}
	}
	
	/**
	 * Grows the three.
	 */
	protected void grow() {
		RajLog.d("[" + this.getClass().getName() + "] Growing tree");
		
	}
	
	/**
	 * Shrinks the tree.
	 */
	protected void shrink() {
		RajLog.d("[" + this.getClass().getName() + "] Shrinking tree");
		if (mParent != null) { //Pass the shrink call up the tree
			mParent.shrink();
		} else {
			if (mOutside.size() <= mShrinkThreshold) {
				//TODO: Check logic and finish
			}
		}
	}
	
	/**
	 * Determines if this node can be merged.
	 * 
	 * @return boolean indicating merge status.
	 */
	public boolean canMerge() {
		//Determine recursive member count
		int count = mMembers.size();
		for (int i = 0; i < CHILD_COUNT; ++i) {
			count += mChildren[i].mMembers.size();
		}
		return (count <= mMergeThreshold);
	}
	
	/*
	 * (non-Javadoc)
	 * @see rajawali.scenegraph.IGraphNode#addObject(rajawali.ATransformable3D)
	 */
	public void addObject(IGraphNodeMember object) {
		RajLog.d("[" + this.getClass().getName() + "] Adding object: " + object + " to octree."); 
		//TODO: Handle recursive add posibility
		if (mParent == null) {
			//We are the root node
			if (mMembers.size() == 0) {
				//Set bounds to the incoming objects bounding box
				setBounds(object);
				addToMembers(object);
			} else {
				//Check if object is in bounds
				if (contains(object.getBoundingVolume())) {
					//The object is fully in bounds
					internalAddObject(object);
				} else {
					//The object is not in bounds or only partially in bounds
					//Add it to the outside container
					mOutside.add(object);
					if (mOutside.size() >= mGrowThreshold) {
						grow();
					}
				}
			}
		} else {
			//We are a branch or leaf node
			internalAddObject(object);
		}
	}

	/*
	 * (non-Javadoc)
	 * @see rajawali.scenegraph.IGraphNode#removeObject(rajawali.ATransformable3D)
	 */
	public void removeObject(IGraphNodeMember object) {
		RajLog.d("[" + this.getClass().getName() + "] Removing object: " + object + " to octree.");
		//Handle recursive remove possibility
		//Retrieve the container object
		IGraphNode container = object.getGraphNode();
		if (container == this) {
			//If this is the container, process the removal
			//Remove the object from the members
			mMembers.remove(object);
			if (canMerge() && mParent != null) {
				//If we can merge, do it (if we are the root node, we can't)
				merge();
			}
			shrink(); //Try to shrink the tree
		} else {
			//Defer the removal to the container
			container.removeObject(object);
		}
	}
	
	/*
	 * (non-Javadoc)
	 * @see rajawali.scenegraph.IGraphNode#updateObject(rajawali.ATransformable3D)
	 */
	public  void updateObject(IGraphNodeMember object) {
		RajLog.d("[" + this.getClass().getName() + "] Updating object: " + object + " in octree.");
		//TODO: Implement
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

	/*
	 * (non-Javadoc)
	 * @see rajawali.scenegraph.IGraphNode#rebuild()
	 */
	public void rebuild() {
		// TODO Auto-generated method stub

	}

	/*
	 * (non-Javadoc)
	 * @see rajawali.scenegraph.IGraphNode#clear()
	 */
	public void clear() {
		// TODO Auto-generated method stub

	}

	/*
	 * (non-Javadoc)
	 * @see rajawali.scenegraph.IGraphNode#cullFromBoundingVolume(rajawali.bounds.IBoundingVolume)
	 */
	public void cullFromBoundingVolume(IBoundingVolume volume) {
		// TODO Auto-generated method stub

	}

	/*
	 * (non-Javadoc)
	 * @see rajawali.scenegraph.IGraphNode#displayGraph(boolean)
	 */
	public void displayGraph(Camera camera, float[] projMatrix, float[] vMatrix) {
		RajLog.d("[" + this.getClass().getName() + "] Drawing octree: " + this);
		Matrix.setIdentityM(mMMatrix, 0);
		Matrix.translateM(mMMatrix, 0, -mPosition.x, mPosition.y, mPosition.z);
		transform(mMMatrix);
		drawBoundingVolume(camera, projMatrix, vMatrix, mMMatrix);
	}
}