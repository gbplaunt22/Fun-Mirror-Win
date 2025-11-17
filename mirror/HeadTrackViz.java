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

                List<Point2D.Double> contour = orderContourConvexHull(outline);
                if (contour.size() < 3)
                        return;

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

        private java.util.List<Point2D.Double> orderContourConvexHull(int[] outline) {
                int n = outline.length / 2;
                java.util.List<Point2D.Double> pts = new java.util.ArrayList<>(n);
                java.util.HashSet<Long> seen = new java.util.HashSet<>();
                for (int i = 0; i < outline.length; i += 2) {
                        int x = outline[i];
                        int y = outline[i + 1];
                        long key = (((long) x) << 32) ^ (y & 0xffffffffL);
                        if (seen.add(key)) {
                                pts.add(new Point2D.Double(x, y));
                        }
                }

                if (pts.size() < 3)
                        return pts;

                pts.sort((p1, p2) -> {
                        int cmpX = Double.compare(p1.x, p2.x);
                        if (cmpX != 0)
                                return cmpX;
                        return Double.compare(p1.y, p2.y);
                });

                java.util.List<Point2D.Double> lower = new java.util.ArrayList<>();
                for (Point2D.Double p : pts) {
                        while (lower.size() >= 2 && cross(lower.get(lower.size() - 2), lower.get(lower.size() - 1), p) <= 0) {
                                lower.remove(lower.size() - 1);
                        }
                        lower.add(p);
                }

                java.util.List<Point2D.Double> upper = new java.util.ArrayList<>();
                for (int i = pts.size() - 1; i >= 0; i--) {
                        Point2D.Double p = pts.get(i);
                        while (upper.size() >= 2 && cross(upper.get(upper.size() - 2), upper.get(upper.size() - 1), p) <= 0) {
                                upper.remove(upper.size() - 1);
                        }
                        upper.add(p);
                }

                lower.remove(lower.size() - 1);
                upper.remove(upper.size() - 1);
                lower.addAll(upper);

                return lower;
        }

        private static double cross(Point2D.Double a, Point2D.Double b, Point2D.Double c) {
                double abx = b.x - a.x;
                double aby = b.y - a.y;
                double bcx = c.x - b.x;
                double bcy = c.y - b.y;
                return abx * bcy - aby * bcx;
        }
}