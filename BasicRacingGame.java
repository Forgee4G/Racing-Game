import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

public class BasicRacingGame extends JPanel implements KeyListener, ActionListener {

    static final int WIDTH = 800;
    static final int HEIGHT = 600;

    int carX = 380;
    int carY = 500;
    int speed = 5;

    boolean up, down, left, right;

    Timer timer = new Timer(16, this);

    public BasicRacingGame() {
        setPreferredSize(new Dimension(WIDTH, HEIGHT));
        setBackground(Color.DARK_GRAY);
        setFocusable(true);
        addKeyListener(this);
        timer.start();
    }

    @Override
    public void paintComponent(Graphics g) {
        super.paintComponent(g);

        g.setColor(Color.GRAY);
        g.fillRect(200, 0, 400, HEIGHT);

        g.setColor(Color.RED);
        g.fillRect(carX, carY, 40, 60);
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        if (up) carY -= speed;
        if (down) carY += speed;
        if (left) carX -= speed;
        if (right) carX += speed;

        carX = Math.max(200, Math.min(carX, 560));
        carY = Math.max(0, Math.min(carY, HEIGHT - 60));

        repaint();
    }

    @Override public void keyPressed(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.VK_W) up = true;
        if (e.getKeyCode() == KeyEvent.VK_S) down = true;
        if (e.getKeyCode() == KeyEvent.VK_A) left = true;
        if (e.getKeyCode() == KeyEvent.VK_D) right = true;
    }

    @Override public void keyReleased(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.VK_W) up = false;
        if (e.getKeyCode() == KeyEvent.VK_S) down = false;
        if (e.getKeyCode() == KeyEvent.VK_A) left = false;
        if (e.getKeyCode() == KeyEvent.VK_D) right = false;
    }

    @Override public void keyTyped(KeyEvent e) {}

    public static void main(String[] args) {
        JFrame frame = new JFrame("Basic Racing Game");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setResizable(false);
        frame.add(new BasicRacingGame());
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }
}
