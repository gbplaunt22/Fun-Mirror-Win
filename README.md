# Fun-Mirror-Win

Fun-Mirror v0.2 for Windows instead of Linux.

## Repository layout

* `mirror/` – the Swing-based Fun Mirror app. `HeadTrackViz` is where we draw the
  tracked markers, while `KinectBridge` streams head-position data from the
  native Windows helper.
* `native/windows/` – Windows helpers (`KinectBridge.exe`) that own the Kinect
  sensor and forward tracking data over STDOUT to the Java layer.
* `examples/XnaBasics/` – a standalone XNA prototype used to experiment with new
  rendering tricks (like the player-outline effect) before the logic is ported
  into the Swing mirror UI.

## Kinect outline workflow

The new `PlayerOutlineRenderer` that lives in `examples/XnaBasics` is a stepping
stone. It demonstrates how to extract per-player masks from the depth stream and
produce a contour that can be layered on top of the depth backbuffer. Once the
edge-generation approach feels right, we can replicate the same algorithm inside
`native/windows/KinectBridge` so that the Swing mirror (via `HeadTrackViz`) can
subscribe to the additional silhouette data alongside head tracking.

Until then, the XNA sample lets us rapidly iterate on the illusion without
risking regressions inside the Java mirror. When the native helper emits the
outline points, `HeadTrackViz` can render them just like it already renders the
smoothed head marker.
