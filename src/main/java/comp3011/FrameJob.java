/*
 * Authors:
 *   1. Aidan Chan A1924235
 */
package comp3011;

import org.bytedeco.javacv.Frame;

/**
 * A frame travelling through the effect pipeline. It holds the producer's private copy of a decoded frame, plus the
 * numbers the effects need. A job belongs to exactly one stage thread at a time and is handed on through a queue, so
 * the effects can change its pixels in place without any locking.
 */
public record FrameJob(Frame frame, int frameNumber, long mediaTimestampUs) {

    /**
     * Put on the first queue by the producer when the grabber runs out of frames. Every stage passes it on and then
     * exits. Its frame is null, so call {@link #isEnd()} before using {@link #frame()}.
     */
    public static final FrameJob END = new FrameJob(null, -1, -1);

    public boolean isEnd() {
        return this == END;
    }
}
