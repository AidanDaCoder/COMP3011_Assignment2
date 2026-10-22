/*
 * Authors:
 *   1. Aidan Chan A1924235
 */
package comp3011;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Checks that the effects that run their rows in parallel give exactly the same pixels as when they run one row after
 * another. The same effect object and the same frame number are used for both runs, so the only difference is
 * whether the rows were shared between threads.
 */
class ParallelEffectsTest {
    private static final int WIDTH = 321; // odd sizes, so that the last band and the last block are partly filled
    private static final int HEIGHT = 183;
    private static final int[] FRAME_NUMBERS = { 0, 7, 100, 250 };

    @AfterEach
    void goBackToParallel() {
        RowParallel.forceSequential = false;
    }

    @Test
    void parallelAndSequentialRunsGiveTheSamePixels() throws Exception {
        Frame frame = new Frame(WIDTH, HEIGHT, Frame.DEPTH_UBYTE, 3);
        InfoVideo video = new InfoVideo(
                "test.mp4", 1000, WIDTH, HEIGHT, Frame.DEPTH_UBYTE, 3, frame.imageStride, 24.0, 24, 41708, 0);
        byte[] original = new byte[((ByteBuffer) frame.image[0]).capacity()];
        new Random(12345).nextBytes(original);

        List<FrameProcessor> effects = List.of(
                new FrameVignetter(), new FrameBlackAndWhiter(), new FrameYellower(), new FrameFlickerer(),
                new FrameBleeder(), new FrameMottler());

        for (FrameProcessor effect : effects) {
            effect.initialise(video);
            boolean changedSomething = false;

            for (int frameNumber : FRAME_NUMBERS) {
                InfoFrame info = new InfoFrame(frameNumber, frameNumber * 41708L);

                RowParallel.forceSequential = true;
                byte[] sequential = run(effect, frame, original, info);

                RowParallel.forceSequential = false;
                byte[] parallel = run(effect, frame, original, info);

                assertArrayEquals(sequential, parallel,
                        effect.getClass().getSimpleName() + " differs at frame " + frameNumber);
                changedSomething |= !Arrays.equals(original, sequential);
            }

            assertTrue(changedSomething, effect.getClass().getSimpleName() + " never changed the frame");
        }
    }

    private static byte[] run(FrameProcessor effect, Frame frame, byte[] original, InfoFrame info) throws Exception {
        ByteBuffer pixels = (ByteBuffer) frame.image[0];
        pixels.clear();
        pixels.put(original);
        pixels.rewind();

        effect.process(frame, info);

        byte[] result = new byte[original.length];
        pixels.rewind();
        pixels.get(result);
        pixels.rewind();
        return result;
    }
}
