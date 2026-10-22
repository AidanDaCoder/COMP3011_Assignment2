/*
 * Authors:
 *   1. Aidan Chan A1924235
 */
package comp3011;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;

import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Load test that compares the effect chain with its rows shared between threads against the same chain run on one
 * thread, which is how the starter player ran it. It is an integration test (the name ends in IT), so
 * {@code mvn test} stays quick and {@code mvn verify} runs it through the failsafe plugin.
 *
 * <p>
 * The test uses a synthetic 1280 x 720 frame, so it needs no video file and gives the same load on any machine. It
 * measures the cost of preparing one frame, which is the number that decides whether the player keeps up, and the
 * results are printed so that a future developer can compare them with the starter version. The sequential baseline
 * is {@link RowParallel#forceSequential}, which makes every {@code forRows} call run its body inline. It does not
 * include the other change, which moved decoding off the JavaFX thread, because that needs a real video.
 *
 * <p>
 * Timing assertions are loose on purpose (a clear win, not an exact figure). They are only made on machines with at
 * least six cores. With fewer there are only a few workers to share the rows between, and a small or busy build
 * machine (a grading server, say) can swamp the gain with noise, which would fail the whole build for no real reason.
 * On those machines the test still runs and prints its table, then reports itself as skipped.
 */
class PlayerLoadIT {
    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;
    private static final int WARM_UP_FRAMES = 8;
    private static final int TIMED_FRAMES = 30;
    private static final double FRAME_BUDGET_MS = 1000.0 / 23.976; // the clip's frame period
    private static final int MAX_REPEATS = 8; // 8 repeats of the 11 effects is 88 effects
    private static final int MIN_CORES_FOR_ASSERTIONS = 6;

    /** The order of the command line "-nsfwyvdjmbp", one of every effect. */
    private static final List<Supplier<FrameProcessor>> ONE_OF_EACH = List.of(
            FrameNumberer::new, FrameScratcher::new, FrameFlickerer::new, FrameBlackAndWhiter::new,
            FrameYellower::new, FrameVignetter::new, FrameDuster::new, FrameJitterer::new, FrameMottler::new,
            FrameBleeder::new, FramePepperer::new);

    @AfterEach
    void goBackToParallel() {
        RowParallel.forceSequential = false;
    }

    @Test
    void parallelChainIsFasterThanSingleThreadedChain() throws Exception {
        System.out.printf("%nLoad test: %d x %d frames, %d cores, frame budget %.1f ms%n",
                WIDTH, HEIGHT, Runtime.getRuntime().availableProcessors(), FRAME_BUDGET_MS);
        System.out.printf("%8s %14s %14s %9s%n", "effects", "single (ms)", "parallel (ms)", "speedup");

        double lastSpeedup = 0;
        for (int repeats : new int[] { 1, 3 }) {
            double single = msPerFrame(repeats, true);
            double parallel = msPerFrame(repeats, false);
            lastSpeedup = single / parallel;
            System.out.printf("%8d %14.2f %14.2f %8.2fx%n",
                    repeats * ONE_OF_EACH.size(), single, parallel, lastSpeedup);
        }

        assumeEnoughCoresToAssert();

        // 33 effects is the heaviest case measured. Sharing rows should be clearly faster, not just equal. The bar is
        // low because a busy build machine adds noise.
        assertTrue(lastSpeedup > 1.15, "expected parallel to be clearly faster at 33 effects, speedup " + lastSpeedup);
    }

    @Test
    void parallelChainFitsAtLeastAsManyEffectsInOneFrame() throws Exception {
        int singleFits = effectsThatFitInOneFrame(true);
        int parallelFits = effectsThatFitInOneFrame(false);
        System.out.printf("%nEffects that fit in %.1f ms: single threaded %d, parallel %d%n",
                FRAME_BUDGET_MS, singleFits, parallelFits);

        assumeEnoughCoresToAssert();

        // Counts move in steps of 11 effects, so a small speed up can leave both on the same step. Never fewer is the
        // safe claim, and the speed up test above covers "clearly faster".
        assertTrue(parallelFits >= singleFits,
                "parallel fitted " + parallelFits + " effects, single threaded " + singleFits);
    }

    /** Called after the timings are printed, so a small machine still shows its numbers but cannot fail the build. */
    private static void assumeEnoughCoresToAssert() {
        assumeTrue(Runtime.getRuntime().availableProcessors() >= MIN_CORES_FOR_ASSERTIONS,
                "speed up is only asserted with at least " + MIN_CORES_FOR_ASSERTIONS + " cores");
    }

    /** Largest whole number of repeats of the 11 effects that still fits in one frame period, as an effect count. */
    private static int effectsThatFitInOneFrame(boolean singleThreaded) throws Exception {
        int fits = 0;
        for (int repeats = 1; repeats <= MAX_REPEATS; repeats++) {
            if (msPerFrame(repeats, singleThreaded) > FRAME_BUDGET_MS) {
                break;
            }
            fits = repeats * ONE_OF_EACH.size();
        }
        return fits;
    }

    /** Mean time to run the whole chain over one frame, after a warm up so that the JIT has compiled the loops. */
    private static double msPerFrame(int repeats, boolean singleThreaded) throws Exception {
        RowParallel.forceSequential = singleThreaded;

        Frame frame = new Frame(WIDTH, HEIGHT, Frame.DEPTH_UBYTE, 3);
        InfoVideo video = new InfoVideo(
                "load.mp4", 1000, WIDTH, HEIGHT, Frame.DEPTH_UBYTE, 3, frame.imageStride, 24.0, 24, 41708, 0);
        byte[] original = new byte[((ByteBuffer) frame.image[0]).capacity()];
        new Random(2026).nextBytes(original);

        List<FrameProcessor> chain = new ArrayList<>();
        for (int i = 0; i < repeats; i++) {
            for (Supplier<FrameProcessor> factory : ONE_OF_EACH) {
                FrameProcessor effect = factory.get();
                effect.initialise(video);
                chain.add(effect);
            }
        }

        long elapsedNs = 0;
        for (int frameNumber = 0; frameNumber < WARM_UP_FRAMES + TIMED_FRAMES; frameNumber++) {
            ByteBuffer pixels = (ByteBuffer) frame.image[0];
            pixels.clear();
            pixels.put(original);
            pixels.rewind();
            InfoFrame info = new InfoFrame(frameNumber, frameNumber * 41708L);

            long start = System.nanoTime();
            for (FrameProcessor effect : chain) {
                effect.process(frame, info);
            }
            if (frameNumber >= WARM_UP_FRAMES) {
                elapsedNs += System.nanoTime() - start;
            }
        }
        return elapsedNs / 1e6 / TIMED_FRAMES;
    }
}
