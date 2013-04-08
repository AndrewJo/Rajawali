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
import rajawali.math.Number3D;
import rajawali.util.RajLog;
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

	protected static final int[] COLORS = new int[]{
		0xFF00000F, 0xFF0000FF, 0xFF000F00, 0xFF000F0F,
		0xFF000FF0, 0xFF000FFF, 0xFF00F000, 0xFF00F00F
	};

	protected static final int FITS_OCTANT_0_FLAG = 0x00000001;
	protected static final int FITS_OCTANT_1_FLAG = 0x00000002;
	protected static final int FITS_OCTANT_2_FLAG = 0x00000004;
	protected static final int FITS_OCTANT_3_FLAG = 0x00000008;
	protected static final int FITS_OCTANT_4_FLAG = 0x00000010;
	protected static final int FITS_OCTANT_5_FLAG = 0x00000020;
	protected static final int FITS_OCTANT_6_FLAG = 0x00000040;
	protected static final int FITS_OCTANT_7_FLAG = 0x00000080;

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
		mChildLengths = new Number3D();
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
			mMin.setAllFrom(Number3D.subtract(mMax, side_lengths));
			break;
		case 1: //-X/+Y/+Z 
			mMax.x = mParent.mMin.x + side_lengths.x;
			mMax.y = mParent.mMax.y;
			mMax.z = mParent.mMax.z;
			mMin.x = mParent.mMin.x;
			mMin.y = mParent.mMax.y - side_lengths.y;
			mMin.z = mParent.mMax.z - side_lengths.z;
			break;
		case 2: //-X/-Y/+Z
			mMax.x = mParent.mMin.x + side_lengths.x;
			mMax.y = mParent.mMin.y + side_lengths.y;
			mMax.z = mParent.mMax.z;
			mMin.x = mParent.mMin.x;
			mMin.y = mParent.mMin.y;
			mMin.z = mParent.mMax.z - side_lengths.z;
			break;
		case 3: //+X/-Y/+Z
			mMax.x = mParent.mMax.x;
			mMax.y = mParent.mMin.y + side_lengths.y;
			mMax.z = mParent.mMax.z;
			mMin.x = mParent.mMax.x - side_lengths.x;
			mMin.y = mParent.mMin.y;
			mMin.z = mParent.mMax.z - side_lengths.z;
			break;
		case 4: //+X/+Y/-Z
			mMax.x = mParent.mMax.x;
			mMax.y = mParent.mMax.y;
			mMax.z = mParent.mMin.z + side_lengths.z;
			mMin.x = mParent.mMax.x - side_lengths.x;
			mMin.y = mParent.mMax.y - side_lengths.y;
			mMin.z = mParent.mMin.z;
			break;
		case 5: //-X/+Y/-Z
			mMax.x = mParent.mMin.x + side_lengths.x;
			mMax.y = mParent.mMax.y;
			mMax.z = mParent.mMin.z + side_lengths.z;
			mMin.x = mParent.mMin.x;
			mMin.y = mParent.mMax.y - side_lengths.y;
			mMin.z = mParent.mMin.z;
			break;
		case 6: //-X/-Y/-Z
			mMin.setAllFrom(mParent.mMin);
			mMax.setAllFrom(Number3D.add(mMin, side_lengths));
			break;
		case 7: //+X/-Y/-Z
			mMax.x = mParent.mMax.x;
			mMax.y = mParent.mMin.y + side_lengths.y;
			mMax.z = mParent.mMin.z + side_lengths.z;
			mMin.x = mParent.mMax.x - side_lengths.x;
			mMin.y = mParent.mMin.y;
			mMin.z = mParent.mMin.z;
			break;
		default:
			return;
		}
		mTransformedMin.setAllFrom(mMin);
		mTransformedMax.setAllFrom(mMax);
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
		calculatePoints();
		calculateChildSideLengths();
	}

	protected void internalAddObject(IGraphNodeMember object) {
		//TODO: Implement a batch process for this to save excessive splitting/merging
		if (mSplit) {
			//Check if the object fits in our children
			int fits_in_child = -1;
			for (int j = 0; j < CHILD_COUNT; ++j) {
				if (mChildren[j].contains(object.getTransformedBoundingVolume())) {
					//If the member fits in this child, mark that child
					if (fits_in_child < 0) {
						fits_in_child = j;
					} else {
						//It fits in multiple children, leave it in parent
						RajLog.d("[" + this.getClass().getName() + "] Member: " + object + "fits in multiple children. Leaving in parent.");
						fits_in_child = -1;
						break;
					}
				}
			}
			if (fits_in_child >= 0) { //If a single child was marked, add the member to it
				mChildren[fits_in_child].addObject(object);
			} else {
				//It didn't fit in any of the children, so store it here
				addToMembers(object);
			}
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
		RajLog.d("[" + this.getClass().getName() + "] Parent member count before: " + mMembers.size());
		//Populate child array
		for (int i = 0; i < CHILD_COUNT; ++i) {
			mChildren[i] = new Octree(this, mMergeThreshold,
					mSplitThreshold, mShrinkThreshold, mGrowThreshold, mOverlap);
			mChildren[i].mBoundingColor = 0xFF00FF00; //COLORS[i];
			mChildren[i].setOctant(i, mChildLengths);
		}
		int member_count = mMembers.size();
		//Keep a list of members we have removed
		ArrayList<IGraphNodeMember> removed = new ArrayList<IGraphNodeMember>();
		for (int i = 0; i < member_count; ++i) {
			int fits_in_child = -1;
			IGraphNodeMember member = mMembers.get(i);
			for (int j = 0; j < CHILD_COUNT; ++j) {
				if (mChildren[j].contains(member.getTransformedBoundingVolume())) {
					//If the member fits in this child, mark that child
					if (fits_in_child < 0) {
						fits_in_child = j;
					} else {
						//It fits in multiple children, leave it in parent
						RajLog.d("[" + this.getClass().getName() + "] Member: " + member + "fits in multiple children. Leaving in parent.");
						fits_in_child = -1;
						break;
					}
				}
			}
			if (fits_in_child >= 0) { //If a single child was marked, add the member to it
				mChildren[fits_in_child].addObject(member);
				removed.add(member); //Mark the member for removal from parent
			}
		}
		//Now remove all of the members marked for removal
		mMembers.removeAll(removed);
		for (int i = 0; i < CHILD_COUNT; ++i) {
			RajLog.d("[" + this.getClass().getName() + "] Child " + i + " member count: " + mChildren[i].mMembers.size());
		}
		RajLog.d("[" + this.getClass().getName() + "] Parent member count after: " + mMembers.size());
		mSplit = true; //Flag that we have split
	}

	/**
	 * Merges this child nodes into their parent node. 
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
			Number3D test_against_min = null;
			Number3D test_against_max = null;
			if (volume == null) {
				ATransformable3D object = (ATransformable3D) member;
				test_against_min = object.getPosition();
				test_against_max = test_against_min;
			} else {
				if (volume instanceof BoundingBox) {
					BoundingBox bb = (BoundingBox) volume;
					test_against_min = bb.getTransformedMin();
					test_against_max = bb.getTransformedMax();
				} else if (volume instanceof BoundingSphere) {
					BoundingSphere bs = (BoundingSphere) volume;
					Number3D bs_position = bs.getPosition();
					float radius = bs.getScaledRadius();
					Number3D rad = new Number3D();
					rad.setAll(radius, radius, radius);
					test_against_min = Number3D.subtract(bs_position, rad);
					test_against_max = Number3D.add(bs_position, rad);
				} else {
					RajLog.e("[" + this.getClass().getName() + "] Received a bounding box of unknown type.");
					throw new IllegalArgumentException("Received a bounding box of unknown type."); 
				}
			}
			if (test_against_min != null && test_against_max != null) {
				if(test_against_min.x < min.x) min.x = test_against_min.x;
				if(test_against_min.y < min.y) min.y = test_against_min.y;
				if(test_against_min.z < min.z) min.z = test_against_min.z;
				if(test_against_max.x > max.x) max.x = test_against_max.x;
				if(test_against_max.y > max.y) max.y = test_against_max.y;
				if(test_against_max.z > max.z) max.z = test_against_max.z;
			}
		}
		RajLog.d("[" + this.getClass().getName() + "] New root min/max should be: " + min + "/" + max);
		mMin.setAllFrom(min);
		mMax.setAllFrom(max);
		mTransformedMin.setAllFrom(min);
		mTransformedMax.setAllFrom(max);
		calculatePoints();
		calculateChildSideLengths();
		ArrayList<IGraphNodeMember> members = new ArrayList<IGraphNodeMember>();
		members.addAll(mMembers);
		members.addAll(mOutside);
		mMembers.clear();
		mOutside.clear();
		if (mSplit) {
			for (int i = 0; i < CHILD_COUNT; ++i) {
				mChildren[i].setOctant(i, mChildLengths);
			}
		}
		for (IGraphNodeMember member : members) {internalAddObject(member);}
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
		if (mSplit) {
			for (int i = 0; i < CHILD_COUNT; ++i) {
				RajLog.d("[" + this.getClass().getName() + "] " + mChildren[i].toString());
			}
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
		if (mMembers.size() == 0 && mOutside.size() == 0 && mParent == null) {return;}
		Matrix.setIdentityM(mMMatrix, 0);
		drawBoundingVolume(camera, projMatrix, vMatrix, mMMatrix);
		if (mSplit) {
			for (int i = 0; i < CHILD_COUNT; ++i) {
				mChildren[i].displayGraph(camera, projMatrix, vMatrix);
			}
		}
	}

	/*@Override
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
	}*/

	@Override
	public String toString() {
		return "Node member/outside count: " + mMembers.size() + "/" + mOutside.size();
	}

	/*
	 * (non-Javadoc)
	 * @see rajawali.scenegraph.IGraphNode#setListener(rajawali.scenegraph.ISceneGraphCallbacks)
	 */
	public void setListener(ISceneGraphCallbacks listener) {
		mListener = listener;
	}
}