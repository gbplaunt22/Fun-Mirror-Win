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
        private static readonly int[] NeighborX = { 1, 1, 0, -1, -1, -1, 0, 1 };
        private static readonly int[] NeighborY = { 0, 1, 1, 1, 0, -1, -1, -1 };

        private static DepthImagePixel[] depthPixels;
        private static byte[] playerMask;
        private static readonly List<PixelPoint> OutlineScratch = new List<PixelPoint>(2048);

        private static volatile bool hasTrackedSkeleton = false;
        private static volatile bool outlineActive = false;

        private struct PixelPoint
        {
            public readonly int X;
            public readonly int Y;
            public readonly int Depth;

            public PixelPoint(int x, int y)
            {
                X = x;
                Y = y;
                Depth = 0;
            }

            public PixelPoint(int x, int y, int depth)
            {
                X = x;
                Y = y;
                Depth = depth;
            }
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
                    ClearOutline();
                    return;
                }

                Skeleton[] skeletons = new Skeleton[frame.SkeletonArrayLength];
                frame.CopySkeletonDataTo(skeletons);

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

                if (!hasTrackedSkeleton)
                {
                    ClearOutline();
                    return;
                }

                frame.CopyDepthImagePixelDataTo(depthPixels);
                BuildPlayerMask(depthPixels, playerMask);

                int outlineCount = ExtractOutline(playerMask, depthPixels, frame.Width, frame.Height, OutlineScratch);
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

        private static int ExtractOutline(byte[] mask, DepthImagePixel[] depth, int width, int height, List<PixelPoint> outline)
        {
            outline.Clear();

            if (!TryFindBoundaryPixel(mask, width, height, out PixelPoint start))
            {
                return 0;
            }

            TraceContour(mask, depth, width, height, start, outline);
            return outline.Count;
        }

        private static bool TryFindBoundaryPixel(byte[] mask, int width, int height, out PixelPoint start)
        {
            for (int y = 0; y < height; y++)
            {
                int row = y * width;
                for (int x = 0; x < width; x++)
                {
                    int idx = row + x;
                    if (mask[idx] != 0 && IsBoundaryPixel(mask, width, height, x, y))
                    {
                        start = new PixelPoint(x, y);
                        return true;
                    }
                }
            }

            start = default(PixelPoint);
            return false;
        }

        private static bool IsBoundaryPixel(byte[] mask, int width, int height, int x, int y)
        {
            int idx = y * width + x;
            if (mask[idx] == 0)
            {
                return false;
            }

            for (int ny = -1; ny <= 1; ny++)
            {
                int sampleY = y + ny;
                if (sampleY < 0 || sampleY >= height)
                {
                    return true;
                }

                for (int nx = -1; nx <= 1; nx++)
                {
                    if (nx == 0 && ny == 0)
                    {
                        continue;
                    }

                    int sampleX = x + nx;
                    if (sampleX < 0 || sampleX >= width)
                    {
                        return true;
                    }

                    int sampleIdx = sampleY * width + sampleX;
                    if (mask[sampleIdx] == 0)
                    {
                        return true;
                    }
                }
            }

            return false;
        }

        private static void TraceContour(byte[] mask, DepthImagePixel[] depth, int width, int height, PixelPoint start, List<PixelPoint> outline)
        {
            outline.Add(new PixelPoint(start.X, start.Y, SampleDepth(depth, width, start.X, start.Y)));

            PixelPoint current = start;
            int backtrackDir = 4; // pretend we entered from the west
            int steps = 0;
            int decimateCounter = 0;
            int maxSteps = width * height * 4;

            while (steps < maxSteps)
            {
                if (!TryStep(mask, width, height, current, backtrackDir, out PixelPoint next, out int nextBacktrack))
                {
                    break;
                }

                current = next;
                backtrackDir = nextBacktrack;
                steps++;

                if (current.X == start.X && current.Y == start.Y)
                {
                    break;
                }

                decimateCounter++;
                if (decimateCounter >= OutlineStride)
                {
                    outline.Add(new PixelPoint(current.X, current.Y, SampleDepth(depth, width, current.X, current.Y)));
                    decimateCounter = 0;

                    if (outline.Count >= MaxOutlinePoints)
                    {
                        break;
                    }
                }
            }
        }

        private static int SampleDepth(DepthImagePixel[] depth, int width, int x, int y)
        {
            int idx = y * width + x;
            if (idx < 0 || idx >= depth.Length)
            {
                return 0;
            }

            return depth[idx].Depth;
        }

        private static bool TryStep(byte[] mask, int width, int height, PixelPoint current, int backtrackDir, out PixelPoint next, out int nextBacktrack)
        {
            // Moore-neighbor tracing searches starting two steps clockwise from
            // the direction we entered the current pixel (i.e., hugging the
            // contour on the right-hand side). Using +6 (two steps
            // counter-clockwise) makes the tracer immediately step back toward
            // the previous pixel, producing duplicated points and a wobbling
            // outline. Rotate the starting direction clockwise instead.
            int startDir = (backtrackDir + 2) & 7;
            for (int i = 0; i < 8; i++)
            {
                int dir = (startDir + i) & 7;
                int nx = current.X + NeighborX[dir];
                int ny = current.Y + NeighborY[dir];
                if (nx < 0 || ny < 0 || nx >= width || ny >= height)
                {
                    continue;
                }

                int idx = ny * width + nx;
                if (mask[idx] == 0)
                {
                    continue;
                }

                next = new PixelPoint(nx, ny);
                nextBacktrack = (dir + 4) & 7;
                return true;
            }

            next = current;
            nextBacktrack = backtrackDir;
            return false;
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

            var builder = new StringBuilder(count * 9 + 16);
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
                builder.Append(' ');
                builder.Append(pt.Depth);
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
