package mirror;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Path2D;

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

	// How strongly outline follows the head movement
	private double parallaxFactor = 1.0;
	
	

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

		setupCalibrationKeyBindings();
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

		// Use a semi-transparent stroke color for visibility
		g2.setColor(new Color(0, 200, 255, 180));

		// Draw each outline point as a small filled rectangle (2x2 or 3x3).
		// This mirrors the XNA PlayerOutlineRenderer which draws 2x2 pixels.
		final int pointSize = 6;
		final int half = pointSize / 2;

		for (int i = 0; i + 1 < outline.length; i += 2) {
			// int panelX = mapXToPanel(outline[i], panelW);
			// int panelY = mapYToPanel(outline[i + 1], panelH);

			int baseX = mapXToPanel(outline[i], panelW);
			int baseY = mapYToPanel(outline[i + 1], panelH);

			// apply parallax translation
			Point p = applyHeadParallax(baseX, baseY, panelW, panelH);
			int panelX = p.x;
			int panelY = p.y;

			// optionally skip points outside panel, cheap clipping
			if (panelX + half < 0 || panelX - half > panelW || panelY + half < 0 || panelY - half > panelH)
				continue;

			g2.fillRect(panelX - half, panelY - half, pointSize, pointSize);

			// Translation for parallax effect

		}
	}

	// Mirror illusion, shift: move outline points based on head position
	// If head is not at the anchor, we translate
	// all outline points by the same delta so the silhouette follows the viewer.
	private Point applyHeadParallax(int baseX, int baseY, int panelW, int panelH) {
		// Where head is on screen
		int headX = mapXToPanel(smoothX, panelW);
		int headY = mapYToPanel(smoothY, panelH);

		// Where do we conceptually want the head to be?
		int anchorX = panelW / 2;
		int anchorY = panelH / 2;

		// How strongly should outline follow head?
		// 1.0 = full lock, 0.5 = half as much
		double factor = 1.0;

		int dx = (int) Math.round((headX - anchorX) * parallaxFactor);
		int dy = (int) Math.round((headY - anchorY) * parallaxFactor);

		int outX = baseX + dx;
		int outY = baseY + dy;

		return new Point(outX, outY);
	}

	// ---- Runtime calibration helpers ----

	// Small shift in X (offsetX is in "panel width" units, so 0.01 ≈ 1% of width)
	public void adjustOffsetX(double delta) {
		offsetX += delta;
		repaint();
	}

	public void adjustOffsetY(double delta) {
		offsetY += delta;
		repaint();
	}

	// Scale > 0; values like ±0.05 are usually enough for fine tuning
	public void adjustScaleX(double delta) {
		scaleX += delta;
		if (scaleX < 0.1)
			scaleX = 0.1; // clamp so it doesn't go negative/tiny
		repaint();
	}

	public void adjustScaleY(double delta) {
		scaleY += delta;
		if (scaleY < 0.1)
			scaleY = 0.1;
		repaint();
	}

	// Direct setters if you ever want to jump to a value
	public void setParallaxFactor(double factor) {
		this.parallaxFactor = factor;
		repaint();
	}

	public double getParallaxFactor() {
		return parallaxFactor;
	}

	public double getScaleX() {
		return scaleX;
	}

	public double getScaleY() {
		return scaleY;
	}

	public double getOffsetX() {
		return offsetX;
	}

	public double getOffsetY() {
		return offsetY;
	}

	private void setupCalibrationKeyBindings() {
		InputMap im = getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
		ActionMap am = getActionMap();

		// Horizontal offset
		im.put(KeyStroke.getKeyStroke("LEFT"), "offsetLeft");
		im.put(KeyStroke.getKeyStroke("RIGHT"), "offsetRight");

		am.put("offsetLeft", new AbstractAction() {
			@Override
			public void actionPerformed(java.awt.event.ActionEvent e) {
				adjustOffsetX(-0.01); // move outline left
			}
		});
		am.put("offsetRight", new AbstractAction() {
			@Override
			public void actionPerformed(java.awt.event.ActionEvent e) {
				adjustOffsetX(0.01); // move outline right
			}
		});

		// Vertical offset
		im.put(KeyStroke.getKeyStroke("UP"), "offsetUp");
		im.put(KeyStroke.getKeyStroke("DOWN"), "offsetDown");

		am.put("offsetUp", new AbstractAction() {
			@Override
			public void actionPerformed(java.awt.event.ActionEvent e) {
				adjustOffsetY(-0.01); // up on screen
			}
		});
		am.put("offsetDown", new AbstractAction() {
			@Override
			public void actionPerformed(java.awt.event.ActionEvent e) {
				adjustOffsetY(0.01); // down on screen
			}
		});

		// Zoom in/out (scale)
		im.put(KeyStroke.getKeyStroke('='), "scaleUp"); // '+' usually needs shift, so '=' is easier
		im.put(KeyStroke.getKeyStroke('-'), "scaleDown");

		am.put("scaleUp", new AbstractAction() {
			@Override
			public void actionPerformed(java.awt.event.ActionEvent e) {
				adjustScaleX(0.05);
				adjustScaleY(0.05);
			}
		});
		am.put("scaleDown", new AbstractAction() {
			@Override
			public void actionPerformed(java.awt.event.ActionEvent e) {
				adjustScaleX(-0.05);
				adjustScaleY(-0.05);
			}
		});
	}

}
