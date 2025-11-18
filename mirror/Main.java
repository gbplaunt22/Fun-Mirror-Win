package mirror;

import javax.swing.*;
import java.awt.*;

public class Main {

    public static void main(String[] args) {
        KinectBridge bridge = new KinectBridge();

        try {
            bridge.start();

            SwingUtilities.invokeLater(() -> {
            	
            	Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
            	
                JFrame frame = new JFrame("Fun Mirror Win");
                frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
                
                //Borderless transparent overlay
                frame.setUndecorated(true);
                frame.setBackground(new Color(0, 0, 0, 0));
                frame.setAlwaysOnTop(true);
                

                HeadTrackViz viz = new HeadTrackViz(bridge);
                viz.setOpaque(false);
                
                
                frame.setContentPane(viz);

                frame.setSize(screen); 
                frame.setLocation(0, 0);
                
                //frame.setLocationRelativeTo(null);
                
                frame.setVisible(true);
            });

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
