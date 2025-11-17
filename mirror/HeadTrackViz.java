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

        private void drawOutline(Graphics2D g2, int[] outline, int stride, int panelW, int panelH) {
                if (outline.length < stride * 2)
                        return;

                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                List<OutlinePoint> mapped = mapOutline(outline, stride, panelW, panelH);
                if (mapped.isEmpty())
                        return;

                Path2D orderedOutline = buildOrderedOutline(mapped);
                Stroke original = g2.getStroke();
                g2.setColor(new Color(0, 200, 255, 180));

                if (orderedOutline != null) {
                        g2.setStroke(new BasicStroke(3f));
                        g2.draw(orderedOutline);
                } else {
                        drawPointCloud(g2, mapped);
                }

                g2.setStroke(original);
        }

        private List<OutlinePoint> mapOutline(int[] outline, int stride, int panelW, int panelH) {
                boolean hasDepth = stride >= 3;
                List<OutlinePoint> mapped = new ArrayList<>(outline.length / stride);
                for (int i = 0; i <= outline.length - stride; i += stride) {
                        double panelX = mapXToPanel(outline[i], panelW);
                        double panelY = mapYToPanel(outline[i + 1], panelH);
                        int depth = hasDepth ? outline[i + 2] : 0;
                        mapped.add(new OutlinePoint(panelX, panelY, depth));
                }
                return mapped;
        }

        private Path2D buildOrderedOutline(List<OutlinePoint> points) {
                if (points.size() < 3)
                        return null;

                Path2D path = new Path2D.Double();
                OutlinePoint first = points.get(0);
                path.moveTo(first.x, first.y);
                for (int i = 1; i < points.size(); i++) {
                        OutlinePoint p = points.get(i);
                        path.lineTo(p.x, p.y);
                }
                path.closePath();

                double area = polygonArea(points);
                if (Math.abs(area) < 1.0) {
                        return null;
                }

                return path;
        }

        private double polygonArea(List<OutlinePoint> points) {
                double area = 0.0;
                for (int i = 0; i < points.size(); i++) {
                        OutlinePoint a = points.get(i);
                        OutlinePoint b = points.get((i + 1) % points.size());
                        area += (a.x * b.y) - (b.x * a.y);
                }
                return area / 2.0;
        }

        private void drawPointCloud(Graphics2D g2, List<OutlinePoint> points) {
                int size = 4;
                for (OutlinePoint p : points) {
                        int x = (int) Math.round(p.x) - size / 2;
                        int y = (int) Math.round(p.y) - size / 2;
                        g2.fillOval(x, y, size, size);
                }
        }

        private static class OutlinePoint {
                final double x;
                final double y;
                final int depth;

                OutlinePoint(double x, double y, int depth) {
                        this.x = x;
                        this.y = y;
                        this.depth = depth;
                }
        }
}
