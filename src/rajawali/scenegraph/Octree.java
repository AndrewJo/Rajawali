package rajawali.scenegraph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import rajawali.ATransformable3D;
import rajawali.BaseObject3D;
import rajawali.Camera;
import rajawali.bounds.BoundingBox;
import rajawali.bounds.BoundingSphere;
import rajawali.bounds.IBoundingVolume;
import rajawali.lights.ALight;
import rajawali.materials.SimpleMaterial;
import rajawali.math.Number3D;
import rajawali.primitives.Cube;
import rajawali.util.RajLog;
import android.opengl.GLES20;
import android.opengl.Matrix;


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

	protected ISceneGraphCallbacks mListener; //The callback listener for this tree
	protected Octree mParent; //Parent partition;
	protected Octree[] mChildren; //Child partitions
	protected Number3D mChildLengths; //Lengths of each side of the child nodes
	protected boolean mSplit = false; //Have we split to child partitions
	protected List<IGraphNodeMember> mMembers; //A list of all the member objects
	protected List<IGraphNodeMember> mOutside; //A list of all the objects outside the root

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
	 * The octant this node occupies in its parent. If this node
	 * has no parent this is a meaningless number. A negative
	 * number is used to represent that there is no octant assigned.
	 * 
	 * The octant order follows the conventional algebraic numbering
	 * for 3D Euclidean space. Note that they follow the axis ordering
	 * and OpenGL uses a rotated coordinate system when compared to 
	 * Euclidean mathematics. Thus, assuming no camera rotation or similar
	 * effects:
	 * @see <a href="http://en.wikipedia.org/wiki/Octant_(solid_geometry)">
	 http://en.wikipedia.org/wiki/Octant_(solid_geometry)</a>
	 * <pre> 
	 *     Octant     | Screen Region
	 * ---------------|---------------
	 *       0        | Upper right, z > 0
	 *       1        | Upper left, z > 0
	 *       2        | Lower left, z > 0
	 *       3        | Lower right, z > 0
	 *       4        | Upper right, z < 0
	 *       5        | Upper left, z < 0
	 *       6        | Lower left, z < 0
	 *       7        | Lower right, z < 0
	 * </pre>
	 */
	protected int mOctant = -1;

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
		mMembers = Collections.synchronizedList(new CopyOnWriteArrayList<IGraphNodeMember>());
		mOutside = Collections.synchronizedList(new CopyOnWriteArrayList<IGraphNodeMember>());
	}

	/**
	 * Sets the octant this node occupies in its parent.
	 * 
	 * @param octant Integer octant this child occupies.
	 * @param size Number3D containing the length for each
	 * side this node should be. 
	 */
	protected void setOctant(int octant, Number3D side_lengths) {
		mOctant = octant;
		switch (mOctant) {
		case 0: //+X/+Y/+Z
			mMax.setAllFrom(mParent.mMax);
			mTransformedMax.setAllFrom(mMax);
			mMin.setAllFrom(Number3D.subtract(mMax, side_lengths));
			mTransformedMin.setAllFrom(mMin);
			break;
		case 1: //-X/+Y/+Z
			break;
		case 2:
			break;
		case 3:
			break;
		case 4:
			break;
		case 5: 
			break;
		case 6: //-X/-Y/-Z
			mMin.setAllFrom(mParent.mMin);
			mTransformedMin.setAllFrom(mMin);
			mMax.setAllFrom(Number3D.add(mMin, side_lengths));
			mTransformedMin.setAllFrom(mMax);
			break;
		case 7:
			break;
		default:
			return;
		}
		calculatePoints();
	}

	/**
	 * Retrieve the octant this node resides in.
	 * 
	 * @return integer The octant.
	 */
	protected int getOctant() {
		return mOctant;
	}
	
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
	 * Performs the necessary process to destroy this node
	 */
	protected void destroy() {
		RajLog.d("[" + this.getClass().getName() + "] Destroying octree node: " + this);
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
		BaseObject3D object = null;
		Camera camera = null;
		ALight light = null;
		Number3D position = null;
		double span_y = 0;
		double span_x = 0;
		double span_z = 0;
		if (member instanceof BaseObject3D) {
			object = (BaseObject3D) member;
			position = object.getPosition();
		} else if (member instanceof Camera) {
			camera = (Camera) member;
			position = camera.getPosition();
		} else if (member instanceof ALight) {
			light = (ALight) member;
			position = light.getPosition();
		}
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
		mTransformedMin = mMin;
		mTransformedMax = mMax;

		/*RajLog.d("[" + this.getClass().getName() + "] Position: " + position);
		RajLog.d("[" + this.getClass().getName() + "] Spans: " + span_x + ", " + span_y + ", " + span_z);
		RajLog.d("[" + this.getClass().getName() + "] Min/Max: " + mMin + "/" + mMax);*/
		calculatePoints();
	}

	protected void internalAddObject(IGraphNodeMember object) {
		//TODO: Implement a batch process for this to save excessive splitting/merging
		if (mSplit) {
			//Check if the object fits in our children
			for (int i = 0; i < CHILD_COUNT; ++i) {
				if (mChildren[i].contains(object.getTransformedBoundingVolume())) {
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
			mChildren[i].mBoundingColor = mBoundingColor - 0x0000000F;
			mChildren[i].setOctant(i, mChildLengths);
		}
		for (int j = 0; j < CHILD_COUNT; ++j) {
			int member_count = mMembers.size();
			//Keep a list of members we have removed
			ArrayList<IGraphNodeMember> removed = new ArrayList<IGraphNodeMember>();
			for (int i = 0; i < member_count; ++i) {
				IGraphNodeMember member = mMembers.get(i);
				if (mChildren[j].contains(member.getTransformedBoundingVolume())) {
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
	 * Grows the tree.
	 */
	protected void grow() {
		RajLog.d("[" + this.getClass().getName() + "] Growing tree");
		//Determine the direction to grow
		Number3D min = new Number3D(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE);
		Number3D max = new Number3D(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE);
		ArrayList<IGraphNodeMember> items = new ArrayList<IGraphNodeMember>();
		items.addAll(mMembers);
		items.addAll(mOutside);
		for (IGraphNodeMember member : items) {
			IBoundingVolume volume = member.getTransformedBoundingVolume();
			if (volume == null) {
				ATransformable3D object = (ATransformable3D) member;
				Number3D pos = object.getPosition();
				if(pos.x < min.x) min.x = pos.x;
				if(pos.y < min.y) min.y = pos.y;
				if(pos.z < min.z) min.z = pos.z;
				if(pos.x > max.x) max.x = pos.x;
				if(pos.y > max.y) max.y = pos.y;
				if(pos.z > max.z) max.z = pos.z;
			} else {
				if (volume instanceof BoundingBox) {
					BoundingBox bb = (BoundingBox) volume;
					Number3D bb_min = bb.getTransformedMin();
					Number3D bb_max = bb.getTransformedMax();
					if(bb_min.x < min.x) min.x = bb_min.x;
					if(bb_min.y < min.y) min.y = bb_min.y;
					if(bb_min.z < min.z) min.z = bb_min.z;
					if(bb_max.x > max.x) max.x = bb_max.x;
					if(bb_max.y > max.y) max.y = bb_max.y;
					if(bb_max.z > max.z) max.z = bb_max.z;
				} else if (volume instanceof BoundingSphere) {
					BoundingSphere bs = (BoundingSphere) volume;
					Number3D bs_position = bs.getPosition();
					float radius = bs.getScaledRadius();
					if((bs_position.x - radius) < min.x) min.x = (bs_position.x - radius);
					if((bs_position.y - radius) < min.y) min.y = (bs_position.y - radius);
					if((bs_position.z - radius) < min.z) min.z = (bs_position.z - radius);
					if((bs_position.x + radius) > max.x) max.x = (bs_position.x + radius);
					if((bs_position.y + radius) > max.y) max.y = (bs_position.y + radius);
					if((bs_position.z + radius) > max.z) max.z = (bs_position.z + radius);
				} else {
					RajLog.e("[" + this.getClass().getName() + "] Received a bounding box of unknown type.");
					throw new IllegalArgumentException("Received a bounding box of unknown type."); 
				}
			}
		}
		RajLog.d("[" + this.getClass().getName() + "] New root min/max should be: " + min + "/" + max);
		Octree newRoot = new Octree(this, mMergeThreshold, mSplitThreshold, mShrinkThreshold, mGrowThreshold, mOverlap);
		newRoot.setBoundingColor(0xFFFF0000);
		newRoot.mMin.setAllFrom(min);
		newRoot.mMax.setAllFrom(max);
		newRoot.mTransformedMin.setAllFrom(min);
		newRoot.mTransformedMax.setAllFrom(max);
		newRoot.calculatePoints();
		RajLog.d("[" + this.getClass().getName() + "] New root node: " + newRoot);
		for (IGraphNodeMember member : mOutside) {newRoot.addObject(member);}
		for (IGraphNodeMember member : mMembers) {newRoot.addObject(member);}
		newRoot.mParent = null;
		newRoot.setListener(mListener);
		mListener.updateRootNode(newRoot);
		destroy();
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
	/*public void addObject(IGraphNodeMember object) {
		addObject(object, false);
	}*/
	
	/**
	 * Internal method for adding objects.
	 * 
	 * @param object IGraphNodeMember to be added.
	 * @param grow boolean indicating if this is being called
	 * from a grow() operation.
	 */
	public void addObject(IGraphNodeMember object) {
		RajLog.d("[" + this.getClass().getName() + "] Adding object: " + object + " to octree."); 
		//TODO: Handle recursive add posibility
		if (mParent == null) {
			//We are the root node
			mBoundingColor = 0xFFFF0000;
			if (mMembers.size() == 0) {
				//Set bounds based the incoming objects bounding box
				setBounds(object); 
				addToMembers(object);
			} else {
				//Check if object is in bounds
				if (contains(object.getTransformedBoundingVolume())) {
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
		RajLog.d("[" + this.getClass().getName() + "] Member/Outside count: "
				+ mMembers.size() + "/" + mOutside.size());
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
		if (mMembers.size() == 0 && mOutside.size() == 0 && mParent == null) {return;}
		RajLog.d("[" + this.getClass().getName() + "] Drawing octree: " + this);
		RajLog.d("[" + this.getClass().getName() + "] Octree min/max: " + 
				mTransformedMin + "/" + mTransformedMax);
		//RajLog.d("[" + this.getClass().getName() + "] Member/Outside count: "
		//		+ mMembers.size() + "/" + mOutside.size());
		Matrix.setIdentityM(mMMatrix, 0);
		drawBoundingVolume(camera, projMatrix, vMatrix, mMMatrix);
	}
	
	@Override
	public void drawBoundingVolume(Camera camera, float[] projMatrix, float[] vMatrix, float[] mMatrix) {
		if(mVisualBox == null) {
			mVisualBox = new Cube(1);
			mVisualBox.setMaterial(new SimpleMaterial());
			mVisualBox.getMaterial().setUseColor(true);
			mVisualBox.setColor(mBoundingColor);
			mVisualBox.setDrawingMode(GLES20.GL_LINE_LOOP);
		}
		
		mVisualBox.setScale(
				Math.abs(mTransformedMax.x - mTransformedMin.x),
				Math.abs(mTransformedMax.y - mTransformedMin.y),
				Math.abs(mTransformedMax.z - mTransformedMin.z)
				);
		Matrix.setIdentityM(mTmpMatrix, 0);
		mVisualBox.setPosition(
				mTransformedMin.x + (mTransformedMax.x - mTransformedMin.x) * .5f, 
				mTransformedMin.y + (mTransformedMax.y - mTransformedMin.y) * .5f, 
				mTransformedMin.z + (mTransformedMax.z - mTransformedMin.z) * .5f
				);
		
		mVisualBox.render(camera, projMatrix, vMatrix, mTmpMatrix, null);
	}
	
	/*@Override
	public String toString() {
		return "Octree node: " + Integer.toString(this.getClass().hashCode());
	}*/

	/*
	 * (non-Javadoc)
	 * @see rajawali.scenegraph.IGraphNode#setListener(rajawali.scenegraph.ISceneGraphCallbacks)
	 */
	public void setListener(ISceneGraphCallbacks listener) {
		mListener = listener;
	}
}