/*
 * Authors:
 *   1. Aidan Chan A1924235
 */
package comp3011;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.JavaFXFrameConverter;

/**
 * Prepares video frames ahead of time as a pipeline of threads, and queues the results for the FX thread.
 *
 * <p>
 * The frames travel from a producer thread, through a few effect threads, then through a converter thread, and end up
 * on the videoOut queue that the FX thread reads. The producer thread grabs frames, copies each one and feeds the
 * copy into the first queue. The effects are cut into runs of neighbours that each get a thread ({@link EffectStage},
 * see {@link StagePlanner}) and the converter has one too ({@link FrameConverterStage}). Between two stages is a first
 * in, first out queue, so every effect sees the frames in order and the output comes out in order. A frame is only
 * ever held by one thread at a time, so no locks are needed on the pixels.
 * </p>
 *
 * <p>
 * The producer is the only thread that touches the grabber, calls {@code initialise} on the effects and calls
 * {@code AudioPlayer.copySamples}. Each effect instance is used only by the one stage thread it belongs to, and the
 * converter only by the converter thread.
 * </p>
 */
public class FramePreparer {
    // The threads that are not effect stages: the producer, the converter and the FX thread. The effect stages get
    // the remaining cores, so the decoder and the display are never starved of one.
    private static final int OTHER_BUSY_THREADS = 3;

    // Frames allowed in the pipeline on top of one per effect stage, so that every stage always has work waiting.
    // Each frame is a copy, so this also bounds the memory used.
    private static final int EXTRA_FRAMES_IN_FLIGHT = 3;

    // The grabber and the audio copy belong to the producer thread while it is running.
    private final FFmpegFrameGrabber grabber;
    private final JavaFXFrameConverter converter; // used only by the converter thread
    private final List<FrameProcessor> frameProcessors; // each instance is used by one stage thread only
    private final AudioPlayer audioPlayer; // only copySamples is called from here, by the producer
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

    // Control. stopRequested is written by the FX thread and read by every pipeline thread.
    private volatile boolean stopRequested;
    // Not volatile, because only the FX thread ever calls start() and stop(), so only it touches this list.
    private final List<Thread> threads = new ArrayList<>();
    // Survives stop and start, so a seek does not initialise the effects a second time. Only the producer
    // thread uses it, and the threads of one run are always joined before the next run starts.
    private boolean frameProcessorsInitialised;

    // Per run state, created in start(). The producer takes a permit before it copies a frame and the converter
    // gives it back once the frame has been handed to the FX thread's queue.
    private Semaphore framesInFlight;
    private BlockingQueue<FrameJob> firstQueue;
    private final AtomicBoolean errorReported = new AtomicBoolean();

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
     * Starts the pipeline threads. Called from the FX thread only. Does nothing if the pipeline is already
     * running, because two threads must never use the grabber at once.
     */
    public void start() {
        if (threads.stream().anyMatch(Thread::isAlive)) {
            return; // already running, never start a second pipeline
        }
        threads.clear();
        stopRequested = false;
        errorReported.set(false);

        // At most one stage per spare core, and no more than there are effects.
        int maxStages = Math.max(2, Runtime.getRuntime().availableProcessors() - OTHER_BUSY_THREADS);
        List<List<FrameProcessor>> stageGroups = StagePlanner.partition(frameProcessors, maxStages);

        // Fresh queues and permits every run, so frames left over from before a seek can never leak into this run.
        framesInFlight = new Semaphore(stageGroups.size() + EXTRA_FRAMES_IN_FLIGHT);
        firstQueue = new LinkedBlockingQueue<>();

        // One thread per run of effects, with one queue between each pair of stages.
        BlockingQueue<FrameJob> input = firstQueue;
        for (List<FrameProcessor> group : stageGroups) {
            BlockingQueue<FrameJob> output = new LinkedBlockingQueue<>();
            EffectStage stage = new EffectStage(group, input, output, () -> stopRequested, this::reportError);
            addThread(stage, "effect-stage-" + threads.size());
            input = output;
        }

        FrameConverterStage converterStage = new FrameConverterStage(
                input, converter, videoOut, framesInFlight, () -> stopRequested, this::reportError);
        addThread(converterStage, "frame-converter");

        // The producer is added last, and all threads are started together after this, so every consumer exists
        // before any frame is fed in.
        addThread(this::runProducer, "frame-producer");

        threads.forEach(Thread::start);
    }

    private void addThread(Runnable body, String name) {
        Thread t = new Thread(body, name);
        t.setDaemon(true);
        // Slightly below normal, so that when every core is busy the FX thread still gets to draw the frames.
        t.setPriority(Thread.NORM_PRIORITY - 1);
        threads.add(t);
    }

    /**
     * Asks every pipeline thread to stop and waits for them all to finish. Called from the FX thread only. It
     * blocks until the threads have exited, so the caller can safely reposition or close the grabber afterwards.
     * Does nothing if no pipeline is running.
     */
    public void stop() {
        if (threads.isEmpty()) {
            return; // nothing running, so nothing to do
        }
        stopRequested = true;
        for (Thread t : threads) {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        threads.clear();
    }

    /**
     * Only the first error is passed on, because one failure usually makes every other stage fail too, and the
     * model closes the session on the first one.
     */
    private void reportError(Exception e) {
        if (errorReported.compareAndSet(false, true)) {
            errorHandler.accept(e);
        }
    }

    /**
     * The producer thread's loop. It grabs a frame, queues its audio, copies the frame and feeds the copy into the
     * pipeline. Ends when the video runs out (after queueing the end marker), when stop() is called, or when
     * something throws.
     */
    private void runProducer() {
        try {
            while (!stopRequested) {
                Frame frame = grabFrame();
                if (frame == null) {
                    firstQueue.add(FrameJob.END);
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

                if (!acquireUnlessStopped()) {
                    return;
                }
                // The grabber reuses its Frame and pixel buffer on the next grab(), so the pipeline needs its own copy.
                firstQueue.add(new FrameJob(frame.clone(), frameNumber, timestampUs));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            reportError(e);
        }
    }

    // Waits for a free slot in the pipeline, but gives up as soon as stop() is requested.
    private boolean acquireUnlessStopped() throws InterruptedException {
        while (!stopRequested) {
            if (framesInFlight.tryAcquire(50, TimeUnit.MILLISECONDS)) {
                return true;
            }
        }
        return false;
    }

    private Frame grabFrame() throws Exception {
        if (audioAvailable) {
            return grabber.grab();
        }
        return grabber.grabImage();
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
