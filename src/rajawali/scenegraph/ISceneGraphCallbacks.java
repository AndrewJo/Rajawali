package rajawali.scenegraph;

/**
 * Callbacks which a class interested in managing the scenegraph
 * (typically the current RajawaliRenderer instance) must implement.
 * 
 * @author Jared Woolston (jwoolston@tenkiv.com)
 */
public interface ISceneGraphCallbacks {

	/**
	 * Updates the reference to the root node of the scene graph.
	 * 
	 * @param root The instance which is currently the root of the graph.
	 */
	public void updateRootNode(IGraphNode root);
}
