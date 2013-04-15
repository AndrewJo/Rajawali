package rajawali.scenegraph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.CopyOnWriteArrayList;

import rajawali.ATransformable3D;
import rajawali.Camera;
import rajawali.bounds.BoundingBox;
import rajawali.bounds.BoundingSphere;
import rajawali.bounds.IBoundingVolume;
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
public class Octree extends A_nAABBTree {

	private static final int CHILD_COUNT = 8; //The number of child nodes used

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

	/*
	 * (non-Javadoc)
	 * @see rajawali.scenegraph.AD_AABBTree#init()
	 */
	@Override
	protected void init() {
		//Pre-allocate storage here to favor modification speed
		mChildren = new Octree[CHILD_COUNT];
		mMembers = Collections.synchronizedList(new CopyOnWriteArrayList<IGraphNodeMember>());
		if (mParent == null) //mOutside should not be used for children, thus we want to force the Null pointer.
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
		Number3D min = mParent.getMin();
		Number3D max = mParent.getMax();
		switch (mOctant) {
		case 0: //+X/+Y/+Z
			mMax.setAllFrom(mParent.getMax());
			mMin.setAllFrom(Number3D.subtract(mMax, side_lengths));
			break;
		case 1: //-X/+Y/+Z 
			mMax.x = min.x + side_lengths.x;
			mMax.y = max.y;
			mMax.z = max.z;
			mMin.x = min.x;
			mMin.y = max.y - side_lengths.y;
			mMin.z = max.z - side_lengths.z;
			break;
		case 2: //-X/-Y/+Z
			mMax.x = min.x + side_lengths.x;
			mMax.y = min.y + side_lengths.y;
			mMax.z = max.z;
			mMin.x = min.x;
			mMin.y = min.y;
			mMin.z = max.z - side_lengths.z;
			break;
		case 3: //+X/-Y/+Z
			mMax.x = max.x;
			mMax.y = min.y + side_lengths.y;
			mMax.z = max.z;
			mMin.x = max.x - side_lengths.x;
			mMin.y = min.y;
			mMin.z = max.z - side_lengths.z;
			break;
		case 4: //+X/+Y/-Z
			mMax.x = max.x;
			mMax.y = max.y;
			mMax.z = min.z + side_lengths.z;
			mMin.x = max.x - side_lengths.x;
			mMin.y = max.y - side_lengths.y;
			mMin.z = min.z;
			break;
		case 5: //-X/+Y/-Z
			mMax.x = min.x + side_lengths.x;
			mMax.y = max.y;
			mMax.z = min.z + side_lengths.z;
			mMin.x = min.x;
			mMin.y = max.y - side_lengths.y;
			mMin.z = min.z;
			break;
		case 6: //-X/-Y/-Z
			mMin.setAllFrom(min);
			mMax.setAllFrom(Number3D.add(mMin, side_lengths));
			break;
		case 7: //+X/-Y/-Z
			mMax.x = max.x;
			mMax.y = min.y + side_lengths.y;
			mMax.z = min.z + side_lengths.z;
			mMin.x = max.x - side_lengths.x;
			mMin.y = min.y;
			mMin.z = min.z;
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
				((Octree) mChildren[i]).setOctant(i, mChildLengths);
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

	/*
	 * (non-Javadoc)
	 * @see rajawali.scenegraph.A_nAABBTree#destroy()
	 */
	@Override
	protected void destroy() {
		RajLog.d("[" + this.getClass().getName() + "] Destroying octree node: " + this);
		//TODO: Implement
	}

	/*
	 * (non-Javadoc)
	 * @see rajawali.scenegraph.A_nAABBTree#addToMembers(rajawali.scenegraph.IGraphNodeMember)
	 */
	@Override
	protected void addToMembers(IGraphNodeMember object) {
		RajLog.d("[" + this.getClass().getName() + "] Adding object: " + object + " to members list in: " + this); 
		object.getTransformedBoundingVolume().setBoundingColor(mBoundingColor.get());
		object.setGraphNode(this);
		mMembers.add(object);
	}
	
	/*
	 * (non-Javadoc)
	 * @see rajawali.scenegraph.A_nAABBTree#removeFromMembers(rajawali.scenegraph.IGraphNodeMember)
	 */
	@Override
	protected void removeFromMembers(IGraphNodeMember object) {
		RajLog.d("[" + this.getClass().getName() + "] Removing object: " + object + " from members list in: " + this);
		object.getTransformedBoundingVolume().setBoundingColor(IBoundingVolume.DEFAULT_COLOR);
		object.setGraphNode(null);
		mMembers.remove(object);
	}
	
	/*
	 * (non-Javadoc)
	 * @see rajawali.scenegraph.A_nAABBTree#addToOutside(rajawali.scenegraph.IGraphNodeMember)
	 */
	@Override
	protected void addToOutside(IGraphNodeMember object) {
		mOutside.add(object);
		object.setGraphNode(null);
		object.getTransformedBoundingVolume().setBoundingColor(IBoundingVolume.DEFAULT_COLOR);
	}

	/*
	 * (non-Javadoc)
	 * @see rajawali.scenegraph.A_nAABBTree#getAllMembersRecursively(boolean)
	 */
	@Override
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

	/*
	 * (non-Javadoc)
	 * @see rajawali.scenegraph.A_nAABBTree#internalAddObject(rajawali.scenegraph.IGraphNodeMember)
	 */
	@Override
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

	/*
	 * (non-Javadoc)
	 * @see rajawali.scenegraph.A_nAABBTree#shrinkAddObject(rajawali.scenegraph.IGraphNodeMember)
	 */
	@Override
	protected void shrinkAddObject(IGraphNodeMember object) {
		if (contains(object.getTransformedBoundingVolume())) {
			internalAddObject(object);
		} else {
			addToOutside(object);
		}
	}

	/*
	 * (non-Javadoc)
	 * @see rajawali.scenegraph.A_nAABBTree#split()
	 */
	@Override
	protected void split() {
		RajLog.d("[" + this.getClass().getName() + "] Spliting node: " + this);
		//Populate child array
		for (int i = 0; i < CHILD_COUNT; ++i) {
			if (mChildren[i] == null) {
				mChildren[i] = new Octree(this, mMergeThreshold,
						mSplitThreshold, mShrinkThreshold, mGrowThreshold, mOverlap);
			}
			mChildren[i].setBoundingColor(COLORS[i]);
			((Octree) mChildren[i]).setOctant(i, mChildLengths);
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

	/*
	 * (non-Javadoc)
	 * @see rajawali.scenegraph.A_nAABBTree#merge()
	 */
	@Override
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

	/*
	 * (non-Javadoc)
	 * @see rajawali.scenegraph.A_nAABBTree#grow()
	 */
	@Override
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
				((Octree) mChildren[i]).setOctant(i, mChildLengths);
			}
		}
		for (int i = 0; i < members_count; ++i) {
			internalAddObject(members.get(i));
		}
	}

	/*
	 * (non-Javadoc)
	 * @see rajawali.scenegraph.AD_AABBTree#shrink()
	 */
	@Override
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

	/*
	 * (non-Javadoc)
	 * @see rajawali.scenegraph.AD_AABBTree#canMerge()
	 */
	@Override
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
					addToOutside(object);
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
				removeFromMembers(object);
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
		/*RajLog.d("[" + this.getClass().getName() + "] Updating object: " + object + 
				"[" + object.getClass().getName() + "] in octree.");*/
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
		if (mMembers.size() == 0 && mParent == null) {return;}
		if (mOutside != null && mOutside.size() == 0) {return;}
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
		String str = "Octant " + mOctant + " member/outside count: " + mMembers.size() + "/";
		if (mOutside != null) {
			str = str + mOutside.size();
		} else {
			str = str + "NULL";
		}
		return str;
	}


	/*
	 * (non-Javadoc)
	 * @see rajawali.scenegraph.IGraphNode#contains(rajawali.bounds.IBoundingVolume)
	 */
	public boolean contains(IBoundingVolume boundingVolume) {
		if(!(boundingVolume instanceof BoundingBox)) return false;
		BoundingBox boundingBox = (BoundingBox)boundingVolume;
		Number3D otherMin = boundingBox.getTransformedMin();
		Number3D otherMax = boundingBox.getTransformedMax();
		Number3D min = mTransformedMin;
		Number3D max = mTransformedMax;		
		
		return (max.x >= otherMax.x) && (min.x <= otherMin.x) &&
				(max.y >= otherMax.y) && (min.y <= otherMin.y) &&
				(max.z >= otherMax.z) && (min.z <= otherMin.z);
	}

	/*
	 * (non-Javadoc)
	 * @see rajawali.scenegraph.IGraphNode#isContainedBy(rajawali.bounds.IBoundingVolume)
	 */
	public boolean isContainedBy(IBoundingVolume boundingVolume) {
		if(!(boundingVolume instanceof BoundingBox)) return false;
		BoundingBox boundingBox = (BoundingBox)boundingVolume;
		Number3D otherMin = boundingBox.getTransformedMin();
		Number3D otherMax = boundingBox.getTransformedMax();
		Number3D min = mTransformedMin;
		Number3D max = mTransformedMax;		
		
		return (max.x <= otherMax.x) && (min.x >= otherMin.x) &&
				(max.y <= otherMax.y) && (min.y >= otherMin.y) &&
				(max.z <= otherMax.z) && (min.z >= otherMin.z);
	}
}