import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;
import java.util.List;

public class BasicRacer extends JPanel implements ActionListener, KeyListener {

    static final int W = 900;
    static final int H = 600;

    static final int ROAD_X = 220;
    static final int ROAD_W = 460;

    double carX = ROAD_X + ROAD_W / 2.0 - 20;
    double carY = H - 110;
    double vx = 0, vy = 0;

    double accel = 900;
    double friction = 0.92;
    double maxSpeed = 420;
    boolean up, down, left, right;

    boolean won = false;

    boolean timerRunning = false;
    long raceStartMs = 0;
    long raceEndMs = 0;

    private final List<Rectangle> roadblocks = new ArrayList<>();

    Timer timer = new Timer(16, this); // ~60 FPS
    long lastNanos = System.nanoTime();

    public BasicRacer() {
        setPreferredSize(new Dimension(W, H));
        setFocusable(true);
        addKeyListener(this);

        roadblocks.add(new Rectangle(ROAD_X + 60, 420, 70, 30));
        roadblocks.add(new Rectangle(ROAD_X + 280, 360, 90, 30));
        roadblocks.add(new Rectangle(ROAD_X + 140, 280, 80, 30));
        roadblocks.add(new Rectangle(ROAD_X + 310, 210, 70, 30));
        roadblocks.add(new Rectangle(ROAD_X + 90, 140, 90, 30));

        timer.start();
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        long now = System.nanoTime();
        double dt = (now - lastNanos) / 1_000_000_000.0;
        lastNanos = now;

        dt = Math.min(dt, 0.05);

        update(dt);
        repaint();
    }

    private void update(double dt) {
        if (won) return;

        if (!timerRunning && (up || down || left || right)) {
            timerRunning = true;
            raceStartMs = System.currentTimeMillis();
        }
        double oldX = carX;
        double oldY = carY;

        if (up)    vy -= accel * dt;
        if (down)  vy += accel * dt;
        if (left)  vx -= accel * dt;
        if (right) vx += accel * dt;

        double speed = Math.hypot(vx, vy);
        if (speed > maxSpeed) {
            double s = maxSpeed / speed;
            vx *= s;
            vy *= s;
        }

        carX += vx * dt;
        carY += vy * dt;

        vx *= friction;
        vy *= friction;

        double minX = ROAD_X;
        double maxX = ROAD_X + ROAD_W - 40;
        double minY = 0;
        double maxY = H - 70;

        carX = Math.max(minX, Math.min(maxX, carX));
        carY = Math.max(minY, Math.min(maxY, carY));

        Rectangle carRect = getCarRect();
        for (Rectangle block : roadblocks) {
            if (carRect.intersects(block)) {
                carX = oldX;
                carY = oldY;

                vx *= 0.25;
                vy *= 0.25;

                break;
            }
        }

        if (carY <= 40) {
            won = true;
            raceEndMs = System.currentTimeMillis();
        }
    }

    private Rectangle getCarRect() {
        return new Rectangle((int)carX, (int)carY, 40, 70);
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        g2.setColor(new Color(25, 90, 45));
        g2.fillRect(0, 0, W, H);

        g2.setColor(new Color(0, 0, 0, 60));
        g2.fillRoundRect(ROAD_X + 7, 7, ROAD_W, H - 14, 40, 40);

        g2.setColor(new Color(60, 60, 65));
        g2.fillRoundRect(ROAD_X, 0, ROAD_W, H, 40, 40);

        g2.setColor(new Color(240, 240, 240, 160));
        int centerX = ROAD_X + ROAD_W / 2;
        for (int y = 20; y < H; y += 55) {
            g2.fillRoundRect(centerX - 4, y, 8, 28, 8, 8);
        }

        g2.setColor(Color.WHITE);
        g2.fillRect(ROAD_X, 35, ROAD_W, 10);

        drawRoadblocks(g2);

        drawCar(g2, (int)carX, (int)carY);

        g2.setColor(Color.WHITE);
        g2.setFont(new Font("Arial", Font.BOLD, 18));
        g2.drawString("Controls: WASD or Arrow Keys | R = Restart", 20, 25);

        g2.setFont(new Font("Arial", Font.BOLD, 18));
        g2.drawString("Time: " + formatTime(getCurrentRaceTimeMs()), 20, 50);

        if (won) {
            g2.setFont(new Font("Arial", Font.BOLD, 54));
            String msg = "YOU WIN!";
            int sw = g2.getFontMetrics().stringWidth(msg);
            g2.drawString(msg, (W - sw) / 2, H / 2);

            g2.setFont(new Font("Arial", Font.PLAIN, 20));
            String msg2 = "Final Time: " + formatTime(raceEndMs - raceStartMs) + "   (Press R to play again)";
            int sw2 = g2.getFontMetrics().stringWidth(msg2);
            g2.drawString(msg2, (W - sw2) / 2, H / 2 + 40);
        }

        g2.dispose();
    }

    private void drawRoadblocks(Graphics2D g2) {
        for (Rectangle b : roadblocks) {
            g2.setColor(new Color(25, 25, 25));
            g2.fillRoundRect(b.x, b.y, b.width, b.height, 10, 10);

            g2.setColor(new Color(255, 80, 80, 170));
            g2.setStroke(new BasicStroke(3f));
            g2.drawRoundRect(b.x, b.y, b.width, b.height, 10, 10);

            g2.setColor(new Color(255, 255, 255, 70));
            g2.drawLine(b.x + 6, b.y + b.height - 4, b.x + b.width - 6, b.y + 4);
        }
    }

    private long getCurrentRaceTimeMs() {
        if (!timerRunning) return 0;
        if (won) return raceEndMs - raceStartMs;
        return System.currentTimeMillis() - raceStartMs;
    }

    private String formatTime(long ms) {
        long sec = ms / 1000;
        long rem = ms % 1000;
        long min = sec / 60;
        sec = sec % 60;
        return String.format("%d:%02d.%03d", min, sec, rem);
    }

    private void drawCar(Graphics2D g2, int x, int y) {
        int w = 40, h = 70;

        g2.setColor(new Color(0, 0, 0, 80));
        g2.fillOval(x + 4, y + 8, w - 8, h - 10);

        GradientPaint body = new GradientPaint(x, y, new Color(220, 50, 50),
                x, y + h, new Color(140, 10, 10));
        g2.setPaint(body);
        g2.fillRoundRect(x, y, w, h, 18, 18);

        g2.setColor(new Color(255, 255, 255, 160));
        g2.fillRoundRect(x + w / 2 - 4, y + 6, 8, h - 12, 10, 10);

        g2.setColor(new Color(170, 220, 255, 150));
        g2.fillRoundRect(x + 7, y + 14, w - 14, 16, 12, 12);

        g2.setColor(new Color(20, 20, 22));
        g2.fillRoundRect(x - 5, y + 12, 10, 14, 10, 10);
        g2.fillRoundRect(x + w - 5, y + 12, 10, 14, 10, 10);
        g2.fillRoundRect(x - 5, y + h - 26, 10, 14, 10, 10);
        g2.fillRoundRect(x + w - 5, y + h - 26, 10, 14, 10, 10);

        g2.setColor(new Color(0, 0, 0, 90));
        g2.setStroke(new BasicStroke(2f));
        g2.drawRoundRect(x, y, w, h, 18, 18);
    }

    @Override
    public void keyPressed(KeyEvent e) {
        int k = e.getKeyCode();
        if (k == KeyEvent.VK_W || k == KeyEvent.VK_UP) up = true;
        if (k == KeyEvent.VK_S || k == KeyEvent.VK_DOWN) down = true;
        if (k == KeyEvent.VK_A || k == KeyEvent.VK_LEFT) left = true;
        if (k == KeyEvent.VK_D || k == KeyEvent.VK_RIGHT) right = true;

        if (k == KeyEvent.VK_R) restart();
    }

    @Override
    public void keyReleased(KeyEvent e) {
        int k = e.getKeyCode();
        if (k == KeyEvent.VK_W || k == KeyEvent.VK_UP) up = false;
        if (k == KeyEvent.VK_S || k == KeyEvent.VK_DOWN) down = false;
        if (k == KeyEvent.VK_A || k == KeyEvent.VK_LEFT) left = false;
        if (k == KeyEvent.VK_D || k == KeyEvent.VK_RIGHT) right = false;
    }

    @Override public void keyTyped(KeyEvent e) {}

    private void restart() {
        carX = ROAD_X + ROAD_W / 2.0 - 20;
        carY = H - 110;
        vx = 0; vy = 0;
        won = false;

        timerRunning = false;
        raceStartMs = 0;
        raceEndMs = 0;
    }

    public static void main(String[] args) {
        JFrame f = new JFrame("Basic Racer (Roadblocks + Timer)");
        f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        f.setResizable(false);
        f.setContentPane(new BasicRacer());
        f.pack();
        f.setLocationRelativeTo(null);
        f.setVisible(true);
    }
}
