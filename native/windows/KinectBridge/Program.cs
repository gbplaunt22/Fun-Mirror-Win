using System;
using System.Collections.Generic;
using System.Text;
using Microsoft.Kinect;

namespace KinectBridge
{
    internal static class Program
    {
        private static KinectSensor sensor;
        private static readonly object ConsoleLock = new object();

        private const DepthImageFormat DepthFormat = DepthImageFormat.Resolution640x480Fps30;
        private const int OutlineStride = 3;
        private const int MaxOutlinePoints = 320;
        private const int BoundingBoxPadding = 20;

        private static DepthImagePixel[] depthPixels;
        private static byte[] playerMask;
        private static readonly List<PixelPoint> OutlineScratch = new List<PixelPoint>(2048);

        // store latest skeletons so depth callback can use them
        private static Skeleton[] currentSkeletons = null;
        private static volatile bool hasTrackedSkeleton = false;
        private static volatile bool outlineActive = false;

        private struct PixelPoint
        {
            public readonly int X;
            public readonly int Y;

            public PixelPoint(int x, int y)
            {
                X = x;
                Y = y;
            }
        }

        // Small replacement for System.Drawing.Rectangle to avoid adding that dependency.
        private struct DepthRect
        {
            public int Left;
            public int Top;
            public int Right;
            public int Bottom;

            public int Width => Right - Left;
            public int Height => Bottom - Top;
            public bool IsEmpty => Width <= 0 || Height <= 0;

            public static DepthRect Empty => new DepthRect { Left = 0, Top = 0, Right = 0, Bottom = 0 };
        }

        private static void Main(string[] args)
        {
            foreach (var s in KinectSensor.KinectSensors)
            {
                if (s.Status == KinectStatus.Connected)
                {
                    sensor = s;
                    break;
                }
            }

            if (sensor == null)
            {
                Console.WriteLine("NO_KINECT");
                Console.WriteLine("Press Enter to exit...");
                Console.ReadLine();
                return;
            }

            sensor.SkeletonStream.Enable();
            sensor.DepthStream.Enable(DepthFormat);

            depthPixels = new DepthImagePixel[sensor.DepthStream.FramePixelDataLength];
            playerMask = new byte[depthPixels.Length];

            sensor.SkeletonFrameReady += Sensor_SkeletonFrameReady;
            sensor.DepthFrameReady += Sensor_DepthFrameReady;

            sensor.Start();

            Console.WriteLine("BRIDGE_READY");
            Console.WriteLine("Press Enter to quit...");
            Console.Out.Flush();

            Console.ReadLine();

            sensor.Stop();
            sensor.SkeletonFrameReady -= Sensor_SkeletonFrameReady;
            sensor.DepthFrameReady -= Sensor_DepthFrameReady;
        }

        private static void Sensor_SkeletonFrameReady(object sender, SkeletonFrameReadyEventArgs e)
        {
            using (SkeletonFrame frame = e.OpenSkeletonFrame())
            {
                if (frame == null)
                {
                    hasTrackedSkeleton = false;
                    currentSkeletons = null;
                    ClearOutline();
                    return;
                }

                Skeleton[] skeletons = new Skeleton[frame.SkeletonArrayLength];
                frame.CopySkeletonDataTo(skeletons);

                // Save skeletons for depth handler to use
                currentSkeletons = skeletons;

                Skeleton tracked = null;
                foreach (var s in skeletons)
                {
                    if (s.TrackingState == SkeletonTrackingState.Tracked)
                    {
                        tracked = s;
                        break;
                    }
                }

                if (tracked == null)
                {
                    hasTrackedSkeleton = false;
                    ClearOutline();
                    return;
                }

                hasTrackedSkeleton = true;

                Joint head = tracked.Joints[JointType.Head];

                SkeletonPoint headPosition;
                if (head.TrackingState != JointTrackingState.NotTracked)
                {
                    headPosition = head.Position;
                }
                else
                {
                    Joint bodyFallback = tracked.Joints[JointType.ShoulderCenter];
                    if (bodyFallback.TrackingState == JointTrackingState.NotTracked)
                    {
                        bodyFallback = tracked.Joints[JointType.Spine];
                    }

                    if (bodyFallback.TrackingState == JointTrackingState.NotTracked)
                    {
                        return;
                    }

                    headPosition = new SkeletonPoint
                    {
                        X = bodyFallback.Position.X,
                        Y = bodyFallback.Position.Y + 0.25f,
                        Z = bodyFallback.Position.Z
                    };
                }

                DepthImagePoint p = sensor.CoordinateMapper.MapSkeletonPointToDepthPoint(headPosition, DepthFormat);

                lock (ConsoleLock)
                {
                    Console.WriteLine("HEAD {0} {1} {2:F2}", p.X, p.Y, headPosition.Z);
                    Console.Out.Flush();
                }
            }
        }

        private static void Sensor_DepthFrameReady(object sender, DepthImageFrameReadyEventArgs e)
        {
            using (DepthImageFrame frame = e.OpenDepthImageFrame())
            {
                if (frame == null)
                {
                    return;
                }

                if (!hasTrackedSkeleton || currentSkeletons == null)
                {
                    ClearOutline();
                    return;
                }

                frame.CopyDepthImagePixelDataTo(depthPixels);
                BuildPlayerMask(depthPixels, playerMask);

                // Extract outline only inside tracked skeleton bounding boxes
                int outlineCount = ExtractOutlineForSkeletons(playerMask, frame.Width, frame.Height, currentSkeletons, OutlineScratch);
                if (outlineCount > 0)
                {
                    PublishOutline(OutlineScratch);
                }
                else
                {
                    ClearOutline();
                }
            }
        }

        private static void BuildPlayerMask(DepthImagePixel[] depthData, byte[] mask)
        {
            for (int i = 0; i < depthData.Length; i++)
            {
                mask[i] = depthData[i].PlayerIndex > 0 ? (byte)1 : (byte)0;
            }
        }

        /// <summary>
        /// For each tracked skeleton, compute a loose depth-space bounding box,
        /// then extract edge pixels inside that box using a 3x3 neighbor test
        /// (same logic as PlayerOutlineRenderer.ExtractEdges from XNA).
        /// </summary>
        private static int ExtractOutlineForSkeletons(byte[] mask, int width, int height, Skeleton[] skeletons, List<PixelPoint> outline)
        {
            outline.Clear();

            if (skeletons == null)
            {
                return 0;
            }

            int maxX = width - 1;
            int maxY = height - 1;

            for (int s = 0; s < skeletons.Length; s++)
            {
                var skel = skeletons[s];
                if (skel == null || skel.TrackingState != SkeletonTrackingState.Tracked)
                {
                    continue;
                }

                DepthRect bounds = GetBoundingBoxDepthSpace(skel, width, height);
                if (bounds.IsEmpty)
                {
                    continue;
                }

                // clamp bounds to frame
                int left = Math.Max(1, bounds.Left);
                int top = Math.Max(1, bounds.Top);
                int right = Math.Min(width - 2, bounds.Right);
                int bottom = Math.Min(height - 2, bounds.Bottom);

                for (int y = top; y <= bottom; y += OutlineStride)
                {
                    int rowOffset = y * width;
                    for (int x = left; x <= right; x += OutlineStride)
                    {
                        int idx = rowOffset + x;
                        if (mask[idx] == 0)
                        {
                            continue;
                        }

                        bool up = mask[(y - 1) * width + x] == 1;
                        bool down = mask[(y + 1) * width + x] == 1;
                        bool leftN = mask[rowOffset + (x - 1)] == 1;
                        bool rightN = mask[rowOffset + (x + 1)] == 1;

                        if (!(up && down && leftN && rightN))
                        {
                            outline.Add(new PixelPoint(x, y));
                        }
                    }
                }
            }

            return outline.Count;
        }

        /// <summary>
        /// Compute a loose bounding rectangle in depth-space (640x480),
        /// mapping each tracked joint to depth coordinates and padding the box.
        /// </summary>
        private static DepthRect GetBoundingBoxDepthSpace(Skeleton skeleton, int width, int height)
        {
            if (skeleton == null)
            {
                return DepthRect.Empty;
            }

            float minX = width;
            float minY = height;
            float maxX = 0;
            float maxY = 0;
            bool hasPoint = false;

            foreach (Joint joint in skeleton.Joints)
            {
                if (joint.TrackingState == JointTrackingState.NotTracked)
                {
                    continue;
                }

                DepthImagePoint dpt = sensor.CoordinateMapper.MapSkeletonPointToDepthPoint(joint.Position, DepthFormat);

                if (float.IsNaN(dpt.X) || float.IsNaN(dpt.Y))
                {
                    continue;
                }

                hasPoint = true;
                minX = Math.Min(minX, dpt.X);
                minY = Math.Min(minY, dpt.Y);
                maxX = Math.Max(maxX, dpt.X);
                maxY = Math.Max(maxY, dpt.Y);
            }

            if (!hasPoint)
            {
                return DepthRect.Empty;
            }

            int left = Math.Max(0, (int)minX - BoundingBoxPadding);
            int top = Math.Max(0, (int)minY - BoundingBoxPadding);
            int right = Math.Min(width - 1, (int)maxX + BoundingBoxPadding);
            int bottom = Math.Min(height - 1, (int)maxY + BoundingBoxPadding);

            // Ensure non-empty rect. Right/Bottom are inclusive here and mirror Rectangle.Right semantics.
            if (right <= left || bottom <= top)
            {
                return DepthRect.Empty;
            }

            return new DepthRect { Left = left, Top = top, Right = right, Bottom = bottom };
        }

        private static void PublishOutline(IList<PixelPoint> points)
        {
            if (points == null || points.Count == 0)
            {
                ClearOutline();
                return;
            }

            int count = points.Count;
            if (count > MaxOutlinePoints)
            {
                count = MaxOutlinePoints;
            }

            var builder = new StringBuilder(count * 6 + 16);
            builder.Append("OUTLINE ");
            builder.Append(count);

            double step = points.Count <= count ? 1.0 : (double)points.Count / count;
            for (int i = 0; i < count; i++)
            {
                int index = points.Count <= count ? i : (int)(i * step);
                if (index >= points.Count)
                {
                    index = points.Count - 1;
                }

                PixelPoint pt = points[index];
                builder.Append(' ');
                builder.Append(pt.X);
                builder.Append(' ');
                builder.Append(pt.Y);
            }

            lock (ConsoleLock)
            {
                outlineActive = true;
                Console.WriteLine(builder.ToString());
                Console.Out.Flush();
            }
        }

        private static void ClearOutline()
        {
            if (!outlineActive)
            {
                return;
            }

            lock (ConsoleLock)
            {
                Console.WriteLine("OUTLINE 0");
                Console.Out.Flush();
                outlineActive = false;
            }
        }
    }
}
