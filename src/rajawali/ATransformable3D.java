package rajawali;

import rajawali.bounds.IBoundingVolume;
import rajawali.math.AngleAxis;
import rajawali.math.Number3D;
import rajawali.math.Number3D.Axis;
import rajawali.math.Quaternion;
import rajawali.renderer.AFrameTask;
import rajawali.scenegraph.IGraphNode;
import rajawali.scenegraph.IGraphNodeMember;
import android.opengl.Matrix;

public abstract class ATransformable3D extends AFrameTask implements IGraphNodeMember {
	protected Number3D mPosition, mRotation, mScale;
	protected Quaternion mOrientation;
	protected Quaternion mTmpOrientation;
	protected Number3D mRotationAxis;
	protected Number3D mAxisX, mAxisY, mAxisZ;
	protected boolean mRotationDirty;
	protected Number3D mLookAt;
	protected Number3D mTmpAxis, mTmpVec;
	protected boolean mIsCamera, mQuatWasSet;
	protected AngleAxis mAngleAxis; 
	
	protected IGraphNode mGraphNode;
	protected boolean mInsideGraph = false; //Default to being outside the graph
	
	public ATransformable3D() {
		mPosition = new Number3D();
		mRotation = new Number3D();
		mScale = new Number3D(1, 1, 1);
		mOrientation = new Quaternion();
		mTmpOrientation = new Quaternion();
		mAxisX = Number3D.getAxisVector(Axis.X);
		mAxisY = Number3D.getAxisVector(Axis.Y);
		mAxisZ = Number3D.getAxisVector(Axis.Z);
		mTmpAxis = new Number3D();
		mTmpVec = new Number3D();
		mAngleAxis = new AngleAxis();
		mRotationDirty = true;
	}
	
	public synchronized void setPosition(Number3D position) {
		mPosition.setAllFrom(position);
		if (mGraphNode != null) mGraphNode.updateObject(this);
	}

	public synchronized void setPosition(float x, float y, float z) {
		mPosition.setAll(x, y, z);
		if (mGraphNode != null) mGraphNode.updateObject(this);
	}

	public synchronized Number3D getPosition() {
		return mPosition;
	}
	
	public synchronized void setX(float x) {
		mPosition.x = x;
		if (mGraphNode != null) mGraphNode.updateObject(this);
	}

	public synchronized float getX() {
		return mPosition.x;
	}

	public synchronized void setY(float y) {
		mPosition.y = y;
		if (mGraphNode != null) mGraphNode.updateObject(this);
	}

	public synchronized float getY() {
		return mPosition.y;
	}

	public synchronized void setZ(float z) {
		mPosition.z = z;
		if (mGraphNode != null) mGraphNode.updateObject(this);
	}

	public synchronized float getZ() {
		return mPosition.z;
	}
	
	Number3D mTmpRotX = new Number3D();
	Number3D mTmpRotY = new Number3D();
	Number3D mTmpRotZ = new Number3D();
	float[] mLookAtMatrix = new float[16];

	public synchronized void setOrientation() {
		if(!mRotationDirty && mLookAt == null) return;

		mOrientation.setIdentity();
		if(mLookAt != null) {			
			mTmpRotZ.setAllFrom(mLookAt);
			mTmpRotZ.subtract(mPosition);
			mTmpRotZ.normalize();
			
			if(mTmpRotZ.length() == 0)
				mTmpRotZ.z = 1;
			
			mTmpRotX.setAllFrom(mAxisY);
			mTmpRotX.cross(mTmpRotZ);
			mTmpRotX.normalize();
			
			if(mTmpRotX.length() == 0) {
				mTmpRotZ.x += .0001f;
				mTmpRotX.cross(mTmpRotZ);
				mTmpRotX.normalize();
			}
			
			mTmpRotY.setAllFrom(mTmpRotZ);
			mTmpRotY.cross(mTmpRotX);
			
			Matrix.setIdentityM(mLookAtMatrix, 0);
			mLookAtMatrix[0] = mTmpRotX.x;
			mLookAtMatrix[1] = mTmpRotX.y;
			mLookAtMatrix[2] = mTmpRotX.z;
			mLookAtMatrix[4] = mTmpRotY.x;
			mLookAtMatrix[5] = mTmpRotY.y;
			mLookAtMatrix[6] = mTmpRotY.z;
			mLookAtMatrix[8] = mTmpRotZ.x;
			mLookAtMatrix[9] = mTmpRotZ.y;
			mLookAtMatrix[10] = mTmpRotZ.z;
		} else {
			mOrientation.multiply(mTmpOrientation.fromAngleAxis(mIsCamera ? mRotation.y : mRotation.y, mAxisY));
			mOrientation.multiply(mTmpOrientation.fromAngleAxis(mIsCamera ? mRotation.z : mRotation.z, mAxisZ));
			mOrientation.multiply(mTmpOrientation.fromAngleAxis(mIsCamera ? mRotation.x : mRotation.x, mAxisX));
			if(mIsCamera)
				mOrientation.inverseSelf();
		}
		//if (mGraphNode != null) mGraphNode.updateObject(this); //TODO: This may cause problems
	}

	public synchronized void rotateAround(Number3D axis, float angle) {
		rotateAround(axis, angle, true);
	}
	
 	public synchronized void rotateAround(Number3D axis, float angle, boolean append) {
 		if(append) {
 			mTmpOrientation.fromAngleAxis(angle, axis);
 			mOrientation.multiply(mTmpOrientation);
 		} else {
 			mOrientation.fromAngleAxis(angle, axis);
 		}
		mRotationDirty = false;
		if (mGraphNode != null) mGraphNode.updateObject(this);
	}
	
	public synchronized Quaternion getOrientation() {
		setOrientation(); // Force mOrientation to be recalculated
		return new Quaternion(mOrientation);
	}
	
	public synchronized void setOrientation(Quaternion quat) {
		mOrientation.setAllFrom(quat);
		mRotationDirty = false;
		if (mGraphNode != null) mGraphNode.updateObject(this);
	}
	
	public synchronized void setRotation(float rotX, float rotY, float rotZ) {
		mRotation.x = rotX;
		mRotation.y = rotY;
		mRotation.z = rotZ;
		mRotationDirty = true;
		if (mGraphNode != null) mGraphNode.updateObject(this);
	}
	
	public synchronized void setRotX(float rotX) {
		mRotation.x = rotX;
		mRotationDirty = true;
	}

	public synchronized float getRotX() {
		return mRotation.x;
	}

	public synchronized void setRotY(float rotY) {
		mRotation.y = rotY;
		mRotationDirty = true;
	}

	public synchronized float getRotY() {
		return mRotation.y;
	}

	public synchronized void setRotZ(float rotZ) {
		mRotation.z = rotZ;
		mRotationDirty = true;
	}

	public synchronized float getRotZ() {
		return mRotation.z;
	}
	
	public synchronized Number3D getRotation() {
		return mRotation;
	}

	public synchronized void setRotation(Number3D rotation) {
		mRotation.setAllFrom(rotation);
		mRotationDirty = true;
	}

	public synchronized void setScale(float scale) {
		mScale.x = scale;
		mScale.y = scale;
		mScale.z = scale;
		if (mGraphNode != null) mGraphNode.updateObject(this);
	}

	public synchronized void setScale(float scaleX, float scaleY, float scaleZ) {
		mScale.x = scaleX;
		mScale.y = scaleY;
		mScale.z = scaleZ;
		if (mGraphNode != null) mGraphNode.updateObject(this);
	}

	public synchronized void setScaleX(float scaleX) {
		mScale.x = scaleX;
		if (mGraphNode != null) mGraphNode.updateObject(this);
	}

	public synchronized float getScaleX() {
		return mScale.x;
	}

	public synchronized void setScaleY(float scaleY) {
		mScale.y = scaleY;
		if (mGraphNode != null) mGraphNode.updateObject(this);
	}

	public synchronized float getScaleY() {
		return mScale.y;
	}

	public synchronized void setScaleZ(float scaleZ) {
		mScale.z = scaleZ;
		if (mGraphNode != null) mGraphNode.updateObject(this);
	}

	public synchronized float getScaleZ() {
		return mScale.z;
	}
	
	public synchronized Number3D getScale() {
		return mScale;
	}

	public synchronized void setScale(Number3D scale) {
		mScale = scale;
		if (mGraphNode != null) mGraphNode.updateObject(this);
	}

	public synchronized Number3D getLookAt() {
		return mLookAt;
	}
	
	public synchronized void setLookAt(float x, float y, float z) {
		if(mLookAt == null) mLookAt = new Number3D();
		mLookAt.x = x;
		mLookAt.y = y;
		mLookAt.z = z;
		mRotationDirty = true;
	}
	
	public synchronized void setLookAt(Number3D lookAt) {
		if(lookAt == null) {
			mLookAt = null;
			return;
		}
		setLookAt(lookAt.x,  lookAt.y, lookAt.z);
	}

	/*
	 * (non-Javadoc)
	 * @see rajawali.scenegraph.IGraphNodeMember#setGraphNode(rajawali.scenegraph.IGraphNode)
	 */
	public synchronized void setGraphNode(IGraphNode node, boolean inside) {
		mGraphNode = node;
		mInsideGraph = inside;
	}
	
	/*
	 * (non-Javadoc)
	 * @see rajawali.scenegraph.IGraphNodeMember#getGraphNode()
	 */
	public synchronized IGraphNode getGraphNode() {
		return mGraphNode;
	}
	
	/*
	 * (non-Javadoc)
	 * @see rajawali.scenegraph.IGraphNodeMember#isInGraph()
	 */
	public synchronized boolean isInGraph() {
		return mInsideGraph;
	}
	
	/*
	 * (non-Javadoc)
	 * @see rajawali.scenegraph.IGraphNodeMember#getTransformedBoundingVolume()
	 */
	public synchronized IBoundingVolume getTransformedBoundingVolume() {
		return null;
	}
	
	/*
	 * (non-Javadoc)
	 * @see rajawali.scenegraph.IGraphNodeMember#getScenePosition()
	 */
	public synchronized Number3D getScenePosition() {
		return mPosition;
	}
}
