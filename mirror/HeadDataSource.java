package mirror;

public interface HeadDataSource {
        int getHeadX();
        int getHeadY();
        double getHeadZ();
        int[] getOutlinePoints();
        int getOutlinePointStride();
        SilhouetteFrame getSilhouetteFrame();
}
