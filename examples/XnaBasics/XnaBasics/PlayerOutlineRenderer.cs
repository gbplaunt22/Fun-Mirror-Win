namespace Microsoft.Samples.Kinect.XnaBasics
{
    using System.Collections.Generic;
    using Microsoft.Kinect;
    using Microsoft.Xna.Framework;
    using Microsoft.Xna.Framework.Graphics;

    /// <summary>
    /// Generates a lightweight contour for each tracked player by combining
    /// skeleton joint bounds with the player index bits from the depth map.
    /// </summary>
    internal class PlayerOutlineRenderer
    {
        /// <summary>
        /// Maximum number of distinct colors we cycle through for outlines.
        /// </summary>
        private static readonly Color[] PlayerColors =
        {
            Color.Cyan,
            Color.Magenta,
            Color.Orange,
            Color.LimeGreen,
            Color.DeepSkyBlue,
            Color.LightYellow
        };

        /// <summary>
        /// Map used to convert skeleton joints to depth coordinates.
        /// </summary>
        private readonly SkeletonPointMap mapMethod;

        /// <summary>
        /// Graphics device used to lazily create our 1x1 sprite.
        /// </summary>
        private readonly GraphicsDevice graphicsDevice;

        /// <summary>
        /// Temporary point buffer we reuse every frame.
        /// </summary>
        private readonly List<OutlinePoint> scratchPoints = new List<OutlinePoint>();

        /// <summary>
        /// The current frame's outline points.
        /// </summary>
        private readonly List<OutlinePoint> outlinePoints = new List<OutlinePoint>();

        /// <summary>
        /// A single white pixel that we stretch to draw the outline.
        /// </summary>
        private Texture2D pixel;

        /// <summary>
        /// Initializes a new instance of the <see cref="PlayerOutlineRenderer"/> class.
        /// </summary>
        /// <param name="graphicsDevice">Graphics device used to create textures.</param>
        /// <param name="mapMethod">Delegate that maps skeleton points to depth space.</param>
        public PlayerOutlineRenderer(GraphicsDevice graphicsDevice, SkeletonPointMap mapMethod)
        {
            this.graphicsDevice = graphicsDevice;
            this.mapMethod = mapMethod;
        }

        /// <summary>
        /// Updates the cached outline geometry based on the latest depth data.
        /// </summary>
        /// <param name="depthData">Packed depth/player-index pixels.</param>
        /// <param name="width">Depth frame width.</param>
        /// <param name="height">Depth frame height.</param>
        /// <param name="skeletons">Current skeletons for the frame.</param>
        public void Update(short[] depthData, int width, int height, Skeleton[] skeletons)
        {
            if (depthData == null || skeletons == null || width <= 0 || height <= 0)
            {
                this.outlinePoints.Clear();
                return;
            }

            this.scratchPoints.Clear();

            for (int skeletonIndex = 0; skeletonIndex < skeletons.Length; skeletonIndex++)
            {
                Skeleton skeleton = skeletons[skeletonIndex];
                if (skeleton == null || skeleton.TrackingState != SkeletonTrackingState.Tracked)
                {
                    continue;
                }

                Rectangle bounds = this.GetBoundingBox(skeleton, width, height);
                if (bounds == Rectangle.Empty)
                {
                    continue;
                }

                int playerIndex = skeletonIndex + 1; // depth player index is 1-based
                Color color = PlayerColors[skeletonIndex % PlayerColors.Length];

                this.ExtractEdges(depthData, width, height, bounds, playerIndex, color, this.scratchPoints);
            }

            this.outlinePoints.Clear();
            this.outlinePoints.AddRange(this.scratchPoints);
        }

        /// <summary>
        /// Draws the outline pixels into the provided sprite batch.
        /// </summary>
        /// <param name="spriteBatch">An already begun sprite batch.</param>
        public void Draw(SpriteBatch spriteBatch)
        {
            if (spriteBatch == null || this.outlinePoints.Count == 0)
            {
                return;
            }

            this.EnsurePixel();

            foreach (var point in this.outlinePoints)
            {
                Rectangle pixelRect = new Rectangle((int)point.Position.X, (int)point.Position.Y, 2, 2);
                spriteBatch.Draw(this.pixel, pixelRect, point.Color);
            }
        }

        /// <summary>
        /// Computes a loose 2D bounding box for a skeleton in depth space.
        /// </summary>
        private Rectangle GetBoundingBox(Skeleton skeleton, int width, int height)
        {
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

                Vector2 depthPoint = this.mapMethod(joint.Position);
                if (float.IsNaN(depthPoint.X) || float.IsNaN(depthPoint.Y))
                {
                    continue;
                }

                hasPoint = true;
                minX = MathHelper.Clamp(Math.Min(minX, depthPoint.X), 0, width - 1);
                minY = MathHelper.Clamp(Math.Min(minY, depthPoint.Y), 0, height - 1);
                maxX = MathHelper.Clamp(Math.Max(maxX, depthPoint.X), 0, width - 1);
                maxY = MathHelper.Clamp(Math.Max(maxY, depthPoint.Y), 0, height - 1);
            }

            if (!hasPoint)
            {
                return Rectangle.Empty;
            }

            const int padding = 20;
            int left = Math.Max(0, (int)minX - padding);
            int top = Math.Max(0, (int)minY - padding);
            int right = Math.Min(width - 1, (int)maxX + padding);
            int bottom = Math.Min(height - 1, (int)maxY + padding);

            int rectWidth = Math.Max(1, right - left);
            int rectHeight = Math.Max(1, bottom - top);
            return new Rectangle(left, top, rectWidth, rectHeight);
        }

        /// <summary>
        /// Extracts the edge pixels for the requested player and pushes them into the output list.
        /// </summary>
        private void ExtractEdges(
            short[] depthData,
            int width,
            int height,
            Rectangle bounds,
            int playerIndex,
            Color color,
            List<OutlinePoint> output)
        {
            int maxX = width - 1;
            int maxY = height - 1;

            int boundedRight = Math.Min(bounds.Right, width);
            int boundedBottom = Math.Min(bounds.Bottom, height);

            for (int y = bounds.Top; y < boundedBottom; y++)
            {
                int rowOffset = y * width;
                for (int x = bounds.Left; x < boundedRight; x++)
                {
                    if (GetPlayerIndex(depthData[rowOffset + x]) != playerIndex)
                    {
                        continue;
                    }

                    bool up = y > 0 && GetPlayerIndex(depthData[(y - 1) * width + x]) == playerIndex;
                    bool down = y < maxY && GetPlayerIndex(depthData[(y + 1) * width + x]) == playerIndex;
                    bool left = x > 0 && GetPlayerIndex(depthData[rowOffset + x - 1]) == playerIndex;
                    bool right = x < maxX && GetPlayerIndex(depthData[rowOffset + x + 1]) == playerIndex;

                    if (!(up && down && left && right))
                    {
                        output.Add(new OutlinePoint
                        {
                            Position = new Vector2(x, y),
                            Color = color
                        });
                    }
                }
            }
        }

        /// <summary>
        /// Extracts the 3-bit player index from a packed depth pixel.
        /// </summary>
        private static int GetPlayerIndex(short depthValue)
        {
            return depthValue & 0x0007;
        }

        /// <summary>
        /// Lazily creates the white pixel used for drawing.
        /// </summary>
        private void EnsurePixel()
        {
            if (this.pixel != null)
            {
                return;
            }

            this.pixel = new Texture2D(this.graphicsDevice, 1, 1, false, SurfaceFormat.Color);
            this.pixel.SetData(new[] { Color.White });
        }

        /// <summary>
        /// Lightweight value type representing a single outline pixel.
        /// </summary>
        private struct OutlinePoint
        {
            public Vector2 Position { get; set; }

            public Color Color { get; set; }
        }
    }
}
