package rajawali.renderer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import rajawali.BaseObject3D;
import rajawali.Camera;
import rajawali.animation.Animation3D;
import rajawali.filters.IPostProcessingFilter;
import rajawali.materials.AMaterial;
import rajawali.materials.TextureManager;
import rajawali.math.Number3D;
import rajawali.scene.RajawaliScene;
import rajawali.util.FPSUpdateListener;
import rajawali.util.RajLog;
import rajawali.visitors.INode;
import rajawali.visitors.INodeVisitor;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLU;
import android.opengl.Matrix;
import android.os.SystemClock;
import android.service.wallpaper.WallpaperService;
import android.view.MotionEvent;
import android.view.WindowManager;

public class RajawaliRenderer implements GLSurfaceView.Renderer, INode {
	protected final int GL_COVERAGE_BUFFER_BIT_NV = 0x8000;

	protected Context mContext;

	protected float mEyeZ = 4.0f;
	protected float mFrameRate;
	protected double mLastMeasuredFPS;
	protected FPSUpdateListener mFPSUpdateListener;

	protected SharedPreferences preferences;

	protected int mViewportWidth, mViewportHeight;
	protected WallpaperService.Engine mWallpaperEngine;
	protected GLSurfaceView mSurfaceView;
	protected Timer mTimer;
	protected int mFrameCount;
	private long mStartTime = System.nanoTime();
	private long mLastRender;

	protected float[] mVMatrix = new float[16];
	protected float[] mPMatrix = new float[16];
	protected boolean mEnableDepthBuffer = true;
	protected static boolean mFogEnabled;
	protected static int mMaxLights = 1;

	protected TextureManager mTextureManager;
	protected PostProcessingRenderer mPostProcessingRenderer;

	/**
	 * Scene caching stores all textures and relevant OpenGL-specific
	 * data. This is used when the OpenGL context needs to be restored.
	 * The context typically needs to be restored when the application
	 * is re-activated or when a live wallpaper is rotated. 
	 */
	private boolean mSceneCachingEnabled;
	protected boolean mSceneInitialized;

	protected List<IPostProcessingFilter> mFilters;

	public static boolean supportsUIntBuffers = false;

	/**
	 * Frame task queue. Adding, removing or replacing members
	 * such as children, cameras, plugins, etc is now prohibited
	 * outside the use of this queue. The render thread will automatically
	 * handle the necessary operations at an appropriate time, ensuring 
	 * thread safety and general correct operation.
	 * 
	 * Guarded by itself
	 */
	private LinkedList<AFrameTask> mSceneQueue;
	private List<RajawaliScene> mScenes;
	private RajawaliScene mCurrentScene;
	
	protected Camera mCurrentCamera;
	
	public RajawaliRenderer(Context context) {
		RajLog.i("IMPORTANT: Rajawali's coordinate system has changed. It now reflects");
		RajLog.i("the OpenGL standard. Please invert the camera's z coordinate or");
		RajLog.i("call mCamera.setLookAt(0, 0, 0).");
		
		AMaterial.setLoaderContext(context);
		
		mContext = context;
		mFilters = Collections.synchronizedList(new CopyOnWriteArrayList<IPostProcessingFilter>());
		mPostProcessingRenderer = new PostProcessingRenderer(this);
		mFrameRate = getRefreshRate();
		mScenes = Collections.synchronizedList(new CopyOnWriteArrayList<RajawaliScene>());
		mSceneQueue = new LinkedList<AFrameTask>();
		mSceneCachingEnabled = true;
		mSceneInitialized = false;
		
		RajawaliScene defaultScene = new RajawaliScene(this);
		queueAddTask(defaultScene);
		mCurrentScene = defaultScene;
		mCurrentCamera = mCurrentScene.getCamera();
	}
	
	/**
	 * Register an animation to be managed by the renderer. This is optional leaving open the possibility to manage
	 * updates on Animations in your own implementation. Returns true on success.
	 * 
	 * @param anim
	 * @return
	 */
	public boolean registerAnimation(Animation3D anim) {
		return mCurrentScene.registerAnimation(anim);
	}
	
	/**
	 * Remove a managed animation. Returns true on success.
	 * 
	 * @param anim
	 * @return
	 */
	public boolean unregisterAnimation(Animation3D anim) {
		return mCurrentScene.unregisterAnimation(anim);
	}
	
	public boolean addChild(BaseObject3D child) {
		return mCurrentScene.addChild(child);
	}
	
	
	
	public RajawaliScene getCurrentScene() {
		return mCurrentScene;
	}
	
	public void onDrawFrame(GL10 glUnused) {
		performFrameTasks();
		render();
		++mFrameCount;
		if (mFrameCount % 50 == 0) {
			long now = System.nanoTime();
			double elapsedS = (now - mStartTime) / 1.0e9;
			double msPerFrame = (1000 * elapsedS / mFrameCount);
			mLastMeasuredFPS = 1000 / msPerFrame;
			//RajLog.d("ms / frame: " + msPerFrame + " - fps: " + mLastMeasuredFPS);

			mFrameCount = 0;
			mStartTime = now;

			if(mFPSUpdateListener != null)
				mFPSUpdateListener.onFPSUpdate(mLastMeasuredFPS);
		}
	}

	private void render() {
		final double deltaTime = (SystemClock.elapsedRealtime() - mLastRender) / 1000d;
		mLastRender = SystemClock.elapsedRealtime();
		
		mTextureManager.validateTextures();

		if (!mCurrentScene.hasPickerInfo()) {
			if (mFilters.size() == 0)
				GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
			else {
				if (mPostProcessingRenderer.isEnabled())
					mPostProcessingRenderer.bind();
			}
			int color = mCurrentScene.getBackgroundColor();
			GLES20.glClearColor(Color.red(color)/255f, Color.green(color)/255f, Color.blue(color)/255f, Color.alpha(color)/255f);
		}
		mCurrentScene.render(deltaTime);
		
		if (!mCurrentScene.hasPickerInfo() && mPostProcessingRenderer.isEnabled()) {
			mPostProcessingRenderer.render();
		}
	}

	public void onOffsetsChanged(float xOffset, float yOffset, float xOffsetStep, float yOffsetStep, int xPixelOffset, int yPixelOffset) {
	}

	public void onTouchEvent(MotionEvent event) {

	}		

	public void onSurfaceChanged(GL10 gl, int width, int height) {
		mViewportWidth = width;
		mViewportHeight = height;
		mCurrentScene.updateProjectionMatrix(width, height);
		GLES20.glViewport(0, 0, width, height);
	}


	/* Called when the OpenGL context is created or re-created. Don't set up your scene here,
	 * use initScene() for that.
	 * 
	 * @see rajawali.renderer.RajawaliRenderer#initScene
	 * @see android.opengl.GLSurfaceView.Renderer#onSurfaceCreated(javax.microedition.khronos.opengles.GL10, javax.microedition.khronos.egl.EGLConfig)
	 * 
	 */
	public void onSurfaceCreated(GL10 gl, EGLConfig config) {		
		supportsUIntBuffers = gl.glGetString(GL10.GL_EXTENSIONS).indexOf("GL_OES_element_index_uint") > -1;

		GLES20.glFrontFace(GLES20.GL_CCW);
		GLES20.glCullFace(GLES20.GL_BACK);

		if (!mSceneInitialized) {
			mTextureManager = new TextureManager(mContext);
			initScene();
		}

		if (!mSceneCachingEnabled) {
			mTextureManager.reset();
			clearScenes();
		} else if(mSceneCachingEnabled && mSceneInitialized) {
			mTextureManager.reload();
			reloadScenes();
		}
		if(mPostProcessingRenderer.isInitialized())
			mPostProcessingRenderer.reload();

		mSceneInitialized = true;
		startRendering();
	}
	
	protected void reloadScenes() {
		
	}
	
	protected void clearScenes() {
		EmptyTask task = new EmptyTask(AFrameTask.TYPE.SCENE);
		task.setIndex(AFrameTask.UNUSED_INDEX);
		task.setTask(AFrameTask.TASK.REMOVE_ALL);
		addTaskToQueue(task);
	}

	/**
	 * Scene construction should happen here, not in onSurfaceCreated()
	 */
	protected void initScene() {

	}

	public void startRendering() {
		mLastRender = SystemClock.elapsedRealtime();
		
		if (mTimer != null) {
			mTimer.cancel();
			mTimer.purge();
		}

		mTimer = new Timer();
		mTimer.schedule(new RequestRenderTask(), 0, (long) (1000 / mFrameRate));
	}

	/**
	 * Stop rendering the scene.
	 *
	 * @return true if rendering was stopped, false if rendering was already
	 *         stopped (no action taken)
	 */
	protected boolean stopRendering() {
		if (mTimer != null) {
			mTimer.cancel();
			mTimer.purge();
			mTimer = null;
			return true;
		}
		return false;
	}

	public void onVisibilityChanged(boolean visible) {
		if (!visible) {
			stopRendering();
		} else
			startRendering();
	}

	public void onSurfaceDestroyed() {
		stopRendering();
		if (mTextureManager != null)
			mTextureManager.reset();
		synchronized (mScenes) {
			for (int i = 0, j = mScenes.size(); i < j; ++i)
				mScenes.get(i).destroyScene();
		}
	}

	public void setSharedPreferences(SharedPreferences preferences) {
		this.preferences = preferences;
	}

	private class RequestRenderTask extends TimerTask {
		public void run() {
			if (mSurfaceView != null) {
				mSurfaceView.requestRender();
			}
		}
	}

	public Number3D unProject(float x, float y, float z) {
		x = mViewportWidth - x;
		y = mViewportHeight - y;

		float[] m = new float[16], mvpmatrix = new float[16],
				in = new float[4],
				out = new float[4];

		Matrix.multiplyMM(mvpmatrix, 0, mPMatrix, 0, mVMatrix, 0);
		Matrix.invertM(m, 0, mvpmatrix, 0);

		in[0] = (x / (float)mViewportWidth) * 2 - 1;
		in[1] = (y / (float)mViewportHeight) * 2 - 1;
		in[2] = 2 * z - 1;
		in[3] = 1;

		Matrix.multiplyMV(out, 0, m, 0, in, 0);

		if (out[3]==0)
			return null;

		out[3] = 1/out[3];
		return new Number3D(out[0] * out[3], out[1] * out[3], out[2] * out[3]);
	}

	public float getFrameRate() {
		return mFrameRate;
	}

	public void setFrameRate(int frameRate) {
		setFrameRate((float)frameRate);
	}

	public void setFrameRate(float frameRate) {
		this.mFrameRate = frameRate;
		if (stopRendering()) {
			// Restart timer with new frequency
			startRendering();
		}
	}

	public float getRefreshRate() {
		return ((WindowManager) mContext
				.getSystemService(Context.WINDOW_SERVICE))
				.getDefaultDisplay()
				.getRefreshRate();
	}

	public WallpaperService.Engine getEngine() {
		return mWallpaperEngine;
	}

	public void setEngine(WallpaperService.Engine engine) {
		this.mWallpaperEngine = engine;
	}

	public GLSurfaceView getSurfaceView() {
		return mSurfaceView;
	}

	public void setSurfaceView(GLSurfaceView surfaceView) {
		this.mSurfaceView = surfaceView;
	}

	public Context getContext() {
		return mContext;
	}

	public TextureManager getTextureManager() {
		return mTextureManager;
	}
	
	/**
	 * Adds a task to the frame task queue.
	 * 
	 * @param task AFrameTask to be added.
	 * @return boolean True on successful addition to queue.
	 */
	private boolean addTaskToQueue(AFrameTask task) {
		synchronized (mSceneQueue) {
			return mSceneQueue.offer(task);
		}
	}
	
	/**
	 * Internal method for performing frame tasks. Should be called at the
	 * start of onDrawFrame() prior to render().
	 */
	private void performFrameTasks() {
		synchronized (mSceneQueue) {
			//Fetch the first task
			AFrameTask taskObject = mSceneQueue.poll();
			while (taskObject != null) {
				if (taskObject.getFrameTaskType() != AFrameTask.TYPE.SCENE) {
					//Retrieve the next task
					taskObject = mSceneQueue.poll();
					continue;
				} else {
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
					taskObject = mSceneQueue.poll();
				}
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
			internalReplaceScene((RajawaliScene) task, (RajawaliScene) task.getReplaceObject(), task.getIndex());
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
			internalAddScene((RajawaliScene) task, task.getIndex());
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
		case SCENE:
			internalRemoveScene((RajawaliScene) task, task.getIndex());
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
		case SCENE:
			for (i = 0; i < j; ++i) {
				internalAddScene((RajawaliScene) tasks[i], AFrameTask.UNUSED_INDEX);
			}
			break;
		default:
			break;
		}
	}
	
	/**
	 * Internal method for handling add remove all tasks.
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
		case SCENE:
			if (clear) {
				internalClearScenes();
			} else {
				for (i = 0; i < j; ++i) {
					internalRemoveScene((RajawaliScene) tasks[i], AFrameTask.UNUSED_INDEX);
				}
			}
			break;
		default:
			break;
		}
	}
	
	/**
	 * Internal method for replacing a {@link RajawaliScene} object. If index is
	 * {@link AFrameTask.UNUSED_INDEX} then it will be used, otherwise the replace
	 * object is used. Should only be called through {@link #handleAddTask(AFrameTask)}
	 * 
	 * @param scene {@link RajawaliScene} The new scene for the specified index.
	 * @param replace {@link RajawaliScene} The animation to be replaced. Can be null if index is used.
	 * @param index integer index to effect. Set to {@link AFrameTask.UNUSED_INDEX} if not used.
	 */
	private void internalReplaceScene(RajawaliScene scene, RajawaliScene replace, int index) {
		if (index != AFrameTask.UNUSED_INDEX) {
			mScenes.set(index, scene);
		} else {
			mScenes.set(mScenes.indexOf(replace), scene);
		}
	}
	
	/**
	 * Internal method for adding {@link RajawaliScene} objects.
	 * Should only be called through {@link #handleAddTask(AFrameTask)}
	 * 
	 * This takes an index for the addition, but it is pretty
	 * meaningless.
	 * 
	 * @param scene {@link RajawaliScene} to add.
	 * @param int index to add the animation at. 
	 */
	private void internalAddScene(RajawaliScene scene, int index) {
		if (index == AFrameTask.UNUSED_INDEX) {
			mScenes.add(scene);
		} else {
			mScenes.add(index, scene);
		}
	}
	
	/**
	 * Internal method for removing {@link RajawaliScene} objects.
	 * Should only be called through {@link #handleRemoveTask(AFrameTask)}
	 * 
	 * This takes an index for the removal. 
	 * 
	 * @param anim {@link RajawaliScene} to remove. If index is used, this is ignored.
	 * @param index integer index to remove the child at. 
	 */
	private void internalRemoveScene(RajawaliScene scene, int index) {
		if (index == AFrameTask.UNUSED_INDEX) {
			mScenes.remove(scene);
		} else {
			mScenes.remove(index);
		}
	}
	
	/**
	 * Internal method for removing all {@link RajawaliScene} objects.
	 * Should only be called through {@link #handleRemoveAllTask(AFrameTask)}
	 */
	private void internalClearScenes() {
		mScenes.clear();
	}
	
	/**
	 * Queue an addition task. The added object will be placed
	 * at the end of the renderer's list.
	 * 
	 * @param task {@link AFrameTask} to be added.
	 * @return boolean True if the task was successfully queued.
	 */
	private boolean queueAddTask(AFrameTask task) {
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
	private boolean queueAddTask(AFrameTask task, int index) {
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
	private boolean queueRemoveTask(AFrameTask.TYPE type, int index) {
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
	private boolean queueRemoveTask(AFrameTask task) {
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
	 * @param replace {@link AFrameTask} the object to be replaced.
	 * @return boolean True if the task was successfully queued.
	 */
	private boolean queueReplaceTask(int index, AFrameTask replace) {
		EmptyTask task = new EmptyTask(replace.getFrameTaskType());
		task.setTask(AFrameTask.TASK.REPLACE);
		task.setIndex(index);
		task.setReplaceObject(replace);
		return addTaskToQueue(task);
	}
	
	/**
	 * Queue a replacement task to replace the specified object with the new one.
	 * 
	 * @param task {@link AFrameTask} the new object.
	 * @param replace {@link AFrameTask} the object to be replaced.
	 * @return boolean True if the task was successfully queued.
	 */
	private boolean queueReplaceTask(AFrameTask task, AFrameTask replace) {
		task.setTask(AFrameTask.TASK.REPLACE);
		task.setIndex(AFrameTask.UNUSED_INDEX);
		task.setReplaceObject(replace);
		return addTaskToQueue(task);
	}
	
	/**
	 * Queue an add all task to add all objects from the given collection.
	 * 
	 * @param collection {@link Collection} containing all the objects to add.
	 * @return boolean True if the task was successfully queued. 
	 */
	private boolean queueAddAllTask(Collection<AFrameTask> collection) {
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
	private boolean queueClearTask(AFrameTask.TYPE type) {
		GroupTask task = new GroupTask(type);
		task.setTask(AFrameTask.TASK.REMOVE_ALL);
		task.setIndex(AFrameTask.UNUSED_INDEX);
		return addTaskToQueue(task);
	}

	public void addPostProcessingFilter(IPostProcessingFilter filter) {
		if(mFilters.size() > 0)
			mFilters.remove(0);
		mFilters.add(filter);
		mPostProcessingRenderer.setEnabled(true);
		mPostProcessingRenderer.setFilter(filter);
	}

	public void accept(INodeVisitor visitor) {
		visitor.apply(this);
		//for (int i = 0; i < mChildren.size(); i++)
		//	mChildren.get(i).accept(visitor);
	}	

	public void removePostProcessingFilter(IPostProcessingFilter filter) {
		mFilters.remove(filter);
	}

	public void clearPostProcessingFilters() {
		mFilters.clear();
		mPostProcessingRenderer.unbind();
		mPostProcessingRenderer.destroy();
		mPostProcessingRenderer = new PostProcessingRenderer(this);
	}

	public int getViewportWidth() {
		return mViewportWidth;
	}

	public int getViewportHeight() {
		return mViewportHeight;
	}
	
	public static boolean isFogEnabled() {
		return mFogEnabled;
	}
	
	public void setFogEnabled(boolean enabled) {
		mFogEnabled = enabled;
		synchronized (mScenes) {
			for (int i = 0, j = mScenes.size(); i < j; ++i) {
				ArrayList<Camera> cams = mScenes.get(i).getCamerasCopy();
				for (int n = 0, k = mScenes.size(); n < k; ++n) {
					cams.get(n).setFogEnabled(enabled);
				}
			}
		}
	}

	public boolean getSceneInitialized() {
		return mSceneInitialized;
	}

	public void setSceneCachingEnabled(boolean enabled) {
		mSceneCachingEnabled = enabled;
	}

	public boolean getSceneCachingEnabled() {
		return mSceneCachingEnabled;
	}

	public static int getMaxLights() {
		return mMaxLights;
	}

	public static void setMaxLights(int maxLights) {
		RajawaliRenderer.mMaxLights = maxLights;
	}

	public void setFPSUpdateListener(FPSUpdateListener listener) {
		mFPSUpdateListener = listener;
	}

	public static int checkGLError(String message) {
		int error = GLES20.glGetError();
		if(error != GLES20.GL_NO_ERROR)
		{
			StringBuffer sb = new StringBuffer();
			if(message != null)
				sb.append("[").append(message).append("] ");
			sb.append("GLES20 Error: ");
			sb.append(GLU.gluErrorString(error));
			RajLog.e(sb.toString());
		}
		return error;
	}

	public void setUsesCoverageAa(boolean usesCoverageAa) {
		mCurrentScene.setUsesCoverageAa(usesCoverageAa);
	}
	
	public void setUsesCoverageAaAll(boolean usesCoverageAa) {
		synchronized (mScenes) {
			for (int i = 0, j = mScenes.size(); i < j; ++i) {
				mScenes.get(i).setUsesCoverageAa(usesCoverageAa);
			}
		}
	}
}
