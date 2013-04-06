package rajawali.scenegraph;

import rajawali.bounds.IBoundingVolume;
import rajawali.math.Number3D;

/**
 * Generic interface which any member of IGraphNode must
 * implement in order to be a part of the graph.
 * 
 * @author Jared Woolston (jwoolston@tenkiv.com)
 */
public interface IGraphNodeMember {

	/**
	 * Sets the node that this member is contained in.
	 * 
	 * @param node IGraphNode this member was placed inside.
	 */
	public void setGraphNode(IGraphNode node);
	
	/**
	 * Gets the node that this member is contained in.
	 * 
	 * @return IGraphNode this member was placed inside.
	 */
	public IGraphNode getGraphNode();
	
	/**
	 * Retrieve the bounding volume of this member.
	 * 
	 * @return IBoundingVolume which encloses this members "geometry."
	 */
	public IBoundingVolume getTransformedBoundingVolume();
}
