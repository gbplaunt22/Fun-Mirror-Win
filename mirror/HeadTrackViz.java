package mirror;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.List;
import java.awt.geom.Point2D;

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
		if (outline != null && outline.length >= 4) {
			Graphics2D g2 = (Graphics2D) g.create();
			try {
				drawOutline(g2, outline, panelW, panelH);
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

	private void drawOutline(Graphics2D g2, int[] outline, int panelW, int panelH) {
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		
		List<Point2D.Double> contour = orderContourGreedy(outline);
		if (contour.size() < 3) return;
		
		Path2D path = new Path2D.Double();
		boolean first = true;
		 for (Point2D.Double p : contour) {
			int panelX = mapXToPanel((int) p.x, panelW);
			int panelY = mapYToPanel((int) p.y, panelH);
			if (first) {
				path.moveTo(panelX, panelY);
				first = false;
			} else {
				path.lineTo(panelX, panelY);
			}
		}

		path.closePath();
		

		Stroke original = g2.getStroke();
		g2.setStroke(new BasicStroke(3f));
		g2.setColor(new Color(0, 200, 255, 180));
		g2.draw(path);
		g2.setStroke(original);
	}

	private java.util.List<Point2D.Double> orderContourGreedy(int[] outline) {
		int n = outline.length / 2;
		java.util.List<Point2D.Double> pts = new java.util.ArrayList<>(n);
		for (int i = 0; i < outline.length; i += 2) {
			pts.add(new Point2D.Double(outline[i], outline[i + 1]));
		}

		// nothing to do
		if (pts.size() < 3)
			return pts;

		// 1) choose starting point: smallest y, then smallest x
		int startIdx = 0;
		for (int i = 1; i < pts.size(); i++) {
			Point2D.Double p = pts.get(i);
			Point2D.Double best = pts.get(startIdx);
			if (p.y < best.y || (p.y == best.y && p.x < best.x)) {
				startIdx = i;
			}
		}

		java.util.List<Point2D.Double> ordered = new java.util.ArrayList<>(n);
		Point2D.Double current = pts.remove(startIdx);
		ordered.add(current);

		// 2) walk to the nearest unused point each time
		while (!pts.isEmpty()) {
			int bestIdx = 0;
			double bestD2 = dist2(current, pts.get(0));
			for (int i = 1; i < pts.size(); i++) {
				double d2 = dist2(current, pts.get(i));
				if (d2 < bestD2) {
					bestD2 = d2;
					bestIdx = i;
				}
			}
			current = pts.remove(bestIdx);
			ordered.add(current);
		}

		return ordered;
	}

	private static double dist2(Point2D.Double a, Point2D.Double b) {
		double dx = a.x - b.x;
		double dy = a.y - b.y;
		return dx * dx + dy * dy;
	}
}