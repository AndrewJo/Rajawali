package rajawali.scene;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import rajawali.BaseObject3D;
import rajawali.Camera;
import rajawali.animation.Animation3D;
import rajawali.materials.SimpleMaterial;
import rajawali.materials.SkyboxMaterial;
import rajawali.materials.TextureInfo;
import rajawali.primitives.Cube;
import rajawali.renderer.AFrameTask;
import rajawali.renderer.EmptyTask;
import rajawali.renderer.GroupTask;
import rajawali.renderer.RajawaliRenderer;
import rajawali.renderer.plugins.IRendererPlugin;
import rajawali.scenegraph.IGraphNode;
import rajawali.scenegraph.IGraphNode.GRAPH_TYPE;
import rajawali.scenegraph.IGraphNodeMember;
import rajawali.scenegraph.Octree;
import rajawali.util.ObjectColorPicker.ColorPickerInfo;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.opengl.GLES20;
import android.util.Log;

/**
 * This is the container class for scenes in Rajawali.
 * 
 * It is intended that children, lights, cameras and animations
 * will be added to this object and this object will be added
 * to the {@link RajawaliRenderer} instance.
 * 
 * @author Jared Woolston (jwoolston@tenkiv.com)
 */
public class RajawaliScene extends AFrameTask {
	
	protected final int GL_COVERAGE_BUFFER_BIT_NV = 0x8000;
	protected float mEyeZ = 4.0f;
	
	protected RajawaliRenderer mRenderer;
	
	protected float[] mVMatrix = new float[16];
	protected float[] mPMatrix = new float[16];
	
	protected float mRed, mBlue, mGreen, mAlpha;
	protected Cube mSkybox;
	/**
	* Temporary camera which will be switched to by the GL thread.
	* Guarded by {@link #mNextSkyboxLock}
	*/
	private Cube mNextSkybox;
	private final Object mNextSkyboxLock = new Object();
	protected TextureInfo mSkyboxTextureInfo;
	
	protected ColorPickerInfo mPickerInfo;
	protected boolean mReloadPickerInfo;
	protected boolean mUsesCoverageAa;
	protected boolean mEnableDepthBuffer = true;

	private List<BaseObject3D> mChildren;
	private List<Animation3D> mAnimations;
	private List<IRendererPlugin> mPlugins;
	
	/**
	* The camera currently in use.
	* Not thread safe for speed, should
	* only be used by GL thread (onDrawFrame() and render())
	* or prior to rendering such as initScene(). 
	*/
	protected Camera mCamera;
	private List<Camera> mCameras; //List of all cameras in the scene.
	/**
	* Temporary camera which will be switched to by the GL thread.
	* Guarded by {@link #mNextCameraLock}
	*/
	private Camera mNextCamera;
	private final Object mNextCameraLock = new Object();
	
	/**
	 * Frame task queue. Adding, removing or replacing members
	 * such as children, cameras, plugins, etc is now prohibited
	 * outside the use of this queue. The render thread will automatically
	 * handle the necessary operations at an appropriate time, ensuring 
	 * thread safety and general correct operation.
	 * 
	 * Guarded by itself
	 */
	private LinkedList<AFrameTask> mFrameTaskQueue;

	/**
	 * Default to not using scene graph. This is for backwards
	 * compatibility as well as efficiency for simple scenes.
	 * NOT THREAD SAFE, as it is not expected to be changed beyond
	 * initScene().
	 */
	protected boolean mUseSceneGraph = false;
	protected boolean mDisplaySceneGraph = false;
	protected IGraphNode mSceneGraph; //The scenegraph for this scene
	protected GRAPH_TYPE mSceneGraphType = GRAPH_TYPE.NONE; //The type of graph type for this scene.
	
	public RajawaliScene(RajawaliRenderer renderer) {
		mRenderer = renderer;
		mAlpha = 0;
		mAnimations = Collections.synchronizedList(new CopyOnWriteArrayList<Animation3D>());
		mChildren = Collections.synchronizedList(new CopyOnWriteArrayList<BaseObject3D>());
		mPlugins = Collections.synchronizedList(new CopyOnWriteArrayList<IRendererPlugin>());
		mCameras = Collections.synchronizedList(new CopyOnWriteArrayList<Camera>());
		mFrameTaskQueue = new LinkedList<AFrameTask>();
		
		mCamera = new Camera();
		mCamera.setZ(mEyeZ);
		mCameras = Collections.synchronizedList(new CopyOnWriteArrayList<Camera>());
		mCameras.add(mCamera);
	}
	
	public RajawaliScene(RajawaliRenderer renderer, GRAPH_TYPE type) {
		this(renderer);
		mSceneGraphType = type;
		initSceneGraph();
	}
	
	/**
	 * Automatically creates the specified scene graph type with that graph's default
	 * behavior. If you want to use a specific constructor you will need to override this
	 * method. 
	 */
	protected void initSceneGraph() {
		switch (mSceneGraphType) { //Contrived with only one type I know. For the future!
		case OCTREE:
			mSceneGraph = new Octree();
			break;
		default:
			break;
		}
	}
	
	/**
	* Sets the {@link Camera} currently being used to display the scene.
	* 
	* @param mCamera {@link Camera} object to display the scene with.
	*/
	public void setCamera(Camera camera) {
		synchronized (mNextCameraLock) {
			mNextCamera = camera;
		}
	}

	/**
	* Sets the {@link Camera} currently being used to display the scene.
	* 
	* @param camera Index of the {@link Camera} to use.
	*/
	public void setCamera(int camera) {
		setCamera(mCameras.get(camera));
	}

	/**
	* Fetches the {@link Camera} currently being used to display the scene.
	* Note that the camera is not thread safe so this should be used
	* with extreme caution.
	* 
	* @return {@link Camera} object currently used for the scene.
	* @see {@link RajawaliRenderer#mCamera}
	*/
	public Camera getCamera() {
		return this.mCamera;
	}

	/**
	* Fetches the specified {@link Camera}. 
	* 
	* @param camera Index of the {@link Camera} to fetch.
	* @return Camera which was retrieved.
	*/
	public Camera getCamera(int camera) {
		return mCameras.get(camera);
	}

	/**
	* Adds a {@link Camera} to the scene.
	* 
	* @param camera {@link Camera} object to add.
	* @return boolean True if the addition was successfully queued.
	*/
	public boolean addCamera(Camera camera) {
		return queueAddTask(camera);
	}
	
	/**
	 * Adds a {@link Collection} of {@link Camera} objects to the scene.
	 * 
	 * @param cameras {@link Collection} of {@link Camera} objects to add.
	 * @return boolean True if the addition was successfully queued.
	 */
	public boolean addCameras(Collection<Camera> cameras) {
		ArrayList<AFrameTask> tasks = new ArrayList<AFrameTask>(cameras);
		return queueAddAllTask(tasks);
	}
	
	/**
	 * Removes a {@link Camera} from the scene. 
	 * 
	 * @param camera {@link Camera} object to remove.
	 * @return boolean True if the removal was successfully queued.
	 */
	public boolean removeCamera(Camera camera) {
		return queueRemoveTask(camera);
	}

	/**
	* Replaces a {@link Camera} in the renderer at the specified location
	* in the list. This does not validate the index, so if it is not
	* contained in the list already, an exception will be thrown.
	* 
	* @param camera {@link Camera} object to add.
	* @param location Integer index of the camera to replace.
	* @param boolean True if the replacement was successfully queued.
	*/
	public boolean replaceCamera(Camera camera, int location) {
		return queueReplaceTask(location, camera);
	}
	
	/**
	* Replaces the specified {@link Camera} in the renderer with the
	* provided {@link Camera}.
	* 
	* @param oldCamera {@link Camera} object to be replaced.
	* @param newCamera {@link Camera} object replacing the old.
	* @param boolean True if the replacement was successfully queued.
	*/
	public boolean replaceCamera(Camera oldCamera, Camera newCamera) {
		return queueReplaceTask(oldCamera, newCamera);
	}

	/**
	* Adds a {@link Camera}, switching to it immediately
	* 
	* @param camera The {@link Camera} to add.
	* @return boolean True if the addition was successfully queued.
	*/
	public boolean addAndSwitchCamera(Camera camera) {
		boolean success = addCamera(camera);
		setCamera(camera);
		return success;
	}

	/**
	* Replaces a {@link Camera} at the specified index with an option to switch to it
	* immediately.
	* 
	* @param camera The {@link Camera} to add.
	* @param location The index of the camera to replace.
	* @return boolean True if the replacement was successfully queued.
	*/
	public boolean replaceAndSwitchCamera(Camera camera, int location) {
		boolean success = replaceCamera(camera, location);
		setCamera(camera);
		return success;
	}
	
	/**
	* Replaces the specified {@link Camera} in the renderer with the
	* provided {@link Camera}, switching immediately.
	* 
	* @param oldCamera {@link Camera} object to be replaced.
	* @param newCamera {@link Camera} object replacing the old.
	* @param boolean True if the replacement was successfully queued.
	*/
	public boolean replaceAndSwitchCamera(Camera oldCamera, Camera newCamera) {
		boolean success = queueReplaceTask(oldCamera, newCamera);
		setCamera(newCamera);
		return success;
	}
	
	/**
	 * Replaces a {@link BaseObject3D} at the specified index with a new one.
	 * 
	 * @param child {@link BaseObject3D} the new child.
	 * @param location The index of the child to replace.
	 * @return boolean True if the replacement was successfully queued.
	 */
	public boolean replaceChild(BaseObject3D child, int location) {
		return queueReplaceTask(location, child);
	}
	
	/**
	 * Replaces a specified {@link BaseObject3D} with a new one.
	 * 
	 * @param oldChild {@link BaseObject3D} the old child.
	 * @param newChild {@link BaseObject3D} the new child.
	 * @return boolean True if the replacement was successfully queued.
	 */
	public boolean replaceChild(BaseObject3D oldChild, BaseObject3D newChild) {
		return queueReplaceTask(oldChild, newChild);
	}
	
	/**
	 * Requests the addition of a child to the scene. The child
	 * will be added to the end of the list. 
	 * 
	 * @param child {@link BaseObject3D} child to be added.
	 * @return True if the child was successfully queued for addition.
	 */
	public boolean addChild(BaseObject3D child) {
		return queueAddTask(child);
	}
	
	/**
	 * Requests the addition of a {@link Collection} of children to the scene.
	 * 
	 * @param children {@link Collection} of {@link BaseObject3D} children to add.
	 * @return boolean True if the addition was successfully queued.
	 */
	public boolean addChildren(Collection<BaseObject3D> children) {
		ArrayList<AFrameTask> tasks = new ArrayList<AFrameTask>(children);
		return queueAddAllTask(tasks);
	}
	
	/**
	 * Requests the removal of a child from the scene.
	 * 
	 * @param child {@link BaseObject3D} child to be removed.
	 * @return boolean True if the child was successfully queued for removal.
	 */
	public boolean removeChild(BaseObject3D child) {
		return queueRemoveTask(child);
	}
	
	/**
	 * Requests the removal of all children from the scene.
	 * 
	 * @return boolean True if the clear was successfully queued.
	 */
	public boolean clearChildren() {
		return queueClearTask(AFrameTask.TYPE.OBJECT3D);
	}
	
	/**
	 * Register an animation to be managed by the scene. This is optional 
	 * leaving open the possibility to manage updates on Animations in your own implementation.
	 * 
	 * @param anim {@link Animation3D} to be registered.
	 * @return boolean True if the registration was queued successfully.
	 */
	public boolean registerAnimation(Animation3D anim) {
		return queueAddTask(anim);
	}
	
	/**
	 * Remove a managed animation. If the animation is not a member of the scene, 
	 * nothing will happen.
	 * 
	 * @param anim {@link Animation3D} to be unregistered.
	 * @return boolean True if the unregister was queued successfully.
	 */
	public boolean unregisterAnimation(Animation3D anim) {
		return queueRemoveTask(anim);
	}
	
	/**
	 * Replace an {@link Animation3D} with a new one.
	 * 
	 * @param oldAnim {@link Animation3D} the old animation.
	 * @param newAnim {@link Animation3D} the new animation.
	 * @return boolean True if the replacement task was queued successfully.
	 */
	public boolean replaceAnimation(Animation3D oldAnim, Animation3D newAnim) {
		return queueReplaceTask(oldAnim, newAnim);
	}
	
	/**
	 * Adds a {@link Collection} of {@link Animation3D} objects to the scene.
	 * 
	 * @param anims {@link Collection} containing the {@link Animation3D} objects to be added.
	 * @return boolean True if the addition was queued successfully.
	 */
	public boolean registerAnimations(Collection<Animation3D> anims) {
		ArrayList<AFrameTask> tasks = new ArrayList<AFrameTask>(anims);
		return queueAddAllTask(tasks);
	}
	
	/**
	 * Removes all {@link Animation3D} objects from the scene.
	 * 
	 * @return boolean True if the clear task was queued successfully.
	 */
	public boolean clearAnimations() {
		return queueClearTask(AFrameTask.TYPE.ANIMATION);
	}
	
	/**
	 * Creates a skybox with the specified single texture.
	 * 
	 * @param resourceId int Resouce id of the skybox texture.
	 */
	public void setSkybox(int resourceId) {
		synchronized (mCameras) {
			for (int i = 0, j = mCameras.size(); i < j; ++i)
				mCameras.get(i).setFarPlane(1000);
		}
		synchronized (mNextSkyboxLock) {
			mNextSkybox = new Cube(700, true, false);
			mNextSkybox.setDoubleSided(true);
			mSkyboxTextureInfo = mRenderer.getTextureManager().addTexture(BitmapFactory.decodeResource(
					mRenderer.getContext().getResources(), resourceId));
			SimpleMaterial material = new SimpleMaterial();
			material.addTexture(mSkyboxTextureInfo);
			mNextSkybox.setMaterial(material);
		}
	}

	/**
	 * Creates a skybox with the specified 6 textures. 
	 * 
	 * @param front int Resource id for the front face.
	 * @param right int Resource id for the right face.
	 * @param back int Resource id for the back face.
	 * @param left int Resource id for the left face.
	 * @param up int Resource id for the up face.
	 * @param down int Resource id for the down face.
	 */
	public void setSkybox(int front, int right, int back, int left, int up, int down) {
		synchronized (mCameras) {
			for (int i = 0, j = mCameras.size(); i < j; ++i)
				mCameras.get(i).setFarPlane(1000);
		}
		synchronized (mNextSkyboxLock) {
			mNextSkybox = new Cube(700, true);
			Context context = mRenderer.getContext();
			Bitmap[] textures = new Bitmap[6];
			textures[0] = BitmapFactory.decodeResource(context.getResources(), left);
			textures[1] = BitmapFactory.decodeResource(context.getResources(), right);
			textures[2] = BitmapFactory.decodeResource(context.getResources(), up);
			textures[3] = BitmapFactory.decodeResource(context.getResources(), down);
			textures[4] = BitmapFactory.decodeResource(context.getResources(), front);
			textures[5] = BitmapFactory.decodeResource(context.getResources(), back);

			mSkyboxTextureInfo = mRenderer.getTextureManager().addCubemapTextures(textures);
			SkyboxMaterial mat = new SkyboxMaterial();
			mat.addTexture(mSkyboxTextureInfo);
			mNextSkybox.setMaterial(mat);
		}
	}
	
	/**
	 * Updates the sky box textures with a single texture. 
	 * 
	 * @param resourceId int the resource id of the new texture.
	 */
	public void updateSkybox(int resourceId) {
		mRenderer.getTextureManager().updateTexture(mSkyboxTextureInfo, BitmapFactory.decodeResource(
				mRenderer.getContext().getResources(), resourceId));
	}
	
	/**
	 * Updates the sky box textures with 6 new resource ids. 
	 * 
	 * @param front int Resource id for the front face.
	 * @param right int Resource id for the right face.
	 * @param back int Resource id for the back face.
	 * @param left int Resource id for the left face.
	 * @param up int Resource id for the up face.
	 * @param down int Resource id for the down face.
	 */
	public void updateSkybox(int front, int right, int back, int left, int up, int down) {
		Context context = mRenderer.getContext();
		Bitmap[] textures = new Bitmap[6];
		textures[0] = BitmapFactory.decodeResource(context.getResources(), left);
		textures[1] = BitmapFactory.decodeResource(context.getResources(), right);
		textures[2] = BitmapFactory.decodeResource(context.getResources(), up);
		textures[3] = BitmapFactory.decodeResource(context.getResources(), down);
		textures[4] = BitmapFactory.decodeResource(context.getResources(), front);
		textures[5] = BitmapFactory.decodeResource(context.getResources(), back);

		mRenderer.getTextureManager().updateCubemapTextures(mSkyboxTextureInfo, textures);
	}
	
	public void requestColorPickingTexture(ColorPickerInfo pickerInfo) {
		mPickerInfo = pickerInfo;
	}
	
	/**
	 * Reloads this scene.
	 */
	public void reload() {
		reloadChildren();
		if(mSkybox != null)
			mSkybox.reload();
		reloadPlugins();
		mReloadPickerInfo = true;
	}
	
	/**
	 * Clears the scene of contents. Note that this is NOT the same as destroying the scene.
	 */
	public void clear() {
		if (mChildren.size() > 0) {
			queueClearTask(AFrameTask.TYPE.OBJECT3D);
		}
		if (mPlugins.size() > 0) {
			queueClearTask(AFrameTask.TYPE.PLUGIN);
		}
	}
	
	/**
	 * Is the object picking info?
	 * 
	 * @return boolean True if object picking is active.
	 */
	public boolean hasPickerInfo() {
		return (mPickerInfo != null);
	}
	
	public void render(double deltaTime) {
		performFrameTasks(); //Handle the task queue
		synchronized (mNextSkyboxLock) {
			//Check if we need to switch the skybox, and if so, do it.
			if (mNextSkybox != null) {
				mSkybox = mNextSkybox;
				mNextSkybox = null;
			}
		}
		synchronized (mNextCameraLock) { 
			//Check if we need to switch the camera, and if so, do it.
			if (mNextCamera != null) {
				mCamera = mNextCamera;
				mNextCamera = null;
				mCamera.setProjectionMatrix(mRenderer.getViewportWidth(), mRenderer.getViewportHeight());
			}
		}
		
		int clearMask = GLES20.GL_COLOR_BUFFER_BIT;

		ColorPickerInfo pickerInfo = mPickerInfo;

		if (pickerInfo != null) {
			if(mReloadPickerInfo) pickerInfo.getPicker().reload();
			mReloadPickerInfo = false;
			pickerInfo.getPicker().bindFrameBuffer();
			GLES20.glClearColor(1.0f, 1.0f, 1.0f, 1.0f);
		} else {
			GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
			GLES20.glClearColor(mRed, mGreen, mBlue, mAlpha);
		}

		if (mEnableDepthBuffer) {
			clearMask |= GLES20.GL_DEPTH_BUFFER_BIT;
			GLES20.glEnable(GLES20.GL_DEPTH_TEST);
			GLES20.glDepthFunc(GLES20.GL_LESS);
			GLES20.glDepthMask(true);
			GLES20.glClearDepthf(1.0f);
		}
		if (mUsesCoverageAa) {
			clearMask |= GL_COVERAGE_BUFFER_BIT_NV;
		}

		GLES20.glClear(clearMask);

		mVMatrix = mCamera.getViewMatrix();
		mPMatrix = mCamera.getProjectionMatrix();

		if (mSkybox != null) {
			GLES20.glDisable(GLES20.GL_DEPTH_TEST);
			GLES20.glDepthMask(false);

			mSkybox.setPosition(mCamera.getX(), mCamera.getY(), mCamera.getZ());
			mSkybox.render(mCamera, mPMatrix, mVMatrix, pickerInfo);

			if (mEnableDepthBuffer) {
				GLES20.glEnable(GLES20.GL_DEPTH_TEST);
				GLES20.glDepthMask(true);
			}
		}

		mCamera.updateFrustum(mPMatrix, mVMatrix); //update frustum plane
		
		// Update all registered animations
		synchronized (mAnimations) {
			for (int i = 0, j = mAnimations.size(); i < j; ++i) {
				Animation3D anim = mAnimations.get(i);
				if (anim.isPlaying())
					anim.update(deltaTime);
			}
		}

		synchronized (mChildren) {
			for (int i = 0, j = mChildren.size(); i < j; ++i) 
				mChildren.get(i).render(mCamera, mPMatrix, mVMatrix, pickerInfo);
		}

		if (mDisplaySceneGraph) {
			mSceneGraph.displayGraph(mCamera, mPMatrix, mVMatrix);
        }
		
		if (pickerInfo != null) {
			pickerInfo.getPicker().createColorPickingTexture(pickerInfo);
			pickerInfo.getPicker().unbindFrameBuffer();
			pickerInfo = null;
			mPickerInfo = null;
			render(deltaTime); //TODO Possible timing error here
		}

		synchronized (mPlugins) {
			for (int i = 0, j = mPlugins.size(); i < j; i++)
				mPlugins.get(i).render();
		}
	}
	
	/**
	 * Queue an addition task. The added object will be placed
	 * at the end of the renderer's list.
	 * 
	 * @param task {@link AFrameTask} to be added.
	 * @return boolean True if the task was successfully queued.
	 */
	public boolean queueAddTask(AFrameTask task) {
		task.setTask(AFrameTask.TASK.ADD);
		task.setIndex(AFrameTask.UNUSED_INDEX);
		return addTaskToQueue(task);
	}
	
	/**
	 * Queue an addition task. The added object will be placed
	 * at the specified index in the renderer's list, or the end
	 * if out of range. 
	 * 
	 * @param task {@link AFrameTask} to be added.
	 * @param index Integer index to place the object at.
	 * @return boolean True if the task was successfully queued.
	 */
	public boolean queueAddTask(AFrameTask task, int index) {
		task.setTask(AFrameTask.TASK.ADD);
		task.setIndex(index);
		return addTaskToQueue(task);
	}
	
	/**
	 * Queue a removal task. The removal will occur at the specified
	 * index, or at the end of the list if out of range.
	 * 
	 * @param type {@link AFrameTask.TYPE} Which list to remove from.
	 * @param index Integer index to remove the object at.
	 * @return boolean True if the task was successfully queued.
	 */
	protected boolean queueRemoveTask(AFrameTask.TYPE type, int index) {
		EmptyTask task = new EmptyTask(type);
		task.setTask(AFrameTask.TASK.REMOVE);
		task.setIndex(index);
		return addTaskToQueue(task);
	}
	
	/**
	 * Queue a removal task to remove the specified object.
	 * 
	 * @param task {@link AFrameTask} to be removed.
	 * @return boolean True if the task was successfully queued.
	 */
	protected boolean queueRemoveTask(AFrameTask task) {
		task.setTask(AFrameTask.TASK.REMOVE);
		task.setIndex(AFrameTask.UNUSED_INDEX);
		return addTaskToQueue(task);
	}
	
	/**
	 * Queue a replacement task to replace the object at the
	 * specified index with a new one. Replaces the object at
	 * the end of the list if index is out of range.
	 * 
	 * @param index Integer index of the object to replace.
	 * @param replacement {@link AFrameTask} the object replacing the old.
	 * @return boolean True if the task was successfully queued.
	 */
	protected boolean queueReplaceTask(int index, AFrameTask replacement) {
		EmptyTask task = new EmptyTask(replacement.getFrameTaskType());
		task.setTask(AFrameTask.TASK.REPLACE);
		task.setIndex(index);
		task.setNewObject(replacement);
		return addTaskToQueue(task);
	}
	
	/**
	 * Queue a replacement task to replace the specified object with the new one.
	 * 
	 * @param task {@link AFrameTask} the object to replace.
	 * @param replacement {@link AFrameTask} the object replacing the old.
	 * @return boolean True if the task was successfully queued.
	 */
	protected boolean queueReplaceTask(AFrameTask task, AFrameTask replacement) {
		task.setTask(AFrameTask.TASK.REPLACE);
		task.setIndex(AFrameTask.UNUSED_INDEX);
		task.setNewObject(replacement);
		return addTaskToQueue(task);
	}
	
	/**
	 * Queue an add all task to add all objects from the given collection.
	 * 
	 * @param collection {@link Collection} containing all the objects to add.
	 * @return boolean True if the task was successfully queued. 
	 */
	protected boolean queueAddAllTask(Collection<AFrameTask> collection) {
		GroupTask task = new GroupTask(collection);
		task.setTask(AFrameTask.TASK.ADD_ALL);
		task.setIndex(AFrameTask.UNUSED_INDEX);
		return addTaskToQueue(task);
	}
	
	/**
	 * Queue a remove all task which will clear the related list.
	 * 
	 * @param type {@link AFrameTask.TYPE} Which object list to clear (Cameras, BaseObject3D, etc)
	 * @return boolean True if the task was successfully queued.
	 */
	protected boolean queueClearTask(AFrameTask.TYPE type) {
		GroupTask task = new GroupTask(type);
		task.setTask(AFrameTask.TASK.REMOVE_ALL);
		task.setIndex(AFrameTask.UNUSED_INDEX);
		return addTaskToQueue(task);
	}
	
	/**
	 * Queue a remove all task which will remove all objects from the given collection
	 * from the related list.
	 * 
	 * @param collection {@link Collection} containing all the objects to be removed.
	 * @return boolean True if the task was successfully queued.
	 */
	protected boolean queueRemoveAllTask(Collection<AFrameTask> collection) { 
		GroupTask task = new GroupTask(collection);
		task.setTask(AFrameTask.TASK.REMOVE_ALL);
		task.setIndex(AFrameTask.UNUSED_INDEX);
		return addTaskToQueue(task);
	}
	
	/**
	 * Adds a task to the frame task queue.
	 * 
	 * @param task AFrameTask to be added.
	 * @return boolean True on successful addition to queue.
	 */
	private boolean addTaskToQueue(AFrameTask task) {
		synchronized (mFrameTaskQueue) {
			return mFrameTaskQueue.offer(task);
		}
	}
	
	/**
	 * Internal method for performing frame tasks. Should be called at the
	 * start of onDrawFrame() prior to render().
	 */
	private void performFrameTasks() {
		Log.v("Rajawali", "Performing frame tasks in scene with queue size: " + mFrameTaskQueue.size());
		synchronized (mFrameTaskQueue) {
			//Fetch the first task
			AFrameTask taskObject = mFrameTaskQueue.poll();
			while (taskObject != null) {
				AFrameTask.TASK task = taskObject.getTask();
				switch (task) {
				case NONE:
					//DO NOTHING
					return;
				case ADD:
					handleAddTask(taskObject);
					break;
				case ADD_ALL:
					handleAddAllTask(taskObject);
					break;
				case REMOVE:
					handleRemoveTask(taskObject);
					break;
				case REMOVE_ALL:
					handleRemoveAllTask(taskObject);
					break;
				case REPLACE:
					handleReplaceTask(taskObject);
					break;
				}
				//Retrieve the next task
				taskObject = mFrameTaskQueue.poll();
			}
		}
	}
	
	/**
	 * Internal method for handling replacement tasks.
	 * 
	 * @param task {@link AFrameTask} object to process.
	 */
	private void handleReplaceTask(AFrameTask task) {
		AFrameTask.TYPE type = task.getFrameTaskType();
		switch (type) {
		case ANIMATION:
			internalReplaceAnimation(task, (Animation3D) task.getNewObject(), task.getIndex());
			break;
		case CAMERA:
			internalReplaceCamera(task, (Camera) task.getNewObject(), task.getIndex());
			break;
		case LIGHT:
			//TODO: Handle light replacement
			break;
		case OBJECT3D:
			internalReplaceChild(task, (BaseObject3D) task.getNewObject(), task.getIndex());
			break;
		case PLUGIN:
			internalReplacePlugin(task, (IRendererPlugin) task.getNewObject(), task.getIndex());
			break;
		case TEXTURE:
			//TODO: Handle texture replacement
			break;
		default:
			break;
		}
	}

	/**
	 * Internal method for handling addition tasks.
	 * 
	 * @param task {@link AFrameTask} object to process.
	 */
	private void handleAddTask(AFrameTask task) {
		AFrameTask.TYPE type = task.getFrameTaskType();
		switch (type) {
		case ANIMATION:
			internalAddAnimation((Animation3D) task, task.getIndex());
			break;
		case CAMERA:
			internalAddCamera((Camera) task, task.getIndex());
			break;
		case LIGHT:
			//TODO: Handle light addition
			break;
		case OBJECT3D:
			internalAddChild((BaseObject3D) task, task.getIndex());
			break;
		case PLUGIN:
			internalAddPlugin((IRendererPlugin) task, task.getIndex());
			break;
		case TEXTURE:
			//TODO: Handle texture addition
			break;
		default:
			break;
		}
	}
	
	/**
	 * Internal method for handling removal tasks.
	 * 
	 * @param task {@link AFrameTask} object to process.
	 */
	private void handleRemoveTask(AFrameTask task) {
		AFrameTask.TYPE type = task.getFrameTaskType();
		switch (type) {
		case ANIMATION:
			internalRemoveAnimation((Animation3D) task, task.getIndex());
			break;
		case CAMERA:
			internalRemoveCamera((Camera) task, task.getIndex());
			break;
		case LIGHT:
			//TODO: Handle light removal
			break;
		case OBJECT3D:
			internalRemoveChild((BaseObject3D) task, task.getIndex());
			break;
		case PLUGIN:
			internalRemovePlugin((IRendererPlugin) task, task.getIndex());
			break;
		case TEXTURE:
			//TODO: Handle texture removal
			break;
		default:
			break;
		}
	}
	
	/**
	 * Internal method for handling add all tasks.
	 * 
	 * @param task {@link AFrameTask} object to process.
	 */
	private void handleAddAllTask(AFrameTask task) {
		GroupTask group = (GroupTask) task;
		AFrameTask[] tasks = (AFrameTask[]) group.getCollection().toArray();
		AFrameTask.TYPE type = tasks[0].getFrameTaskType();
		int i = 0;
		int j = tasks.length;
		switch (type) {
		case ANIMATION:
			for (i = 0; i < j; ++i) {
				internalAddAnimation((Animation3D) tasks[i], AFrameTask.UNUSED_INDEX);
			}
			break;
		case CAMERA:
			for (i = 0; i < j; ++i) {
				internalAddCamera((Camera) tasks[i], AFrameTask.UNUSED_INDEX);
			}
			break;
		case LIGHT:
			//TODO: Handle light remove all
			break;
		case OBJECT3D:
			for (i = 0; i < j; ++i) {
				internalAddChild((BaseObject3D) tasks[i], AFrameTask.UNUSED_INDEX);
			}
			break;
		case PLUGIN:
			for (i = 0; i < j; ++i) {
				internalAddPlugin((IRendererPlugin) tasks[i], AFrameTask.UNUSED_INDEX);
			}
			break;
		case TEXTURE:
			//TODO: Handle texture remove all
			break;
		default:
			break;
		}
	}
	
	/**
	 * Internal method for handling remove all tasks.
	 * 
	 * @param task {@link AFrameTask} object to process.
	 */
	private void handleRemoveAllTask(AFrameTask task) {
		GroupTask group = (GroupTask) task;
		AFrameTask.TYPE type = group.getFrameTaskType();
		boolean clear = false;
		AFrameTask[] tasks = null;
		int i = 0, j = 0;
		if (type == null) {
			clear = true;
		} else {
			tasks = (AFrameTask[]) group.getCollection().toArray();
			type = tasks[0].getFrameTaskType();
			j = tasks.length;
		}
		switch (type) {
		case ANIMATION:
			if (clear) {
				internalClearAnimations();
			} else {
				for (i = 0; i < j; ++i) {
					internalRemoveAnimation((Animation3D) tasks[i], AFrameTask.UNUSED_INDEX);
				}
			}
			break;
		case CAMERA:
			if (clear) {
				internalClearCameras();
			} else {
				for (i = 0; i < j; ++i) {
					internalRemoveCamera((Camera) tasks[i], AFrameTask.UNUSED_INDEX);
				}
			}
			break;
		case LIGHT:
			//TODO: Handle light add all
			break;
		case OBJECT3D:
			if (clear) {
				internalClearChildren();
			} else {
				for (i = 0; i < j; ++i) {
					internalAddChild((BaseObject3D) tasks[i], AFrameTask.UNUSED_INDEX);
				}
			}
			break;
		case PLUGIN:
			if (clear) {
				internalClearPlugins();
			} else {
				for (i = 0; i < j; ++i) {
					internalAddPlugin((IRendererPlugin) tasks[i], AFrameTask.UNUSED_INDEX);
				}
			}
			break;
		case TEXTURE:
			//TODO: Handle texture add all
			break;
		default:
			break;
		}
	}
	
	/**
	 * Internal method for replacing a {@link Animation3D} object. If index is
	 * {@link AFrameTask.UNUSED_INDEX} then it will be used, otherwise the replace
	 * object is used. Should only be called through {@link #handleAddTask(AFrameTask)}
	 * 
	 * @param anim {@link AFrameTask} The new animation for the specified index.
	 * @param replace {@link Animation3D} The animation replacing the old animation.
	 * @param index integer index to effect. Set to {@link AFrameTask.UNUSED_INDEX} if not used.
	 */
	private void internalReplaceAnimation(AFrameTask anim, Animation3D replace, int index) {
		if (index != AFrameTask.UNUSED_INDEX) {
			mAnimations.set(index, replace);
		} else {
			mAnimations.set(mAnimations.indexOf(replace), (Animation3D) anim);
		}
	}
	
	/**
	 * Internal method for adding {@link Animation3D} objects.
	 * Should only be called through {@link #handleAddTask(AFrameTask)}
	 * 
	 * This takes an index for the addition, but it is pretty
	 * meaningless.
	 * 
	 * @param anim {@link Animation3D} to add.
	 * @param int index to add the animation at. 
	 */
	private void internalAddAnimation(Animation3D anim, int index) {
		if (index == AFrameTask.UNUSED_INDEX) {
			mAnimations.add(anim);
		} else {
			mAnimations.add(index, anim);
		}
	}
	
	/**
	 * Internal method for removing {@link Animation3D} objects.
	 * Should only be called through {@link #handleRemoveTask(AFrameTask)}
	 * 
	 * This takes an index for the removal. 
	 * 
	 * @param anim {@link Animation3D} to remove. If index is used, this is ignored.
	 * @param index integer index to remove the child at. 
	 */
	private void internalRemoveAnimation(Animation3D anim, int index) {
		if (index == AFrameTask.UNUSED_INDEX) {
			mAnimations.remove(anim);
		} else {
			mAnimations.remove(index);
		}
	}
	
	/**
	 * Internal method for removing all {@link Animation3D} objects.
	 * Should only be called through {@link #handleRemoveAllTask(AFrameTask)}
	 */
	private void internalClearAnimations() {
		mAnimations.clear();
	}
	
	/**
	 * Internal method for replacing a {@link Camera}. If index is
	 * {@link AFrameTask.UNUSED_INDEX} then it will be used, otherwise the replace
	 * object is used. Should only be called through {@link #handleReplaceTask(AFrameTask)}
	 * 
	 * @param camera {@link Camera} The new camera for the specified index.
	 * @param replace {@link Camera} The camera replacing the old camera.
	 * @param index integer index to effect. Set to {@link AFrameTask.UNUSED_INDEX} if not used.
	 */
	private void internalReplaceCamera(AFrameTask camera, Camera replace, int index) {
		if (index != AFrameTask.UNUSED_INDEX) {
			mCameras.set(index, replace);
		} else {
			mCameras.set(mCameras.indexOf(replace), (Camera) camera);
		}
		//TODO: Handle camera replacement in scenegraph
	}
	
	/**
	 * Internal method for adding a {@link Camera}.
	 * Should only be called through {@link #handleAddTask(AFrameTask)}
	 * 
	 * This takes an index for the addition, but it is pretty
	 * meaningless.
	 * 
	 * @param camera {@link Camera} to add.
	 * @param int index to add the camera at. 
	 */
	private void internalAddCamera(Camera camera, int index) {
		if (index == AFrameTask.UNUSED_INDEX) {
			mCameras.add(camera);
		} else {
			mCameras.add(index, camera);
		}
		if (mSceneGraph != null) {
			//mSceneGraph.addObject(camera); //TODO: Uncomment
		}
	}
	
	/**
	 * Internal method for removing a {@link Camera}.
	 * Should only be called through {@link #handleRemoveTask(AFrameTask)}
	 * 
	 * This takes an index for the removal. 
	 * 
	 * NOTE: If there is only one camera and it is removed, bad things
	 * will happen.
	 * 
	 * @param camera {@link Camera} to remove. If index is used, this is ignored.
	 * @param index integer index to remove the camera at. 
	 */
	private void internalRemoveCamera(Camera camera, int index) {
		Camera cam = camera;
		if (index == AFrameTask.UNUSED_INDEX) {
			mCameras.remove(camera);
		} else {
			cam = mCameras.remove(index);
		}
		if (mCamera.equals(cam)) {
			//If the current camera is the one being removed,
			//switch to the new 0 index camera.
			mCamera = mCameras.get(0);
		}
		if (mSceneGraph != null) {
			//mSceneGraph.removeObject(camera); //TODO: Uncomment
		}
	}
	
	/**
	 * Internal method for removing all {@link Camera} from the camera list.
	 * Should only be called through {@link #handleRemoveAllTask(AFrameTask)}
	 * Note that this will re-add the current camera.
	 */
	private void internalClearCameras() {
		if (mSceneGraph != null) {
			//mSceneGraph.removeAll(mCameras); //TODO: Uncomment
		}
		mCameras.clear();
		mCameras.add(mCamera);
	}	
	
	/**
	 * Creates a shallow copy of the internal cameras list. 
	 * 
	 * @return ArrayList containing the cameras.
	 */
	public ArrayList<Camera> getCamerasCopy() {
		ArrayList<Camera> list = new ArrayList<Camera>();
		list.addAll(mCameras);
		return list;
	}
	
	/**
	 * Retrieve the number of cameras.
	 * 
	 * @return The current number of cameras.
	 */
	public int getNumCameras() {
		//Thread safety deferred to the List
		return mCameras.size();
	}
	
	/**
	 * Internal method for replacing a {@link BaseObject3D} child. If index is
	 * {@link AFrameTask.UNUSED_INDEX} then it will be used, otherwise the replace
	 * object is used. Should only be called through {@link #handleReplaceTask(AFrameTask)}
	 * 
	 * @param child {@link BaseObject3D} The new child for the specified index.
	 * @param replace {@link BaseObject3D} The child replacing the old child.
	 * @param index integer index to effect. Set to {@link AFrameTask.UNUSED_INDEX} if not used.
	 */
	private void internalReplaceChild(AFrameTask child, BaseObject3D replace, int index) {
		if (index != AFrameTask.UNUSED_INDEX) {
			mChildren.set(index, replace);
		} else {
			mChildren.set(mChildren.indexOf(replace), (BaseObject3D) child);
		}
		//TODO: Handle child replacement in scene graph
	}
	
	/**
	 * Internal method for adding {@link BaseObject3D} children.
	 * Should only be called through {@link #handleAddTask(AFrameTask)}
	 * 
	 * This takes an index for the addition, but it is pretty
	 * meaningless.
	 * 
	 * @param child {@link BaseObject3D} to add.
	 * @param int index to add the child at. 
	 */
	private void internalAddChild(BaseObject3D child, int index) {
		Log.v("Rajawali", "Internal add task for child: " + child + " and index: " + index);
		if (index == AFrameTask.UNUSED_INDEX) {
			mChildren.add(child);
		} else {
			mChildren.add(index, child);
		}
		if (mSceneGraph != null) {
			mSceneGraph.addObject(child);
		}
	}
	
	/**
	 * Internal method for removing {@link BaseObject3D} children.
	 * Should only be called through {@link #handleRemoveTask(AFrameTask)}
	 * 
	 * This takes an index for the removal. 
	 * 
	 * @param child {@link BaseObject3D} to remove. If index is used, this is ignored.
	 * @param index integer index to remove the child at. 
	 */
	private void internalRemoveChild(BaseObject3D child, int index) {
		if (index == AFrameTask.UNUSED_INDEX) {
			mChildren.remove(child);
		} else {
			mChildren.remove(index);
		}
		if (mSceneGraph != null) {
			mSceneGraph.removeObject(child);
		}
	}
	
	/**
	 * Internal method for removing all {@link BaseObject3D} children.
	 * Should only be called through {@link #handleRemoveAllTask(AFrameTask)}
	 */
	private void internalClearChildren() {
		if (mSceneGraph != null) {
			mSceneGraph.removeObjects(new ArrayList<IGraphNodeMember>(mChildren));
		}
		mChildren.clear();
	}
	
	/**
	 * Creates a shallow copy of the internal child list. 
	 * 
	 * @return ArrayList containing the children.
	 */
	public ArrayList<BaseObject3D> getChildrenCopy() {
		ArrayList<BaseObject3D> list = new ArrayList<BaseObject3D>();
		list.addAll(mChildren);
		return list;
	}

	/**
	 * Tests if the specified {@link BaseObject3D} is a child of the renderer.
	 * 
	 * @param child {@link BaseObject3D} to check for.
	 * @return boolean indicating child's presence as a child of the renderer.
	 */
	protected boolean hasChild(BaseObject3D child) {
		//Thread safety deferred to the List.
		return mChildren.contains(child);
	}
	
	/**
	 * Retrieve the number of children.
	 * 
	 * @return The current number of children.
	 */
	public int getNumChildren() {
		//Thread safety deferred to the List
		return mChildren.size();
	}

	/**
	 * Internal method for replacing a {@link IRendererPlugin}. If index is
	 * {@link AFrameTask.UNUSED_INDEX} then it will be used, otherwise the replace
	 * object is used. Should only be called through {@link #handleReplaceTask(AFrameTask)}
	 * 
	 * @param plugin {@link IRendererPlugin} The new plugin for the specified index.
	 * @param replace {@link IRendererPlugin} The plugin replacing the old plugin.
	 * @param index integer index to effect. Set to {@link AFrameTask.UNUSED_INDEX} if not used.
	 */
	private void internalReplacePlugin(AFrameTask plugin, IRendererPlugin replace, int index) {
		if (index != AFrameTask.UNUSED_INDEX) {
			mPlugins.set(index, replace);
		} else {
			mPlugins.set(mPlugins.indexOf(replace), (IRendererPlugin) plugin);
		}
		//TODO: Handle replace plugins
	}
	
	/**
	 * Internal method for adding {@link IRendererPlugin} renderer.
	 * Should only be called through {@link #handleAddTask(AFrameTask)}
	 * 
	 * This takes an index for the addition, but it is pretty
	 * meaningless.
	 * 
	 * @param plugin {@link IRendererPlugin} to add.
	 * @param int index to add the child at. 
	 */
	private void internalAddPlugin(IRendererPlugin plugin, int index) {
		if (index == AFrameTask.UNUSED_INDEX) {
			mPlugins.add(plugin);
		} else {
			mPlugins.add(index, plugin);
		}
	}
	
	/**
	 * Internal method for removing {@link IRendererPlugin} renderer.
	 * Should only be called through {@link #handleRemoveTask(AFrameTask)}
	 * 
	 * This takes an index for the removal. 
	 * 
	 * @param plugin {@link IRendererPlugin} to remove. If index is used, this is ignored.
	 * @param index integer index to remove the child at. 
	 */
	private void internalRemovePlugin(IRendererPlugin plugin, int index) {
		if (index == AFrameTask.UNUSED_INDEX) {
			mPlugins.remove(plugin);
		} else {
			mPlugins.remove(index);
		}
	}
	
	/**
	 * Internal method for removing all {@link IRendererPlugin} renderers.
	 * Should only be called through {@link #handleRemoveAllTask(AFrameTask)}
	 */
	private void internalClearPlugins() {
		mPlugins.clear();
	}
	
	/**
	 * Creates a shallow copy of the internal plugin list. 
	 * 
	 * @return ArrayList containing the plugins.
	 */
	public ArrayList<IRendererPlugin> getPluginsCopy() {
		ArrayList<IRendererPlugin> list = new ArrayList<IRendererPlugin>();
		list.addAll(mPlugins);
		return list;
	}

	/**
	 * Tests if the specified {@link IRendererPlugin} is a plugin of the renderer.
	 * 
	 * @param plugin {@link IRendererPlugin} to check for.
	 * @return boolean indicating plugin's presence as a plugin of the renderer.
	 */
	protected boolean hasPlugin(IRendererPlugin plugin) {
		//Thread safety deferred to the List.
		return mPlugins.contains(plugin);
	}
	
	/**
	 * Retrieve the number of plugins.
	 * 
	 * @return The current number of plugins.
	 */
	public int getNumPlugins() {
		//Thread safety deferred to the List
		return mPlugins.size();
	}
	
	/**
	 * Reload all the children
	 */
	private void reloadChildren() {
		synchronized (mChildren) {
			for (int i = 0, j = mChildren.size(); i < j; ++i)
				mChildren.get(i).reload();
		}
	}

	/**
	 * Reload all the plugins
	 */
	private void reloadPlugins() {
		synchronized (mPlugins) {
			for (int i = 0, j = mPlugins.size(); i < j; ++i)
				mPlugins.get(i).reload();
		}
	}

	/**
	 * Clears any references the scene is holding for its contents. This does
	 * not clear the items themselves as they may be held by some other scene.
	 */
	public void destroyScene() {
		queueClearTask(AFrameTask.TYPE.ANIMATION);
		queueClearTask(AFrameTask.TYPE.CAMERA);
		queueClearTask(AFrameTask.TYPE.LIGHT);
		queueClearTask(AFrameTask.TYPE.OBJECT3D);
		queueClearTask(AFrameTask.TYPE.PLUGIN);
	}
	
	/**
	 * Sets the background color of the scene.
	 * 
	 * @param red float red component (0-1.0f).
	 * @param green float green component (0-1.0f).
	 * @param blue float blue component (0-1.0f).
	 * @param alpha float alpha component (0-1.0f).
	 */
	public void setBackgroundColor(float red, float green, float blue, float alpha) {
		mRed = red;
		mGreen = green;
		mBlue = blue;
		mAlpha = alpha;
	}
	
	/**
	 * Sets the background color of the scene. 
	 * 
	 * @param color Android color integer.
	 */
	public void setBackgroundColor(int color) {
		setBackgroundColor(Color.red(color) / 255f, Color.green(color) / 255f, Color.blue(color) / 255f, Color.alpha(color) / 255f);
	}
	
	/**
	 * Retrieves the background color of the scene.
	 * 
	 * @return Android color integer.
	 */
	public int getBackgroundColor() {
		return Color.argb((int) (mAlpha*255f), (int) (mRed*255f), (int) (mGreen*255f), (int) (mBlue*255f));
	}
	
	/**
	 * Updates the projection matrix of the current camera for new view port dimensions.
	 * 
	 * @param width int the new viewport width in pixels.
	 * @param height in the new viewport height in pixes.
	 */
	public void updateProjectionMatrix(int width, int height) {
		mCamera.setProjectionMatrix(width, height);
	}
	
	public void setUsesCoverageAa(boolean value) {
		mUsesCoverageAa = value;
	}
	
	/**
	 * Set if the scene graph should be displayed. How it is 
	 * displayed is left to the implimentation of the graph.
	 * 
	 * @param display If true, the scene graph will be displayed.
	 */
	public void displaySceneGraph(boolean display) {
		mDisplaySceneGraph = display;
	}

	/**
	 * Retrieve the number of triangles this scene contains.
	 * 
	 * @return int the total triangle count for the scene.
	 */
	public int getNumTriangles() {
		int triangleCount = 0;
		ArrayList<BaseObject3D> children = getChildrenCopy();
		for (int i = 0, j = children.size(); i < j; i++) {
			if (children.get(i).getGeometry() != null && children.get(i).getGeometry().getVertices() != null && children.get(i).isVisible())
				triangleCount += children.get(i).getGeometry().getVertices().limit() / 9;
		}
		return triangleCount;
	}

	@Override
	public TYPE getFrameTaskType() {
		return AFrameTask.TYPE.SCENE;
	}
}
