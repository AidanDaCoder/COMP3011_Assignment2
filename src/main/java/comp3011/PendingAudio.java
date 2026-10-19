/*
 * Authors:
 *   1. Aidan Chan A1924235
 */

package comp3011;

/**
 * A chunk of decoded audio waiting to be written to the sound line.
 * The preparer thread creates these and queues them; after the FX thread polls one off
 * the queue, only the FX thread touches it (it advances the offset as it writes).
 */

class PendingAudio {
    private final long timestampUs;
    private final byte[] samples;
    private int offset;

    PendingAudio(long timestampUs, byte[] samples) {
        this.timestampUs = timestampUs;
        this.samples = samples;
    }

    long timestampUs() {
        return timestampUs;
    }

    byte[] samples() {
        return samples;
    }

    int offset() {
        return offset;
    }

    int remaining() {
        return samples.length - offset;
    }

    void advance(int byteCount) {
        offset += byteCount;
    }

    boolean finished() {
        return offset >= samples.length;
    }
}