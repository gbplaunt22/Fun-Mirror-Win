package mirror;

public class Main {

	public static void main(String[] args) {
		KinectBridge bridge = new KinectBridge();

		try {
			bridge.start();
			System.out.println("Java: KinectBridge started. Watching head values...");

			// simple test loop: print values for ~10 seconds
			long end = System.currentTimeMillis() + 10_000;
			while (System.currentTimeMillis() < end) {
				int x = bridge.getHeadX();
				int y = bridge.getHeadY();
				double z = bridge.getHeadZ();

				if (x != -1) {
					System.out.printf("Java sees HEAD %d %d %.2f%n", x, y, z);
				}

				Thread.sleep(100); // 10 times per second
			}
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			bridge.stop();
		}

		System.out.println("Java: done.");
	}
}
