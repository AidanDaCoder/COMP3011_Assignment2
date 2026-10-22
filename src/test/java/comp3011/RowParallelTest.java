/*
 * Authors:
 *   1. Aidan Chan A1924235
 */
package comp3011;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicIntegerArray;

import org.junit.jupiter.api.Test;

/**
 * Tests that RowParallel hands out every row exactly once and waits for all of them.
 */
class RowParallelTest {

    @Test
    void bandsCoverEveryRowOnceInOrder() {
        for (int rowCount : new int[] { 1, 2, 7, 100, 800, 1001 }) {
            for (int bandCount : new int[] { 1, 2, 3, 24 }) {
                int bands = Math.min(rowCount, bandCount);
                List<int[]> cut = RowParallel.bands(rowCount, bands);

                assertEquals(bands, cut.size());
                int next = 0;
                for (int[] band : cut) {
                    assertEquals(next, band[0], "bands must touch with no gap or overlap");
                    assertTrue(band[1] > band[0], "no band may be empty");
                    next = band[1];
                }
                assertEquals(rowCount, next, "the last band must end at the last row");
            }
        }
    }

    @Test
    void everyRowIsVisitedExactlyOnce() {
        int rows = 800;
        AtomicIntegerArray visits = new AtomicIntegerArray(rows);

        RowParallel.forRows(rows, (from, to) -> {
            for (int row = from; row < to; row++) {
                visits.incrementAndGet(row);
            }
        });

        for (int row = 0; row < rows; row++) {
            assertEquals(1, visits.get(row), "row " + row);
        }
    }

    @Test
    void theCallDoesNotReturnUntilTheWorkIsDone() {
        int rows = 400;
        int[] results = new int[rows]; // plain array on purpose, reading it afterwards relies on the call's waiting

        RowParallel.forRows(rows, (from, to) -> {
            for (int row = from; row < to; row++) {
                results[row] = row * 2 + 1;
            }
        });

        for (int row = 0; row < rows; row++) {
            assertEquals(row * 2 + 1, results[row]);
        }
    }

    @Test
    void noRowsMeansNoWork() {
        RowParallel.forRows(0, (from, to) -> {
            throw new AssertionError("must not be called");
        });
    }

    @Test
    void oneRowStillRuns() {
        int[] seen = new int[1];

        RowParallel.forRows(1, (from, to) -> seen[0] += to - from);

        assertEquals(1, seen[0]);
    }

    @Test
    void anExceptionInABandReachesTheCaller() {
        IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
                RowParallel.forRows(100, (from, to) -> {
                    if (from == 0) {
                        throw new IllegalStateException("band failed");
                    }
                }));

        assertEquals("band failed", thrown.getMessage());
    }
}
