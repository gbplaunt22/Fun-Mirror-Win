package mirror;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.List;

public class HeadTrackViz extends JPanel {

	private final HeadDataSource headSource;

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

		int[] outline = headSource.getOutlinePoints();
		int outlineStride = headSource.getOutlinePointStride();
		if (outline != null && outlineStride >= 2 && outline.length >= outlineStride * 2) {
			Graphics2D g2 = (Graphics2D) g.create();
			try {
				drawOutline(g2, outline, outlineStride, panelW, panelH);
			} finally {
				g2.dispose();
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
		double nx = kinectX / KINECT_WIDTH; // 0..1
		// mirror flip: left/right
		// nx = 1.0 - nx;
		nx = nx * scaleX + offsetX;
		return (int) Math.round(nx * panelW);
	}

	private int mapYToPanel(double kinectY, int panelH) {
		double ny = kinectY / KINECT_HEIGHT; // 0..1
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

        private static final int DEPTH_BREAK_MM = 120;
        private static final int DISTANCE_BREAK_PX = 45;
        private static final int CLOSE_GAP_PX = 16;

	private void drawOutline(Graphics2D g2, int[] outline, int stride, int panelW, int panelH) {
		if (outline.length < stride * 2)
			return;

		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		boolean hasDepth = stride >= 3;
		List<Path2D> segments = new ArrayList<>();
		Path2D currentPath = null;
		int segmentStartX = Integer.MIN_VALUE;
		int segmentStartY = Integer.MIN_VALUE;
		int lastX = Integer.MIN_VALUE;
		int lastY = Integer.MIN_VALUE;
		int lastDepth = Integer.MIN_VALUE;

		for (int i = 0; i <= outline.length - stride; i += stride) {
			int kinectX = outline[i];
			int kinectY = outline[i + 1];
			int depthMm = hasDepth ? outline[i + 2] : 0;

			int panelX = mapXToPanel(kinectX, panelW);
			int panelY = mapYToPanel(kinectY, panelH);

			if (currentPath == null) {
				currentPath = new Path2D.Double();
				currentPath.moveTo(panelX, panelY);
				segmentStartX = panelX;
				segmentStartY = panelY;
			} else {
				boolean breakSegment = hasDepth && shouldBreakSegment(lastX, lastY, lastDepth, panelX, panelY, depthMm);
				if (breakSegment) {
					finalizeSegment(segments, currentPath, segmentStartX, segmentStartY, lastX, lastY);
					currentPath = new Path2D.Double();
					currentPath.moveTo(panelX, panelY);
					segmentStartX = panelX;
					segmentStartY = panelY;
				} else if (panelX != lastX || panelY != lastY) {
					currentPath.lineTo(panelX, panelY);
				}
			}

			lastX = panelX;
			lastY = panelY;
			lastDepth = hasDepth ? depthMm : lastDepth;
		}

		finalizeSegment(segments, currentPath, segmentStartX, segmentStartY, lastX, lastY);

		if (segments.isEmpty())
			return;

		Stroke original = g2.getStroke();
		g2.setStroke(new BasicStroke(3f));
		g2.setColor(new Color(0, 200, 255, 180));
		for (Path2D path : segments) {
			g2.draw(path);
		}
		g2.setStroke(original);
	}
        private boolean shouldBreakSegment(int lastX, int lastY, int lastDepth, int nextX, int nextY, int nextDepth) {
                if (lastDepth <= 0 || nextDepth <= 0)
                        return true;

                int depthDelta = Math.abs(nextDepth - lastDepth);
                if (depthDelta > DEPTH_BREAK_MM)
                        return true;

                int dx = nextX - lastX;
                int dy = nextY - lastY;
                return dx * dx + dy * dy > DISTANCE_BREAK_PX * DISTANCE_BREAK_PX;
        }

        private void finalizeSegment(List<Path2D> segments, Path2D currentPath, int startX, int startY, int endX, int endY) {
                if (currentPath == null)
                        return;

                if (startX != Integer.MIN_VALUE && endX != Integer.MIN_VALUE) {
                        int dx = endX - startX;
                        int dy = endY - startY;
                        if (dx * dx + dy * dy <= CLOSE_GAP_PX * CLOSE_GAP_PX) {
                                currentPath.closePath();
                        }
                }

                segments.add(currentPath);
        }
}
