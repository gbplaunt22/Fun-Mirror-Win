package mirror;

import javax.swing.*;

public class Main {

    public static void main(String[] args) {
        KinectBridge bridge = new KinectBridge();

        try {
            bridge.start();

            SwingUtilities.invokeLater(() -> {
                JFrame frame = new JFrame("Fun Mirror Win");
                frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

                HeadTrackViz viz = new HeadTrackViz(bridge);
                frame.setContentPane(viz);

                frame.setSize(800, 600); // match your monitor later
                frame.setLocationRelativeTo(null);
                frame.setVisible(true);
            });

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
