package rajawali.scenegraph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import rajawali.ATransformable3D;
import rajawali.Camera;
import rajawali.bounds.BoundingBox;
import rajawali.bounds.BoundingSphere;
import rajawali.bounds.IBoundingVolume;
import rajawali.math.Number3D;
import rajawali.util.RajLog;
import android.opengl.Matrix;
import android.util.Log;


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

	private static final int CHILD_COUNT = 8; //The number of child nodes used

	protected ISceneGraphCallbacks mListener; //The callback listener for this tree
	protected Octree mParent; //Parent partition;
	protected Octree[] mChildren; //Child partitions
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
	 *       0        | +X/+Y/+Z
	 *       1        | -X/+Y/+Z 
	 *       2        | -X/-Y/+Z
	 *       3        | +X/-Y/+Z
	 *       4        | +X/+Y/-Z
	 *       5        | -X/+Y/-Z
	 *       6        | -X/-Y/-Z
	 *       7        | +X/-Y/-Z
	 * </pre>
	 */
	protected int mOctant = -1;

	protected static final int[] COLORS = new int[]{
		0xFF8A2BE2, 0xFF0000FF, 0xFFD2691E, 0xFF008000,
		0xFFD2B000, 0xFF00FF00, 0xFFFF00FF, 0xFF40E0D0
	};

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
		calculateChildSideLengths();
		if (mSplit) {
			for (int i = 0; i < CHILD_COUNT; ++i) {
				mChildren[i].setOctant(i, mChildLengths);
			}
		}
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
		RajLog.d("[" + this.getClass().getName() + "] Adding object: " + object + " to members list in: " + this); 
		object.getTransformedBoundingVolume().setBoundingColor(mBoundingColor.get());
		object.setGraphNode(this);
		mMembers.add(object);
	}

	/**
	 * Returns a list of all members of this node and any decendent nodes.
	 * 
	 * @param shouldClear boolean indicating if the search should clear the lists.
	 * @return ArrayList of IGraphNodeMembers.
	 */
	protected ArrayList<IGraphNodeMember> getAllMembersRecursively(boolean shouldClear) {
		ArrayList<IGraphNodeMember> members = new ArrayList<IGraphNodeMember>();
		members.addAll(mMembers);
		members.addAll(mOutside);
		if (shouldClear) clear();
		if (mSplit) {
			for (int i = 0; i < CHILD_COUNT; ++i) {
				members.addAll(mChildren[i].mMembers);
				if (shouldClear) mChildren[i].clear();
			}
		}
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
		Octree new_bounds = mChildren[child];
		mMin.setAllFrom(new_bounds.mMin);
		mMax.setAllFrom(new_bounds.mMax);
		mTransformedMin.setAllFrom(mMin);
		mTransformedMax.setAllFrom(mMax);
		calculatePoints();
		calculateChildSideLengths();
	}

	/**
	 * Internal method for adding an object to the graph. This method will determine if
	 * it gets added to this node or moved to a child node.
	 * 
	 * @param object IGraphNodeMember to be added.
	 */
	protected void internalAddObject(IGraphNodeMember object) {
		//TODO: Implement a batch process for this to save excessive splitting/merging
		if (mSplit) {
			//Check if the object fits in our children
			int fits_in_child = -1;
			for (int i = 0; i < CHILD_COUNT; ++i) {
				if (mChildren[i].contains(object.getTransformedBoundingVolume())) {
					//If the member fits in this child, mark that child
					if (fits_in_child < 0) {
						fits_in_child = i;
					} else {
						//It fits in multiple children, leave it in parent
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

	protected void shrinkAddObject(IGraphNodeMember object) {
		if (contains(object.getTransformedBoundingVolume())) {
			internalAddObject(object);
		} else {
			mOutside.add(object);
			object.setGraphNode(null);
			object.getTransformedBoundingVolume().setBoundingColor(IBoundingVolume.DEFAULT_COLOR);
		}
	}

	/**
	 * Splits this node into {@link CHILD_COUNT} child nodes.
	 */
	protected void split() {
		RajLog.d("[" + this.getClass().getName() + "] Spliting node: " + this);
		//Populate child array
		for (int i = 0; i < CHILD_COUNT; ++i) {
			if (mChildren[i] == null) {
				mChildren[i] = new Octree(this, mMergeThreshold,
						mSplitThreshold, mShrinkThreshold, mGrowThreshold, mOverlap);
			}
			mChildren[i].mBoundingColor.set(COLORS[i]);
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
		mSplit = true; //Flag that we have split
	}

	/**
	 * Merges this child nodes into their parent node. 
	 */
	protected void merge() {
		RajLog.d("[" + this.getClass().getName() + "] Merge nodes called on node: " + this);
		if (mParent != null && mParent.canMerge()) {
			RajLog.d("[" + this.getClass().getName() + "] Parent can merge...passing call up.");
			mParent.merge();
		} else {
			if (mSplit) {
				for (int i = 0; i < CHILD_COUNT; ++i) {
					//Add all the members of all the children
					ArrayList<IGraphNodeMember> members = mChildren[i].getAllMembersRecursively(false);
					int members_count = members.size();
					for (int j = 0; j < members_count; ++j) {
						addToMembers(members.get(j));
					}
					mChildren[i].destroy();
					mChildren[i] = null;
				}
				mSplit = false;
			}
		}
	}

	/**
	 * Grows the tree.
	 */
	protected void grow() {
		RajLog.d("[" + this.getClass().getName() + "] Growing tree: " + this);
		Number3D min = new Number3D(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE);
		Number3D max = new Number3D(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE);
		//Get a full list of all the members, including members in the children
		ArrayList<IGraphNodeMember> members = getAllMembersRecursively(true);
		int members_count = members.size();
		for (int i = 0; i < members_count; ++i) {
			IBoundingVolume volume = members.get(i).getTransformedBoundingVolume();
			Number3D test_against_min = null;
			Number3D test_against_max = null;
			if (volume == null) {
				ATransformable3D object = (ATransformable3D) members.get(i);
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
		mMin.setAllFrom(min);
		mMax.setAllFrom(max);
		mTransformedMin.setAllFrom(min);
		mTransformedMax.setAllFrom(max);
		calculatePoints();
		calculateChildSideLengths();
		if (mSplit) {
			for (int i = 0; i < CHILD_COUNT; ++i) {
				mChildren[i].setOctant(i, mChildLengths);
			}
		}
		for (int i = 0; i < members_count; ++i) {
			internalAddObject(members.get(i));
		}
	}

	/**
	 * Shrinks the tree.
	 */
	protected void shrink() {
		RajLog.d("[" + this.getClass().getName() + "] Checking if tree should be shrunk.");
		int maxCount = 0;
		int index_max = -1;
		for (int i = 0; i < CHILD_COUNT; ++i) {
			if (mChildren[i].getObjectCount() > maxCount) {
				maxCount = mChildren[i].getObjectCount();
				index_max = i;
			}
		}
		if (index_max >= 0) {
			for (int i = 0; i < CHILD_COUNT; ++i) {
				if (i == index_max) {
					continue;
				} else if (mChildren[i].getObjectCount() == maxCount) {
					return;
				}
			}
			if (maxCount <= mShrinkThreshold) {
				RajLog.d("[" + this.getClass().getName() + "] Shrinking tree.");
				ArrayList<IGraphNodeMember> members = getAllMembersRecursively(true);
				int members_count = members.size();
				setBounds(index_max);
				if (mSplit) {
					for (int i = 0; i < CHILD_COUNT; ++i) { 
						//TODO: This is not always necessary depending on the object count
						mChildren[i].destroy();
						mChildren[i] = null;
					}
					mSplit = false;
				}
				for (int i = 0; i < members_count; ++i) {
					shrinkAddObject(members.get(i));
				}
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
		if (mSplit) {
			for (int i = 0; i < CHILD_COUNT; ++i) {
				count += mChildren[i].mMembers.size();
			}
		}
		return (count <= mMergeThreshold);
	}

	/*
	 * (non-Javadoc)
	 * @see rajawali.scenegraph.IGraphNode#addObject(rajawali.scenegraph.IGraphNodeMember)
	 */
	public void addObject(IGraphNodeMember object) {
		RajLog.d("[" + this.getClass().getName() + "] Adding object: " + object + " to octree."); 
		//TODO: Handle recursive add posibility

		if (mParent == null) {
			//We are the root node
			mBoundingColor.set(0xFFFF0000);
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
	}

	/*
	 * (non-Javadoc)
	 * @see rajawali.scenegraph.IGraphNode#removeObject(rajawali.ATransformable3D)
	 */
	public void removeObject(IGraphNodeMember object) {
		RajLog.d("[" + this.getClass().getName() + "] Removing object: " + object + " from octree.");
		//Handle recursive remove possibility
		//Retrieve the container object
		IGraphNode container = object.getGraphNode();
		if (container == null) {
			mOutside.remove(object);
		} else {
			if (container == this) {
				//If this is the container, process the removal
				//Remove the object from the members
				object.setGraphNode(null);
				mMembers.remove(object);
				if (canMerge() && mParent != null) {
					//If we can merge, do it (if we are the root node, we can't)
					merge();
				}
			} else {
				//Defer the removal to the container
				container.removeObject(object);
			}
		}
		if (mParent == null && mSplit) shrink(); //Try to shrink the tree
	}

	/*
	 * (non-Javadoc)
	 * @see rajawali.scenegraph.IGraphNode#updateObject(rajawali.ATransformable3D)
	 */
	public void updateObject(IGraphNodeMember object) {
		RajLog.d("[" + this.getClass().getName() + "] Updating object: " + object + 
				"[" + object.getClass().getName() + "] in octree.");
		if (mParent == null) {
			//We are the root node
			if (getObjectCount() == 1) { //If there is only one object, we should just follow it
				Log.i("Update", "Following single object...");
				setBounds(object);			
			} else {
				//There is more than just the one object.
				IGraphNode container = object.getGraphNode();
				if (container == null) {
					Log.i("Update", "Object was originally outside graph with no container.");
					if (contains(object.getTransformedBoundingVolume())) {
						Log.i("Update", "Object is now inside graph...");
						mOutside.remove(object);
						internalAddObject(object);
					} else {
						Log.i("Update", "Object is still outside graph...");
					}
				} else {
					Log.i("Update", "Object was originally inside graph with a container.");
					if (container.contains(object.getTransformedBoundingVolume())) {
						Log.i("Update", "Object is now inside graph...");
						mOutside.remove(object);
						internalAddObject(object);
					} else {
						Log.i("Update", "Object is still outside graph...");
					}
				}
			}
		} else {
			//We are a branch or leaf node
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
		mMembers.clear();
		mOutside.clear();
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

	/*
	 * (non-Javadoc)
	 * @see rajawali.scenegraph.IGraphNode#getObjectCount()
	 */
	public int getObjectCount() {
		int count = mMembers.size() + mOutside.size();
		if (mSplit) {
			for (int i = 0; i < CHILD_COUNT; ++i) {
				count += mChildren[i].getObjectCount();
			}
		}
		return count;
	}

	@Override
	public String toString() {
		return "Octant " + mOctant + " member/outside count: " + mMembers.size() + "/" + mOutside.size();
	}

	/*
	 * (non-Javadoc)
	 * @see rajawali.scenegraph.IGraphNode#setListener(rajawali.scenegraph.ISceneGraphCallbacks)
	 */
	public void setListener(ISceneGraphCallbacks listener) {
		mListener = listener;
	}
}