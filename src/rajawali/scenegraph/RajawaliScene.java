package rajawali.scenegraph;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import rajawali.BaseObject3D;
import rajawali.Camera;
import rajawali.animation.Animation3D;
import rajawali.filters.IPostProcessingFilter;
import rajawali.renderer.AFrameTask;
import rajawali.renderer.PostProcessingRenderer;
import rajawali.renderer.RajawaliRenderer;
import rajawali.renderer.plugins.IRendererPlugin;

/**
 * This is the container class for scenes in Rajawali.
 * 
 * It is intended that children, lights, cameras and animations
 * will be added to this object and this object will be added
 * to the {@link RajawaliRenderer} instance.
 * 
 * @author Jared Woolston (jwoolston@tenkiv.com)
 */
public class RajawaliScene {

	private List<BaseObject3D> mChildren;
	private List<Animation3D> mAnimations;
	protected List<IPostProcessingFilter> mFilters;

	protected boolean mSceneInitialized;
	/**
	 * Scene caching stores all textures and relevant OpenGL-specific
	 * data. This is used when the OpenGL context needs to be restored.
	 * The context typically needs to be restored when the application
	 * is re-activated or when a live wallpaper is rotated. 
	 */
	private boolean mSceneCachingEnabled;

	private List<IRendererPlugin> mPlugins;
	
	/**
	* List of all cameras in the scene.
	*/
	private List<Camera> mCameras;
	
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
	 * Guarded by mChildren.
	 */
	protected IGraphNode mSceneGraph;
	/**
	 * Default to not using scene graph. This is for backwards
	 * compatibility as well as efficiency for simple scenes.
	 * NOT THREAD SAFE, as it is not expected to be changed beyond
	 * initScene().
	 */
	protected boolean mUseSceneGraph = false;
	protected boolean mDisplaySceneGraph = false;
	
	public RajawaliScene() {
		mAnimations = Collections.synchronizedList(new CopyOnWriteArrayList<Animation3D>());
		mChildren = Collections.synchronizedList(new CopyOnWriteArrayList<BaseObject3D>());
		mFilters = Collections.synchronizedList(new CopyOnWriteArrayList<IPostProcessingFilter>());
		mPlugins = Collections.synchronizedList(new CopyOnWriteArrayList<IRendererPlugin>());
		mCameras = Collections.synchronizedList(new CopyOnWriteArrayList<Camera>());
		mSceneCachingEnabled = true;
		mFrameTaskQueue = new LinkedList<AFrameTask>();
	}
}
