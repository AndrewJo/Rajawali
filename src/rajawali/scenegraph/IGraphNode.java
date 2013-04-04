package rajawali.scenegraph;

import rajawali.Camera;
import rajawali.bounds.IBoundingVolume;


/**
 * Generic interface allowing for the incorporation of scene graphs
 * to the rendering pipeline of Rajawali. To be a member of scene graphs
 * which implement this interface, an object must inherit from 
 * ATransformable3D. 
 * 
 * @author Jared Woolston (jwoolston@tenkiv.com)
 */
public interface IGraphNode {
	
	/**
	 * Adds an object to the scene graph. Implementations do not
	 * need to support online adjustment of the scene graph, and
	 * should clearly document what their add behavior is.
	 * 
	 * @param object BaseObject3D to be added to the graph.
	 */
	public void addObject(IGraphNodeMember object);
	
	/**
	 * Removes an object from the scene graph. Implementations do not
	 * need to support online adjustment of the scene graph, and should
	 * clearly document what their removal behavior is. 
	 * 
	 * @param object BaseObject3D to be removed from the graph.
	 */
	public void removeObject(IGraphNodeMember object);
	
	/**
	 * This should be called whenever an object has moved in the scene.
	 * Implementations should determine its new position in the graph.
	 * 
	 * @param object BaseObject3D to re-examine.
	 */
	public void updateObject(IGraphNodeMember object);
	
	/**
	 * Set the child addition behavior. Implementations are expected
	 * to document their default behavior.
	 * 
	 * @param recursive boolean Should the children be added recursively.
	 */
	public void addChildrenRecursively(boolean recursive);
	
	/**
	 * Set the child removal behavior. Implementations are expected to
	 * document their default behavior.
	 * 
	 * @param recursive boolean Should the children be removed recursively.
	 */
	public void removeChildrenRecursively(boolean recursive);
	
	/**
	 * Can be called to force a reconstruction of the scene graph
	 * with all added children. This is useful if the scene graph
	 * does not support online modification.
	 */
	public void rebuild();
	
	/**
	 * Can be called to remove all objects from the scene graph.
	 */
	public void clear();
	
	/**
	 * Called to cause the scene graph to determine which objects are
	 * contained (even partially) by the provided volume. How this is 
	 * done is left to the implementation.
	 * 
	 * @param volume IBoundingVolume to test visibility against.
	 */
	public void cullFromBoundingVolume(IBoundingVolume volume);
	
	/**
	 * Call this in the renderer to cause the scene graph to be
	 * displayed. It is up to the implementation to determine 
	 * the best way to accomplish this (draw volumes, write text,
	 * log statements, etc.)
	 * 
	 * @param display boolean indicating if the graph is to be displayed.
	 */
	public void displayGraph(Camera camera, float[] projMatrix, float[] vMatrix);
}