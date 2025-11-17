# Fun-Mirror-Win

Fun-Mirror v0.2 for Windows instead of Linux.

## Repository layout

* `mirror/` – the Swing-based Fun Mirror app. `HeadTrackViz` is where we draw the
  tracked markers, while `KinectBridge` streams head-position data from the
  native Windows helper.
* `native/windows/` – Windows helpers (`KinectBridge.exe` + C# sources) that own
  the Kinect sensor and forward head/outline data over STDOUT to the Java layer.
* `examples/XnaBasics/` – a standalone XNA prototype used to experiment with new
  rendering tricks (like the player-outline effect) before the logic is ported
  into the Swing mirror UI.

## Kinect outline workflow

The `PlayerOutlineRenderer` inside `examples/XnaBasics` is still a great place
to iterate on visual styles, but the same contour-generation technique now ships
inside `native/windows/KinectBridge`. The native helper listens to both skeleton
and depth frames, emits `HEAD x y z` lines exactly as before, and interleaves
`OUTLINE n x0 y0 x1 y1 ...` payloads with up to ~300 edge samples in Kinect
depth coordinates. `HeadTrackViz` listens for those outline points and renders
them on top of the Swing mirror alongside the smoothed/raw head markers.

### Message format

* `BRIDGE_READY` – printed once the Kinect sensor is initialized. The Java side
  uses this implicitly by waiting for the first data line.
* `HEAD x y z` – depth-space X/Y (0–639, 0–479) plus depth in meters.
* `OUTLINE n ...` – `n` pairs of pixel coordinates describing the detected
  player silhouette; `OUTLINE 0` clears the contour when no body is tracked.

### Building `KinectBridge.exe`

The `native/windows/KinectBridge` folder contains a `.csproj` and the `Program`
class that implements the console bridge. Open it with Visual Studio (with the
Kinect SDK installed) or run `msbuild` to produce a replacement
`native/windows/KinectBridge.exe`. Copy that binary over the checked-in stub to
run the Swing mirror without rebuilding the entire repository.
