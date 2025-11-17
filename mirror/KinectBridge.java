package mirror;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

public class KinectBridge implements HeadDataSource {

    private Process process;
    private Thread readerThread;
    private volatile boolean running = false;

    // latest head values from Kinect
    private volatile int headX = -1;
    private volatile int headY = -1;
    private volatile double headZ = -1.0;
    private volatile int[] outlinePoints = new int[0];
    private volatile int outlinePointStride = 0;
    private volatile SilhouetteFrame silhouetteFrame = SilhouetteFrame.empty();

    public void start() throws IOException {
        if (running)
            return;

        ProcessBuilder pb = new ProcessBuilder("native/windows/KinectBridge.exe");
        pb.redirectErrorStream(true); // merge stdout+stderr
        process = pb.start();
        running = true;

        readerThread = new Thread(this::readLoop, "KinectBridgeReader");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    private void readLoop() {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream()))) {

            String line;
            while (running && (line = br.readLine()) != null) {
                // System.out.println("FROM C#: " + line); // debug

                if (line.startsWith("HEAD")) {
                    // format: HEAD x y z
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 4) {
                        try {
                            int x = Integer.parseInt(parts[1]);
                            int y = Integer.parseInt(parts[2]);
                            double z = Double.parseDouble(parts[3]);

                            headX = x;
                            headY = y;
                            headZ = z;
                        } catch (NumberFormatException ignored) {
                        }
                    }
                } else if (line.startsWith("OUTLINE")) {
                    parseOutline(line);
                } else if (line.startsWith("SILHOUETTE")) {
                    parseSilhouette(line);
                }
                // you can later handle other messages here
            }
        } catch (IOException e) {
            if (running) {
                e.printStackTrace();
            }
        } finally {
            running = false;
        }
    }

    public void stop() {
        running = false;
        if (process != null) {
            process.destroy();
        }
    }

    // getters for your viz code
    public int getHeadX() {
        return headX;
    }

    public int getHeadY() {
        return headY;
    }

    public double getHeadZ() {
        return headZ;
    }

    public int[] getOutlinePoints() {
        return outlinePoints;
    }

    public int getOutlinePointStride() {
        return outlinePointStride;
    }

    public SilhouetteFrame getSilhouetteFrame() {
        return silhouetteFrame;
    }

    private void parseOutline(String line) {
        String[] parts = line.split("\\s+");
        if (parts.length < 2)
            return;

        try {
            int count = Integer.parseInt(parts[1]);
            if (count <= 0) {
                outlinePoints = new int[0];
                outlinePointStride = 0;
                return;
            }

            int availableValues = parts.length - 2;
            int stride;
            if (availableValues >= count * 3) {
                stride = 3;
            } else if (availableValues >= count * 2) {
                stride = 2;
            } else {
                return;
            }

            int expectedValues = count * stride;
            int[] next = new int[expectedValues];
            for (int i = 0; i < expectedValues; i++) {
                next[i] = Integer.parseInt(parts[2 + i]);
            }
            outlinePoints = next;
            outlinePointStride = stride;
        } catch (NumberFormatException ignored) {
        }
    }

    private void parseSilhouette(String line) {
        String[] parts = line.split("\\s+");
        if (parts.length < 4) {
            return;
        }

        try {
            int width = Integer.parseInt(parts[1]);
            int height = Integer.parseInt(parts[2]);
            int runCount = Integer.parseInt(parts[3]);

            if (width <= 0 || height <= 0 || runCount <= 0) {
                silhouetteFrame = SilhouetteFrame.empty();
                return;
            }

            int expectedValues = runCount * 4;
            if (parts.length < 4 + expectedValues) {
                return;
            }

            int[] runs = new int[expectedValues];
            for (int i = 0; i < expectedValues; i++) {
                runs[i] = Integer.parseInt(parts[4 + i]);
            }

            silhouetteFrame = new SilhouetteFrame(width, height, runs);
        } catch (NumberFormatException ignored) {
        }
    }
}
