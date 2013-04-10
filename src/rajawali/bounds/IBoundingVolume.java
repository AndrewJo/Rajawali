package rajawali.bounds;

import rajawali.BaseObject3D;
import rajawali.Camera;
import rajawali.Geometry3D;

public interface IBoundingVolume {
	
	public static final int DEFAULT_COLOR = 0xFFFFFF00;
	
	public void calculateBounds(Geometry3D geometry);
	public void drawBoundingVolume(Camera camera, float[] projMatrix, float[] vMatrix, float[] mMatrix);
	public void transform(float[] matrix);
	public boolean intersectsWith(IBoundingVolume boundingVolume);
	
	/**
	 * Does this volume fully contain the input volume.
	 * 
	 * @param boundingVolume Volume to check containment of.
	 * @return boolean result of containment test.
	 */
	public boolean contains(IBoundingVolume boundingVolume);
	
	/**
	 * Is this volume fully contained by the input volume.
	 * 
	 * @param boundingVolume Volume to check containment by.
	 * @return boolean result of containment test.
	 */
	public boolean isContainedBy(IBoundingVolume boundingVolume);
	public BaseObject3D getVisual();
	public void setBoundingColor(int color);
	public int getBoundingColor();
}
