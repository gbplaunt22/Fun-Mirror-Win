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

        private static DepthImagePixel[] depthPixels;
        private static byte[] playerMask;
        private static readonly List<PixelPoint> OutlineScratch = new List<PixelPoint>(2048);

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

                int outlineCount = ExtractOutline(playerMask, frame.Width, frame.Height, OutlineScratch);
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

        private static int ExtractOutline(byte[] mask, int width, int height, List<PixelPoint> outline)
        {
            outline.Clear();

            for (int y = 1; y < height - 1; y += OutlineStride)
            {
                for (int x = 1; x < width - 1; x += OutlineStride)
                {
                    int idx = y * width + x;
                    if (mask[idx] == 0)
                    {
                        continue;
                    }

                    bool isEdge = false;
                    for (int ny = -1; ny <= 1 && !isEdge; ny++)
                    {
                        int sampleY = y + ny;
                        for (int nx = -1; nx <= 1; nx++)
                        {
                            if (nx == 0 && ny == 0)
                            {
                                continue;
                            }

                            int sampleX = x + nx;
                            int sampleIdx = sampleY * width + sampleX;
                            if (mask[sampleIdx] == 0)
                            {
                                isEdge = true;
                                break;
                            }
                        }
                    }

                    if (isEdge)
                    {
                        outline.Add(new PixelPoint(x, y));
                    }
                }
            }

            return outline.Count;
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
