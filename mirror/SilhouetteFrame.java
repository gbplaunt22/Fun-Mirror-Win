package mirror;

import java.util.Objects;

/**
 * Immutable snapshot of the Kinect player's silhouette represented as run-length encoded rows.
 */
public final class SilhouetteFrame {

    private static final SilhouetteFrame EMPTY = new SilhouetteFrame(0, 0, new int[0]);

        private final int width;
        private final int height;
        private final int[] runData;

    public SilhouetteFrame(int width, int height, int[] runData) {
        this.width = width;
        this.height = height;
        this.runData = runData == null ? new int[0] : runData.clone();
    }

        public static SilhouetteFrame empty() {
                return EMPTY;
        }

        public boolean isEmpty() {
                return width <= 0 || height <= 0 || runData.length == 0;
        }

        public int getWidth() {
                return width;
        }

        public int getHeight() {
                return height;
        }

    public int getRunCount() {
        return runData.length / 4;
    }

    public void forEachRun(RunConsumer consumer) {
        Objects.requireNonNull(consumer, "consumer");
        for (int i = 0; i <= runData.length - 4; i += 4) {
            consumer.accept(runData[i], runData[i + 1], runData[i + 2], runData[i + 3]);
        }
    }

    public interface RunConsumer {
        void accept(int y, int startX, int length, int playerIndex);
    }
}
