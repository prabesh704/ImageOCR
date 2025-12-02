package com.vitalsoft.imageocr;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;

/**
 * A custom borderless, resizable viewer window
 * with image zoom, pan, and OCR text on the right side.
 */
public class ImageViewerWindow extends JFrame {

    private BufferedImage image;
    private double zoom = 1.0;
    private double minZoom = 0.1;
    private double maxZoom = 8.0;

    private int panX = 0;
    private int panY = 0;
    private int lastDragX, lastDragY;

    private boolean fitToWindow = false;

    private ZoomPanel imagePanel;
    private JLabel textLabel;
    private JSlider zoomSlider;

    public ImageViewerWindow(BufferedImage img, String ocrText) {
        this.image = img;

        setUndecorated(false);       // borderless = true if you want, false = window controls
        setTitle("Image Viewer");
        setMinimumSize(new Dimension(500, 300));

        // Create layout
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        split.setResizeWeight(0.75);
        split.setDividerSize(6);

        // IMAGE PANEL
        imagePanel = new ZoomPanel();
        JScrollPane imageScroll = new JScrollPane(imagePanel);
        imageScroll.getVerticalScrollBar().setUnitIncrement(20);
        imageScroll.getHorizontalScrollBar().setUnitIncrement(20);

        split.setLeftComponent(imageScroll);

        // TEXT PANEL
        textLabel = new JLabel("<html>" + ocrText.replace("\n", "<br>") + "</html>");
        textLabel.setBorder(new EmptyBorder(10, 10, 10, 10));

        JScrollPane textScroll = new JScrollPane(textLabel);
        split.setRightComponent(textScroll);

        // ZOOM SLIDER
        zoomSlider = new JSlider(10, 800, 100);
        zoomSlider.addChangeListener(e -> {
            zoom = zoomSlider.getValue() / 100.0;
            fitToWindow = false;
            imagePanel.repaint();
        });

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(new JLabel("    Zoom "), BorderLayout.WEST);
        bottom.add(zoomSlider, BorderLayout.CENTER);

        // Window layout
        setLayout(new BorderLayout());
        add(split, BorderLayout.CENTER);
        add(bottom, BorderLayout.SOUTH);

        // First layout
        setSize(1200, 800);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        setupMouseControls();
        setVisible(true);
    }

    // ---------------------------------------------------------
    // Mouse Controls: Zoom (wheel), Pan (drag), Fit-to-window (double-click)
    // ---------------------------------------------------------
    private void setupMouseControls() {
        // Mouse Wheel = Zoom
        imagePanel.addMouseWheelListener(e -> {
            double oldZoom = zoom;

            if (e.isControlDown()) zoom += (e.getWheelRotation() < 0 ? 0.05 : -0.05);
            else zoom += (e.getWheelRotation() < 0 ? 0.1 : -0.1);

            zoom = Math.max(minZoom, Math.min(maxZoom, zoom));
            zoomSlider.setValue((int) (zoom * 100));

            fitToWindow = false;
            imagePanel.repaint();
        });

        // Drag to pan
        imagePanel.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                lastDragX = e.getX();
                lastDragY = e.getY();
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                // Double-click = fit-to-window
                if (e.getClickCount() == 2) {
                    fitToWindow = !fitToWindow;
                    if (fitToWindow) {
                        zoomToFit();
                    }
                    imagePanel.revalidate();
                    imagePanel.repaint();
                }
            }
        });

        imagePanel.addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {
                panX += e.getX() - lastDragX;
                panY += e.getY() - lastDragY;

                lastDragX = e.getX();
                lastDragY = e.getY();

                fitToWindow = false;
                imagePanel.repaint();
            }
        });

        // Auto-fit on window resize if in fit mode
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                if (fitToWindow) {
                    zoomToFit();
                    imagePanel.repaint();
                }
            }
        });
    }

    private void zoomToFit() {
        if (image == null) return;

        double wRatio = (double) imagePanel.getWidth() / image.getWidth();
        double hRatio = (double) imagePanel.getHeight() / image.getHeight();
        zoom = Math.min(wRatio, hRatio);
        zoomSlider.setValue((int) (zoom * 100));
    }

    // ---------------------------------------------------------
    // Inner Panel that actually draws the transformed image
    // ---------------------------------------------------------
    private class ZoomPanel extends JPanel {

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);

            if (image == null) return;

            Graphics2D g2 = (Graphics2D) g;

            if (fitToWindow) zoomToFit();

            // Smooth scaling
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);

            // Create transform
            AffineTransform tx = new AffineTransform();
            tx.translate(panX, panY);
            tx.scale(zoom, zoom);

            g2.drawImage(image, tx, null);
        }

        @Override
        public Dimension getPreferredSize() {
            if (image == null) return new Dimension(200, 200);
            return new Dimension(
                    (int) (image.getWidth() * zoom),
                    (int) (image.getHeight() * zoom)
            );
        }
    }
}
