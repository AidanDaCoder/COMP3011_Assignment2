/*
 * Authors:
 *   1. Aidan Chan A1924235
 */
package comp3011;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs the rows of a frame effect on several threads at once. The caller says how many rows there are and gives the
 * work for a range of rows. The rows are cut into bands, the bands run on a small pool of worker threads, and the call
 * returns only when every band has finished.
 *
 * <p>
 * This is the data parallel half of the player. The preparer thread still handles one frame at a time and in order,
 * so the effects see the frames exactly as before. Inside an effect, though, the pixels of different rows do not
 * depend on each other, so different threads can work on different rows of the same frame.
 * </p>
 *
 * <p>
 * Why it is safe. Each band gets its own range of rows, so no two threads write the same byte. The caller waits for
 * every band before it moves on, and waiting on a {@link Future} makes everything the workers wrote visible to the
 * caller. An effect that uses this must only read shared data that nothing changes during the call, and must write
 * only inside the rows it was given.
 * </p>
 *
 * <p>
 * The number of workers is the number of cores minus {@value #RESERVED_CORES}, so the JavaFX thread and the rest
 * of the machine always have cores left to draw the frames.
 * </p>
 */
final class RowParallel {
    // Cores left alone for the JavaFX thread and everything else on the machine.
    private static final int RESERVED_CORES = 2;

    // More bands than workers, so that a slow band does not leave the other workers waiting at the end.
    private static final int BANDS_PER_WORKER = 3;

    private static final int WORKERS = Math.max(1, Runtime.getRuntime().availableProcessors() - RESERVED_CORES);

    // Switched on by the tests that compare a parallel run with a sequential run of the same effect. Nothing else
    // sets it.
    static volatile boolean forceSequential;

    // Null when there is only one worker, because then the caller just does the work itself.
    private static final ExecutorService POOL =
            WORKERS > 1 ? Executors.newFixedThreadPool(WORKERS, new WorkerThreadFactory()) : null;

    /** The work for one band, rows {@code fromRow} up to but not including {@code toRow}. */
    @FunctionalInterface
    interface Rows {
        void run(int fromRow, int toRow);
    }

    private RowParallel() {
    }

    /**
     * Runs {@code body} over rows 0 to {@code rowCount} and returns when all of them are done. A row can be any unit
     * of height the effect likes, such as a row of pixels or a row of 4 by 4 blocks.
     */
    static void forRows(int rowCount, Rows body) {
        if (rowCount <= 0) {
            return;
        }
        if (POOL == null || rowCount < 2 || forceSequential) {
            body.run(0, rowCount);
            return;
        }

        List<Future<?>> futures = new ArrayList<>();
        for (int[] band : bands(rowCount, Math.min(rowCount, WORKERS * BANDS_PER_WORKER))) {
            futures.add(POOL.submit(() -> body.run(band[0], band[1])));
        }
        waitForAll(futures);
    }

    /**
     * Cuts {@code rowCount} rows into {@code bandCount} bands of nearly equal size. Each band is {from, to}, and the
     * bands cover every row exactly once, in order.
     */
    static List<int[]> bands(int rowCount, int bandCount) {
        List<int[]> bands = new ArrayList<>();
        for (int i = 0; i < bandCount; i++) {
            int from = (int) ((long) rowCount * i / bandCount);
            int to = (int) ((long) rowCount * (i + 1) / bandCount);
            bands.add(new int[] { from, to });
        }
        return bands;
    }

    // Must not return while any band is still running, because the bands write into the frame.
    private static void waitForAll(List<Future<?>> futures) {
        boolean interrupted = false;
        RuntimeException failure = null;

        for (Future<?> future : futures) {
            boolean done = false;
            while (!done) {
                try {
                    future.get();
                    done = true;
                } catch (InterruptedException e) {
                    interrupted = true; // keep waiting, and pass the interrupt on once every band is finished
                } catch (ExecutionException e) {
                    if (failure == null) {
                        Throwable cause = e.getCause();
                        failure = cause instanceof RuntimeException runtime ? runtime : new RuntimeException(cause);
                    }
                    done = true;
                }
            }
        }

        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        if (failure != null) {
            throw failure;
        }
    }

    // Daemon threads, so the workers never keep the program alive after the window closes.
    private static final class WorkerThreadFactory implements ThreadFactory {
        private final AtomicInteger count = new AtomicInteger();

        @Override
        public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "row-worker-" + count.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
}
