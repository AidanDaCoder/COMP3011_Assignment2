/* Authors:
*        1. Aidan Chan A1924235
*/

package comp3011;

import javafx.scene.image.Image;

/**
 * A finished frame handed from the preparer thread to the FX thread.
 *
 * It carries no due time on purpose as the FX thread decides when a frame is
 * due, because the playback clock belongs to that thread. preparedAtNs is System.nanoTime() when
 * the frame was finished. It is only there so the FX thread can log how far ahead of its due time
 * the preparer is running.
 */
public record PreparedFrame(Image image, int frameNumber, long mediaTimestampUs, long preparedAtNs) {
	/**
	 * Put on the queue by the preparer when the grabber runs out of frames. Its
	 * image is null, so call {@link #isEnd()} before using {@link #image()}.
	 */
	public static final PreparedFrame END = new PreparedFrame(null, -1, -1, 0);

	public boolean isEnd() {
		return this == END;
	}
}
