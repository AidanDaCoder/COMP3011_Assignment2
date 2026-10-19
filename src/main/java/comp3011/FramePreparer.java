/*
 * Authors:
 *   1. Aidan Chan A1924235
 */
package comp3011;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.JavaFXFrameConverter;

import javafx.scene.image.Image;

/**
 * Runs on its own thread and prepares video frames ahead of time: grab, apply the
 * effects, convert to an Image, then queue the result for the FX thread.
 * It is the only thread that touches the grabber and the effect chain.
 */
public class FramePreparer {
    // Owned and used only by the preparer thread while it is running.
    private final FFmpegFrameGrabber grabber;
    private final JavaFXFrameConverter converter;
    private final List<FrameProcessor> frameProcessors;
    private final AudioPlayer audioPlayer; // only copySamples is called from here
    private final boolean audioAvailable;
    private final BooleanSupplier audioOutputEnabled; // read live, the user can toggle audio

    // Facts about the video that InfoVideo needs when the effects are initialised.
    private final String mediaName;
    private final int totalVideoFrames;
    private final double frameRate;
    private final int intFrameRate;
    private final int videoFrameDurationUs;

    // Handoff to the FX thread.
    private final BlockingQueue<PreparedFrame> videoOut;
    private final Queue<PendingAudio> audioOut;
    private final Consumer<Exception> errorHandler;

    // Control. stopRequested is written by the FX thread and read by the preparer thread.
    private volatile boolean stopRequested;
    // Not volatile: only the FX thread ever calls start() and stop(), so only it touches this field.
    private Thread thread;
    // Survives stop and start, so a seek does not initialise the effects a second time.
    private boolean frameProcessorsInitialised;

    public FramePreparer(
            FFmpegFrameGrabber grabber,
            JavaFXFrameConverter converter,
            List<FrameProcessor> frameProcessors,
            AudioPlayer audioPlayer,
            boolean audioAvailable,
            BooleanSupplier audioOutputEnabled,
            String mediaName,
            int totalVideoFrames,
            double frameRate,
            int intFrameRate,
            int videoFrameDurationUs,
            BlockingQueue<PreparedFrame> videoOut,
            Queue<PendingAudio> audioOut,
            Consumer<Exception> errorHandler) {
        this.grabber = grabber;
        this.converter = converter;
        this.frameProcessors = frameProcessors;
        this.audioPlayer = audioPlayer;
        this.audioAvailable = audioAvailable;
        this.audioOutputEnabled = audioOutputEnabled;
        this.mediaName = mediaName;
        this.totalVideoFrames = totalVideoFrames;
        this.frameRate = frameRate;
        this.intFrameRate = intFrameRate;
        this.videoFrameDurationUs = videoFrameDurationUs;
        this.videoOut = videoOut;
        this.audioOut = audioOut;
        this.errorHandler = errorHandler;
    }

    /**
     * Starts the preparer thread. Called from the FX thread only. Does nothing if the thread is
     * already running, because two threads must never use the grabber at once.
     */
    public void start() {
        if (thread != null && thread.isAlive()) {
            return; // already running, never start a second preparer
        }
        stopRequested = false;
        thread = new Thread(this::run, "frame-preparer");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Asks the preparer thread to stop and waits for it to finish. Called from the FX thread only.
     * It blocks until the thread has exited, so the caller can safely reposition or close the
     * grabber afterwards. Does nothing if no thread is running.
     */
    public void stop() {
        if (thread == null) {
            return; // nothing running, so nothing to do
        }
        stopRequested = true;
        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        thread = null;
    }

    /**
     * The preparer thread's loop: grab a frame, queue its audio, run the effects, convert it to an
     * Image and hand it to the FX thread. Ends when the video runs out (after queueing the end
     * marker), when stop() is called, or when something throws.
     */
    private void run() {
        try {
            while (!stopRequested) {
                Frame frame = grabFrame();
                if (frame == null) {
                    putUnlessStopped(PreparedFrame.END);
                    return;
                }

                long timestampUs = grabber.getTimestamp();
                if (audioAvailable && frame.samples != null) {
                    queueAudio(timestampUs, frame);
                }

                if (frame.image == null) {
                    continue;
                }

                if (!frameProcessorsInitialised) {
                    initialiseFrameProcessors(new InfoVideo(
                            mediaName,
                            totalVideoFrames,
                            frame.imageWidth,
                            frame.imageHeight,
                            frame.imageDepth,
                            frame.imageChannels,
                            frame.imageStride,
                            frameRate,
                            intFrameRate,
                            videoFrameDurationUs,
                            grabber.getPixelFormat()));
                    frameProcessorsInitialised = true;
                }

                int frameNumber = grabber.getFrameNumber();
                processFrame(frame, new InfoFrame(frameNumber, timestampUs));

                Image image = converter.convert(frame);
                putUnlessStopped(new PreparedFrame(image, frameNumber, timestampUs, System.nanoTime()));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            errorHandler.accept(e);
        }
    }

    /**
     * Waits for room on the video queue, but gives up as soon as stop() is requested, so a full
     * queue can never keep the thread from exiting. A plain put() would block forever.
     */
    private void putUnlessStopped(PreparedFrame frame) throws InterruptedException {
        while (!stopRequested) {
            if (videoOut.offer(frame, 50, TimeUnit.MILLISECONDS)) {
                return;
            }
        }
    }

    private Frame grabFrame() throws Exception {
        if (audioAvailable) {
            return grabber.grab();
        }
        return grabber.grabImage();
    }

    private void processFrame(Frame frame, InfoFrame info) throws Exception {
        for (FrameProcessor processor : frameProcessors) {
            processor.process(frame, info);
        }
    }

    private void initialiseFrameProcessors(InfoVideo videoInfo) throws Exception {
        for (FrameProcessor processor : frameProcessors) {
            processor.initialise(videoInfo);
        }
    }

    private void queueAudio(long timestampUs, Frame frame) {
        if (!audioOutputEnabled.getAsBoolean() || audioPlayer == null) {
            return;
        }

        byte[] samples = audioPlayer.copySamples(frame);
        if (samples.length > 0) {
            audioOut.add(new PendingAudio(timestampUs, samples));
        }
    }
}
