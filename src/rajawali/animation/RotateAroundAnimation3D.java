package rajawali.animation;

import rajawali.math.Number3D;
import rajawali.math.Number3D.Axis;

public class RotateAroundAnimation3D extends Animation3D {
	protected final float PI_DIV_180 = 3.14159265f / 180;

	protected Number3D mCenter;
	protected float mDistance;
	protected Axis mAxis;
	
	public RotateAroundAnimation3D(Number3D center, Axis axis, float distance) {
		this(center, axis, distance, 1);
	}
	
	public RotateAroundAnimation3D(Number3D center, Axis axis, float distance, int direction) {
		super();
		mCenter = center;
		mDistance = distance;
		mAxis = axis;
		mDirection = direction;
	}
	
	@Override
	protected void applyTransformation(float interpolatedTime) {
		super.applyTransformation(interpolatedTime);
		float radians = 360f * interpolatedTime * PI_DIV_180;
		
		float cosVal = (float)Math.cos(radians) * mDistance;
		float sinVal = (float)Math.sin(radians) * mDistance;
		float x = 0;
		float y = 0;
		float z = 0;
		if(mAxis == Axis.Z) {
			x = mCenter.x + cosVal;
			y = mCenter.y + sinVal;
			z = mTransformable3D.getZ();
		} else if(mAxis == Axis.Y) {
			x = mCenter.x + cosVal;
			y = mTransformable3D.getY();
			z = mCenter.z + sinVal;
		} else if(mAxis == Axis.X) {
			x = mTransformable3D.getX();
			y = mCenter.y + cosVal;
			z = mCenter.z + sinVal;
		}
		mTransformable3D.setPosition(x, y, z);
	}
}
