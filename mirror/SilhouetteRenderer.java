package mirror;

import java.awt.image.BufferedImage;
import java.util.Arrays;

/**
 * Utility that converts run-length encoded silhouette data into an image buffer
 * that can be painted by Swing. The palette loosely matches the Kinect SDK
 * viewer samples so the filled silhouette looks the same as the reference
 * applications shipped with the sensor.
 */
final class SilhouetteRenderer {

    // Colors lifted from the Kinect WPF viewer sample. Index 0 is transparent.
    private static final int[] PLAYER_PALETTE = new int[] {
            0x00000000,
            0xFFE60000,
            0xFF0094FF,
            0xFF04C100,
            0xFFFFA500,
            0xFF9400D3,
            0xFFFFD700
    };

    private BufferedImage buffer;
    private int[] argbPixels = new int[0];

    BufferedImage render(SilhouetteFrame frame) {
        if (frame == null || frame.isEmpty()) {
            return null;
        }

        ensureBuffer(frame.getWidth(), frame.getHeight());
        Arrays.fill(argbPixels, 0);

        int width = buffer.getWidth();
        int height = buffer.getHeight();

        frame.forEachRun((y, startX, length, playerIndex) -> {
            if (playerIndex <= 0 || playerIndex >= PLAYER_PALETTE.length) {
                return;
            }

            if (y < 0 || y >= height) {
                return;
            }

            int clampedStart = clamp(startX, 0, width);
            int clampedEnd = clamp(startX + length, 0, width);
            if (clampedEnd <= clampedStart) {
                return;
            }

            int rowStart = y * width;
            int color = PLAYER_PALETTE[playerIndex];
            Arrays.fill(argbPixels, rowStart + clampedStart, rowStart + clampedEnd, color);
        });

        buffer.setRGB(0, 0, buffer.getWidth(), buffer.getHeight(), argbPixels, 0, buffer.getWidth());
        return buffer;
    }

    private void ensureBuffer(int width, int height) {
        if (buffer == null || buffer.getWidth() != width || buffer.getHeight() != height) {
            buffer = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            argbPixels = new int[width * height];
        }
    }

    private static int clamp(int value, int min, int max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }
}
