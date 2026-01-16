import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;


public class FrankRacingGame extends JFrame {

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new FrankRacingGame().setVisible(true));
    }

    public FrankRacingGame() {
        setTitle("Frank's Racing Game");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setResizable(false);
        setContentPane(new GamePanel());
        pack();
        setLocationRelativeTo(null);
    }
}


class GamePanel extends JPanel implements ActionListener, KeyListener, MouseListener {

    static final int W = 1000;
    static final int H = 700;

    private final Timer timer = new Timer(16, this); // ~60 FPS
    private long lastNanos = System.nanoTime();

    enum State { MENU, COUNTDOWN, RACE, EXPLODE, RESULTS }
    private State state = State.MENU;

    private int countdownMs = 5000;

    private final Set<Integer> keys = new HashSet<>();

    private PlayerCar player;
    private Track currentTrack;
    private java.util.List<NPCCar> npcs = new ArrayList<>();
    private java.util.List<BoostPad> boosts = new ArrayList<>();
    private java.util.List<Obstacle> obstacles = new ArrayList<>();

    private long raceStartTime;
    private long raceEndTime;
    private int place = 0;

    public GamePanel() {
        setPreferredSize(new Dimension(W, H));
        setFocusable(true);
        addKeyListener(this);
        addMouseListener(this);
        timer.start();
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        long now = System.nanoTime();
        double dt = (now - lastNanos) / 1_000_000_000.0;
        lastNanos = now;

        update(dt);
        repaint();
    }

    private void update(double dt) {
        switch (state) {
            case MENU -> {
            }
            case COUNTDOWN -> {
                countdownMs -= (int)(dt * 1000);
                if (countdownMs <= 0) {
                    raceStartTime = System.currentTimeMillis();
                    state = State.RACE;
                }
            }
            case RACE -> updateRace(dt);
            case EXPLODE -> {
            }
            case RESULTS -> {
            }
        }
    }

    private void updateRace(double dt) {
        if (player == null || currentTrack == null) return;

        player.update(dt, keys, currentTrack);

        for (BoostPad b : boosts) {
            if (!b.used && b.rect.intersects(player.getRect())) {
                b.used = true;
                player.activateBoost();
            }
        }

        for (Obstacle o : obstacles) {
            if (o.rect.intersects(player.getRect())) {
                player.slowDown();
            }
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        g2.setColor(Color.DARK_GRAY);
        g2.fillRect(0, 0, W, H);

        g2.setColor(Color.WHITE);
        g2.setFont(new Font("Arial", Font.BOLD, 28));
        g2.drawString("STATE: " + state, 40, 60);

        if (state == State.COUNTDOWN) {
            int sec = (int)Math.ceil(countdownMs / 1000.0);
            g2.drawString("Starting in: " + sec, W / 2 - 100, H / 2);
        }

        g2.dispose();
    }

    @Override
    public void keyPressed(KeyEvent e) {
        keys.add(e.getKeyCode());

        if (state == State.MENU && e.getKeyCode() == KeyEvent.VK_ENTER) {
            countdownMs = 5000;
            state = State.COUNTDOWN;
        }
    }

    @Override
    public void keyReleased(KeyEvent e) {
        keys.remove(e.getKeyCode());
    }

    @Override public void keyTyped(KeyEvent e) {}

    @Override public void mouseClicked(MouseEvent e) {}
    @Override public void mousePressed(MouseEvent e) {}
    @Override public void mouseReleased(MouseEvent e) {}
    @Override public void mouseEntered(MouseEvent e) {}
    @Override public void mouseExited(MouseEvent e) {}
}
