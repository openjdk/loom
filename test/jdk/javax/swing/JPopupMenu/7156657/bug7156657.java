/*
 * Copyright (c) 2012, 2021, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.util.concurrent.Callable;

import javax.swing.JFrame;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;

/*
   @test
   @key headful
   @bug 7156657 8186617
   @summary Version 7 doesn't support translucent popup menus against a
            translucent window
   @library ../../regtesthelpers
*/
public class bug7156657 {
    private static JFrame lowerFrame;

    private static JFrame frame;

    private static JPopupMenu popupMenu;

    public static void main(String[] args) throws Exception {
        final Robot robot = new Robot();

        Boolean skipTest = Util.invokeOnEDT(new Callable<Boolean>() {
            @Override
            public Boolean call() throws Exception {
                frame = createFrame();
                if (!frame.getGraphicsConfiguration().isTranslucencyCapable()) {
                    System.out.println("Translucency is not supported, the test skipped");

                    return true;
                }

                lowerFrame = createFrame();
                lowerFrame.getContentPane().setBackground(Color.RED);
                lowerFrame.setVisible(true);

                popupMenu = new JPopupMenu();
                popupMenu.setOpaque(false);
                popupMenu.add(new TransparentMenuItem("1111"));
                popupMenu.add(new TransparentMenuItem("2222"));
                popupMenu.add(new TransparentMenuItem("3333"));

                setOpaque(frame, false);
                JPanel pnContent = new JPanel();
                pnContent.setBackground(new Color(255, 255, 255, 128));
                frame.add(pnContent);
                frame.setVisible(true);

                return false;
            }
        });

        if (skipTest) {
            return;
        }

        robot.waitForIdle();

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                popupMenu.show(frame, 0, 0);
            }
        });

        robot.waitForIdle();

        Rectangle popupRectangle = Util.invokeOnEDT(new Callable<Rectangle>() {
            @Override
            public Rectangle call() throws Exception {
                return popupMenu.getBounds();
            }
        });

        BufferedImage redBackgroundCapture = robot.createScreenCapture(popupRectangle);

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                lowerFrame.getContentPane().setBackground(Color.GREEN);
            }
        });

        robot.waitForIdle();

        BufferedImage greenBackgroundCapture = robot.createScreenCapture(popupRectangle);

        if (Util.compareBufferedImages(redBackgroundCapture, greenBackgroundCapture)) {
            throw new RuntimeException("The test failed");
        }

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                popupMenu.setVisible(false);
                frame.dispose();
                lowerFrame.dispose();
            }
        });

        System.out.println("The test passed");
    }

    public static void setOpaque(Window window, boolean opaque) {
        Color bg = window.getBackground();
        if (bg == null) {
            bg = new Color(0, 0, 0, 0);
        }
        window.setBackground(new Color(bg.getRed(), bg.getGreen(), bg.getBlue(),
                                       opaque ? 255 : 0));
    }

    private static JFrame createFrame() {
        JFrame result = new JFrame();

        result.setLocation(0, 0);
        result.setSize(400, 300);
        result.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        result.setUndecorated(true);

        return result;
    }

    private static class TransparentMenuItem extends JMenuItem {
        public TransparentMenuItem(String text) {
            super(text);
            setOpaque(false);
        }

        @Override
        public void paint(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.5f));
            super.paint(g2);
            g2.dispose();
        }
    }
}
