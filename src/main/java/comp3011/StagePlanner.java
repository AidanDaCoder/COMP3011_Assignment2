/*
 * Authors:
 *   1. Aidan Chan A1924235
 */
package comp3011;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Decides which effects share a thread. The effects have to stay in command line order, so a stage is always a run of
 * neighbouring effects, and the planner cuts the list into runs so that the most expensive run is as cheap as it can
 * be. The slowest stage sets the speed of the whole pipeline.
 *
 * <p>
 * Why not a thread per effect: some effects cost under 0.1 ms and others about 5 ms, so most of those threads would
 * do almost nothing but pass frames on. Thirty or more busy threads also compete with the decoder and the display for
 * the cores, and the 8 core TITAN machine dropped frames with a thread per effect.
 * </p>
 *
 * <p>
 * This class uses no JavaFX and holds no state, so a test can call it directly.
 * </p>
 */
final class StagePlanner {
    // Used for an effect that is not in the table, for example one added later.
    private static final double DEFAULT_COST = 1.0;

    // Relative cost of one frame, in ms, from the per effect timings of the original player (see README).
    private static final Map<Class<? extends FrameProcessor>, Double> COST =
            Map.<Class<? extends FrameProcessor>, Double>ofEntries(
            Map.entry(FrameVignetter.class, 4.7),
            Map.entry(FrameBleeder.class, 4.4),
            Map.entry(FrameBlackAndWhiter.class, 3.0),
            Map.entry(FrameMottler.class, 2.6),
            Map.entry(FrameYellower.class, 1.65),
            Map.entry(FrameFlickerer.class, 1.05),
            Map.entry(FramePepperer.class, 0.2),
            Map.entry(FrameJitterer.class, 0.05),
            Map.entry(FrameDuster.class, 0.03),
            Map.entry(FrameScratcher.class, 0.03),
            Map.entry(FrameNumberer.class, 0.03));

    private StagePlanner() {
    }

    /**
     * Cuts the effects into at most {@code maxStages} runs of neighbours, in the original order, so that the most
     * expensive run costs as little as possible. Every effect is in exactly one run and no run is empty.
     */
    static List<List<FrameProcessor>> partition(List<FrameProcessor> processors, int maxStages) {
        int n = processors.size();
        if (n == 0) {
            return List.of();
        }
        int stages = Math.max(1, Math.min(maxStages, n));

        double[] prefix = new double[n + 1]; // prefix[i] is the cost of the first i effects
        for (int i = 0; i < n; i++) {
            prefix[i + 1] = prefix[i] + costOf(processors.get(i));
        }

        // heaviest[j][i] is the smallest possible cost of the heaviest run when the first i effects are cut into
        // j runs. cut[j][i] is where the last of those j runs starts.
        double[][] heaviest = new double[stages + 1][n + 1];
        int[][] cut = new int[stages + 1][n + 1];
        for (int i = 1; i <= n; i++) {
            heaviest[1][i] = prefix[i];
        }
        for (int j = 2; j <= stages; j++) {
            for (int i = j; i <= n; i++) {
                heaviest[j][i] = Double.MAX_VALUE;
                for (int start = j - 1; start < i; start++) {
                    double cost = Math.max(heaviest[j - 1][start], prefix[i] - prefix[start]);
                    if (cost < heaviest[j][i]) {
                        heaviest[j][i] = cost;
                        cut[j][i] = start;
                    }
                }
            }
        }

        // Walk back from the end to find where each run starts.
        List<List<FrameProcessor>> runs = new ArrayList<>();
        int end = n;
        for (int j = stages; j >= 1; j--) {
            int start = (j == 1) ? 0 : cut[j][end];
            runs.add(0, List.copyOf(processors.subList(start, end)));
            end = start;
        }
        return runs;
    }

    private static double costOf(FrameProcessor processor) {
        return COST.getOrDefault(processor.getClass(), DEFAULT_COST);
    }
}
