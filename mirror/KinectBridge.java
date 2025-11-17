package mirror;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

public class KinectBridge {

	private Process process;
	private Thread readerThread;
	private volatile boolean running = false;

	// latest head values from Kinect
	private volatile int headX = -1;
	private volatile int headY = -1;
	private volatile double headZ = -1.0;

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
}
