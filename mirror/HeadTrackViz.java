package mirror;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;

public class HeadTrackViz extends JPanel {

        private final HeadDataSource headSource;
        private final SilhouetteRenderer silhouetteRenderer = new SilhouetteRenderer();

	// smoothed values (for EMA)
	private boolean haveSmooth = false;
	private double smoothX, smoothY, smoothZ;
	//////////////
	private static final double ALPHA = 0.25; // 0..1, higher = snappier
	//////////////

	// Kinect depth resolution (from C#)
	private static final int KINECT_WIDTH = 640;
	private static final int KINECT_HEIGHT = 480;

	// calibration knobs
	private double scaleX = 1.0;
	private double scaleY = 1.0;
	private double offsetX = 0.0; // in panel-width units
	private double offsetY = 0.0; // in panel-height units

	public void setCalibration(double scaleX, double scaleY, double offsetX, double offsetY) {
		this.scaleX = scaleX;
		this.scaleY = scaleY;
		this.offsetX = offsetX;
		this.offsetY = offsetY;
	}

	// constructor takes a HeadDataSource to get head tracking data from
	public HeadTrackViz(HeadDataSource headSource) {
		this.headSource = headSource;
		setBackground(Color.BLACK);
	}

	// ~30 fps repaint
	Timer timer = new Timer(33, e -> repaint());
	{
		timer.setRepeats(true);
		timer.start();
	}

	protected void paintComponent(Graphics g) {
		super.paintComponent(g);

		// get raw data
		int rawX = headSource.getHeadX();
		int rawY = headSource.getHeadY();
		double rawZ = headSource.getHeadZ();

		if (rawX < 0 || rawY < 0) {
			// No head detected, skip drawing
			g.setColor(Color.RED);
			g.drawString("No head detected", 20, 20);
			return;
		}

		// update smoothing?
		updateSmoothing(rawX, rawY, rawZ);

		// map panel to coords
		int panelW = getWidth();
		int panelH = getHeight();

		int drawX = mapXToPanel(smoothX, panelW);
		int drawY = mapYToPanel(smoothY, panelH);

		int drawRawX = mapXToPanel(rawX, panelW);
		int drawRawY = mapYToPanel(rawY, panelH);

                SilhouetteFrame silhouette = headSource.getSilhouetteFrame();
                if (silhouette != null && !silhouette.isEmpty()) {
                        BufferedImage silhouetteImage = silhouetteRenderer.render(silhouette);
                        if (silhouetteImage != null) {
                                Graphics2D g2 = (Graphics2D) g.create();
                                try {
                                        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                                                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                                        g2.drawImage(silhouetteImage, 0, 0, panelW, panelH, null);
                                } finally {
                                        g2.dispose();
                                }
                        }
                }

		// draw marker
		drawHeadMarker(g, drawX, drawY, smoothZ, panelW, panelH);
		// draw raw marker
		drawHeadMarker(g, drawRawX, drawRawY, rawZ, panelW, panelH);

	}

	private void updateSmoothing(int rawX, int rawY, double rawZ) {
		if (!haveSmooth) {
			smoothX = rawX;
			smoothY = rawY;
			smoothZ = rawZ;
			haveSmooth = true;
		} else {
			smoothX = ALPHA * rawX + (1 - ALPHA) * smoothX;
			smoothY = ALPHA * rawY + (1 - ALPHA) * smoothY;
			smoothZ = ALPHA * rawZ + (1 - ALPHA) * smoothZ;
		}
	}

        private int mapXToPanel(double kinectX, int panelW) {
                return mapXToPanel(kinectX, KINECT_WIDTH, panelW);
        }

        private int mapXToPanel(double kinectX, int sourceWidth, int panelW) {
                double nx = kinectX / sourceWidth; // 0..1
                nx = nx * scaleX + offsetX;
                return (int) Math.round(nx * panelW);
        }

        private int mapYToPanel(double kinectY, int panelH) {
                return mapYToPanel(kinectY, KINECT_HEIGHT, panelH);
        }

        private int mapYToPanel(double kinectY, int sourceHeight, int panelH) {
                double ny = kinectY / sourceHeight; // 0..1
                ny = ny * scaleY + offsetY;
                return (int) Math.round(ny * panelH);
        }

        private void drawHeadMarker(Graphics g, int x, int y, double z, int panelW, int panelH) {
                Graphics2D g2 = (Graphics2D) g;
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		// circle whose size depends on Z distance
		int baseRadius = 40;
		int radius = (int) Math.max(10, baseRadius * (2.0 - z));

		int d = radius * 2;
		int cx = x - radius;
		int cy = y - radius;

		g2.setColor(new Color(0, 255, 0, 160));
		g2.fillOval(cx, cy, d, d);

                g2.setColor(Color.WHITE);
                g2.drawString(String.format("Z=%.2f m", z), 10, 20);
        }

        // Outline rendering intentionally removed so that the silhouette fill matches the Kinect SDK sample.
}
