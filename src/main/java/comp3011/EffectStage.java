/*
 * Authors:
 *   1. Aidan Chan A1924235
 */
package comp3011;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * One stage of the effect pipeline, a thread that owns a single {@link FrameProcessor}. It takes a frame from its
 * input queue, applies the effect, and passes the frame to its output queue.
 *
 * <p>
 * There is one thread per effect because effects such as the bleeder, jitterer and mottler keep per frame scratch
 * state in the instance, so an instance must never run two frames at once. With one thread per instance and first
 * in, first out queues between stages, each instance sees every frame in order, exactly as in the single threaded
 * player, and the frames come out in order with no reorder buffer. Several frames are in the pipeline at once, one
 * per stage, and that is where the speed up comes from.
 * </p>
 *
 * <p>
 * This class uses no JavaFX, so a test can drive it with synthetic frames.
 * </p>
 */
public class EffectStage implements Runnable {
    // How long to wait on the queue before checking whether we have been asked to stop.
    private static final long WAIT_MS = 20;

    private final FrameProcessor processor;
    private final BlockingQueue<FrameJob> input;
    // Unbounded, because the number of frames in flight is limited elsewhere.
    private final BlockingQueue<FrameJob> output;
    private final BooleanSupplier stopRequested;
    private final Consumer<Exception> errorHandler;

    public EffectStage(
            FrameProcessor processor,
            BlockingQueue<FrameJob> input,
            BlockingQueue<FrameJob> output,
            BooleanSupplier stopRequested,
            Consumer<Exception> errorHandler) {
        this.processor = processor;
        this.input = input;
        this.output = output;
        this.stopRequested = stopRequested;
        this.errorHandler = errorHandler;
    }

    @Override
    public void run() {
        try {
            while (!stopRequested.getAsBoolean()) {
                // Wait with a timeout rather than forever, so a stage waiting on an empty queue still notices a stop.
                FrameJob job = input.poll(WAIT_MS, TimeUnit.MILLISECONDS);
                if (job == null) {
                    continue;
                }

                if (job.isEnd()) {
                    output.add(job); // pass the end marker down the line, then we are finished
                    return;
                }

                processor.process(job.frame(), new InfoFrame(job.frameNumber(), job.mediaTimestampUs()));
                output.add(job);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            errorHandler.accept(e);
        }
    }
}
