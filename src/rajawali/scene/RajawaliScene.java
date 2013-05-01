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
import rajawali.filters.IPostProcessingFilter;
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
import rajawali.scenegraph.Octree;
import rajawali.util.ObjectColorPicker.ColorPickerInfo;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.opengl.GLES20;

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
	protected boolean mEnableDepthBuffer = true;
	
	protected float mRed, mBlue, mGreen, mAlpha;
	protected Cube mSkybox;
	protected TextureInfo mSkyboxTextureInfo;
	
	protected ColorPickerInfo mPickerInfo;

	protected List<IPostProcessingFilter> mFilters;
	protected boolean mReloadPickerInfo;
	
	protected boolean mUsesCoverageAa;

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
	/**
	* List of all cameras in the scene.
	*/
	private List<Camera> mCameras; 
	/**
	* Temporary camera which will be switched to by the GL thread.
	* Guarded by mNextCameraLock
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

	protected IGraphNode mSceneGraph;
	
	protected GRAPH_TYPE mSceneGraphType = GRAPH_TYPE.NONE;
	
	/**
	 * Default to not using scene graph. This is for backwards
	 * compatibility as well as efficiency for simple scenes.
	 * NOT THREAD SAFE, as it is not expected to be changed beyond
	 * initScene().
	 */
	protected boolean mUseSceneGraph = false;
	protected boolean mDisplaySceneGraph = false;
	
	public RajawaliScene(RajawaliRenderer renderer) {
		mRenderer = renderer;
		mAlpha = 0;
		mAnimations = Collections.synchronizedList(new CopyOnWriteArrayList<Animation3D>());
		mChildren = Collections.synchronizedList(new CopyOnWriteArrayList<BaseObject3D>());
		mPlugins = Collections.synchronizedList(new CopyOnWriteArrayList<IRendererPlugin>());
		mCameras = Collections.synchronizedList(new CopyOnWriteArrayList<Camera>());
		mFrameTaskQueue = new LinkedList<AFrameTask>();
		
		mCamera = new Camera();
		mCameras = Collections.synchronizedList(new CopyOnWriteArrayList<Camera>());
		addCamera(mCamera);
		mCamera.setZ(mEyeZ);
	}
	
	public RajawaliScene(RajawaliRenderer renderer, GRAPH_TYPE type) {
		this(renderer);
		mSceneGraphType = type;
		initSceneGraph();
	}
	
	/**
	 * Automatically creates the specified scen graph type with that graph's default
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
	
	public void requestColorPickingTexture(ColorPickerInfo pickerInfo) {
		mPickerInfo = pickerInfo;
	}
	
	public void reload() {
		reloadChildren();
		if(mSkybox != null)
			mSkybox.reload();
		reloadPlugins();
		mReloadPickerInfo = true;
	}
	
	public void clear() {
		if (mChildren.size() > 0) {
			queueClearTask(AFrameTask.TYPE.OBJECT3D);
		}
		if (mPlugins.size() > 0) {
			queueClearTask(AFrameTask.TYPE.PLUGIN);
		}
	}
	
	public boolean hasPickerInfo() {
		return (mPickerInfo != null);
	}
	
	public void render(double deltaTime) {
		synchronized (mNextCameraLock) { 
			//Check if we need to switch the camera, and if so, do it.
			if (mNextCamera != null) {
				mCamera = mNextCamera;
				mNextCamera = null;
				mCamera.setProjectionMatrix(mRenderer.getViewportWidth(), mRenderer.getViewportHeight());
			}
		}
		performFrameTasks();
		
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

		mCamera.updateFrustum(mPMatrix,mVMatrix); //update frustum plane
		
		// Update all registered animations //TODO Synchronize
		for (int i = 0; i < mAnimations.size(); i++) {
			Animation3D anim = mAnimations.get(i);
			if (anim.isPlaying())
				anim.update(deltaTime);
		}

		for (int i = 0; i < mChildren.size(); i++) 
			mChildren.get(i).render(mCamera, mPMatrix, mVMatrix, pickerInfo);

		if (mDisplaySceneGraph) {
			synchronized (mChildren) {
				mSceneGraph.displayGraph(mCamera, mPMatrix, mVMatrix);
			}
        }
		
		if (pickerInfo != null) {
			pickerInfo.getPicker().createColorPickingTexture(pickerInfo);
			pickerInfo.getPicker().unbindFrameBuffer();
			pickerInfo = null;
			mPickerInfo = null;
			render(deltaTime); //TODO posible error here
		}

		for (int i = 0, j = mPlugins.size(); i < j; i++)
			mPlugins.get(i).render();
	}
	
	/**
	* Sets the camera currently being used to display the scene.
	* 
	* @param mCamera Camera object to display the scene with.
	*/
	public void setCamera(Camera camera) {
		synchronized (mNextCameraLock) {
			mNextCamera = camera;
		}
	}

	/**
	* Sets the camera currently being used to display the scene.
	* 
	* @param camera Index of the camera to use.
	*/
	public void setCamera(int camera) {
		setCamera(mCameras.get(camera));
	}

	/**
	* Fetches the camera currently being used to display the scene.
	* Note that the camera is not thread safe so this should be used
	* with extreme caution.
	* 
	* @return Camera object currently used for the scene.
	* @see {@link RajawaliRenderer#mCamera}
	*/
	public Camera getCamera() {
		return this.mCamera;
	}

	/**
	* Fetches the specified camera. 
	* 
	* @param camera Index of the camera to fetch.
	* @return Camera which was retrieved.
	*/
	public Camera getCamera(int camera) {
		return mCameras.get(camera);
	}

	/**
	* Adds a camera to the renderer.
	* 
	* @param camera Camera object to add.
	* @return int The index the new camera was added at.
	*/
	public int addCamera(Camera camera) {
		mCameras.add(camera);
		return (mCameras.size() - 1);
	}

	/**
	* Replaces a camera in the renderer at the specified location
	* in the list. This does not validate the index, so if it is not
	* contained in the list already, an exception will be thrown.
	* 
	* @param camera Camera object to add.
	* @param location Integer index of the camera to replace.
	*/
	public void replaceCamera(Camera camera, int location) {
		mCameras.set(location, camera);
	}

	/**
	* Adds a camera with the option to switch to it immediately
	* 
	* @param camera The Camera to add.
	* @param useNow Boolean indicating if we should switch to this
	* camera immediately.
	* @return int The index the new camera was added at.
	*/
	public int addCamera(Camera camera, boolean useNow) {
		int index = addCamera(camera);
		if (useNow) setCamera(camera);
		return index;
	}

	/**
	* Replaces a camera at the specified index with an option to switch to it
	* immediately.
	* 
	* @param camera The Camera to add.
	* @param location The index of the camera to replace.
	* @param useNow Boolean indicating if we should switch to this
	* camera immediately.
	*/
	public void replaceCamera(Camera camera, int location, boolean useNow) {
		replaceCamera(camera, location);
		if (useNow) setCamera(camera);
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
	 * Requests the removal of a child from the scene.
	 * 
	 * @param child {@link BaseObject3D} child to be removed.
	 * @return True if the child was successfully queued for removal.
	 */
	public boolean removeChild(BaseObject3D child) {
		return queueRemoveTask(child);
	}
	
	/**
	 * Register an animation to be managed by the renderer. This is optional leaving open the possibility to manage
	 * updates on Animations in your own implementation. Returns true on success.
	 * 
	 * @param anim
	 * @return
	 */
	public boolean registerAnimation(Animation3D anim) {
		return queueAddTask(anim);
	}
	
	/**
	 * Remove a managed animation. Returns true on success.
	 * 
	 * @param anim
	 * @return
	 */
	public boolean unregisterAnimation(Animation3D anim) {
		return queueRemoveTask(anim);
	}
	
	/**
	 * Creates a skybox with the specified single texture.
	 * 
	 * @param resourceId int Resouce id of the skybox texture.
	 */
	public void setSkybox(int resourceId) {
		mCamera.setFarPlane(1000);
		mSkybox = new Cube(700, true, false);
		mSkybox.setDoubleSided(true);
		mSkyboxTextureInfo = mRenderer.getTextureManager().addTexture(BitmapFactory.decodeResource(
				mRenderer.getContext().getResources(), resourceId));
		SimpleMaterial material = new SimpleMaterial();
		material.addTexture(mSkyboxTextureInfo);
		mSkybox.setMaterial(material);
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
		mCamera.setFarPlane(1000);
		mSkybox = new Cube(700, true);
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
		mSkybox.setMaterial(mat);
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
	public boolean queueRemoveTask(AFrameTask.TYPE type, int index) {
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
	public boolean queueRemoveTask(AFrameTask task) {
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
	private boolean queueReplaceTask(int index, AFrameTask replacement) {
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
	private boolean queueReplaceTask(AFrameTask task, AFrameTask replacement) {
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
	public boolean queueAddAllTask(Collection<AFrameTask> collection) {
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
	public boolean queueClearTask(AFrameTask.TYPE type) {
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
	public boolean queueRemoveAllTask(Collection<AFrameTask> collection) { 
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
			mAnimations.set(index, (Animation3D) anim);
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
			mCameras.set(index, (Camera) camera);
		} else {
			mCameras.set(mCameras.indexOf(replace), (Camera) camera);
		}
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
	}
	
	/**
	 * Internal method for removing all {@link Camera} from the camera list.
	 * Should only be called through {@link #handleRemoveAllTask(AFrameTask)}
	 * Note that this will re-add the current camera.
	 */
	private void internalClearCameras() {
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
			mChildren.set(index, (BaseObject3D) child);
		} else {
			mChildren.set(mChildren.indexOf(replace), (BaseObject3D) child);
		}
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
		if (index == AFrameTask.UNUSED_INDEX) {
			mChildren.add(child);
		} else {
			mChildren.add(index, child);
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
	}
	
	/**
	 * Internal method for removing all {@link BaseObject3D} children.
	 * Should only be called through {@link #handleRemoveAllTask(AFrameTask)}
	 */
	private void internalClearChildren() {
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
			mPlugins.set(index, (IRendererPlugin) plugin);
		} else {
			mPlugins.set(mPlugins.indexOf(replace), (IRendererPlugin) plugin);
		}
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
	
	private void reloadChildren() {
		for (int i = 0; i < mChildren.size(); i++)
			mChildren.get(i).reload();
	}

	private void reloadPlugins() {
		for (int i = 0, j = mPlugins.size(); i < j; i++)
			mPlugins.get(i).reload();
	}
	
	/**
	 * Scene construction should happen here, not in onSurfaceCreated()
	 */
	protected void initScene() {

	}

	public void destroyScene() { //TODO FIX
		for (int i = 0; i < mChildren.size(); i++)
			mChildren.get(i).destroy();
		mChildren.clear();
		for (int i = 0, j = mPlugins.size(); i < j; i++)
			mPlugins.get(i).destroy();
		mPlugins.clear();
	}
	
	public void setBackgroundColor(float red, float green, float blue, float alpha) {
		mRed = red;
		mGreen = green;
		mBlue = blue;
		mAlpha = alpha;
	}
	
	public void setBackgroundColor(int color) {
		setBackgroundColor(Color.red(color) / 255f, Color.green(color) / 255f, Color.blue(color) / 255f, Color.alpha(color) / 255f);
	}
	
	public int getBackgroundColor() {
		return Color.argb((int) (mAlpha*255f), (int) (mRed*255f), (int) (mGreen*255f), (int) (mBlue*255f));
	}
	
	public void updateProjectionMatrix(int width, int height) {
		mCamera.setProjectionMatrix(width, height);
	}
	
	public void setUsesCoverageAa(boolean value) {
		mUsesCoverageAa = value;
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
