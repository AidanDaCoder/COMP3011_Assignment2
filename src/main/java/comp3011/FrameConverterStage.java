/*
 * Authors:
 *   1. Aidan Chan A1924235
 */
package comp3011;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import org.bytedeco.javacv.JavaFXFrameConverter;

import javafx.scene.image.Image;

/**
 * The last stage of the pipeline, a thread that owns the {@link JavaFXFrameConverter}. It turns each finished frame
 * into an Image and puts it on the queue the FX thread reads from. It also gives back the permit the producer took
 * for the frame, which is how the number of frames in flight is kept bounded.
 */
public class FrameConverterStage implements Runnable {
    // How long to wait on a queue before checking whether we have been asked to stop.
    private static final long WAIT_MS = 20;

    private final BlockingQueue<FrameJob> input;
    private final JavaFXFrameConverter converter;
    private final BlockingQueue<PreparedFrame> videoOut;
    private final Semaphore inFlight;
    private final BooleanSupplier stopRequested;
    private final Consumer<Exception> errorHandler;

    public FrameConverterStage(
            BlockingQueue<FrameJob> input,
            JavaFXFrameConverter converter,
            BlockingQueue<PreparedFrame> videoOut,
            Semaphore inFlight,
            BooleanSupplier stopRequested,
            Consumer<Exception> errorHandler) {
        this.input = input;
        this.converter = converter;
        this.videoOut = videoOut;
        this.inFlight = inFlight;
        this.stopRequested = stopRequested;
        this.errorHandler = errorHandler;
    }

    @Override
    public void run() {
        try {
            while (!stopRequested.getAsBoolean()) {
                FrameJob job = input.poll(WAIT_MS, TimeUnit.MILLISECONDS);
                if (job == null) {
                    continue;
                }

                if (job.isEnd()) {
                    offerUnlessStopped(PreparedFrame.END);
                    return;
                }

                Image image = converter.convert(job.frame());
                PreparedFrame prepared = new PreparedFrame(
                        image, job.frameNumber(), job.mediaTimestampUs(), System.nanoTime());
                if (!offerUnlessStopped(prepared)) {
                    return;
                }
                inFlight.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            errorHandler.accept(e);
        }
    }

    // A full queue means the display is behind, so wait, but give up as soon as a stop is requested.
    private boolean offerUnlessStopped(PreparedFrame frame) throws InterruptedException {
        while (!stopRequested.getAsBoolean()) {
            if (videoOut.offer(frame, WAIT_MS, TimeUnit.MILLISECONDS)) {
                return true;
            }
        }
        return false;
    }
}
