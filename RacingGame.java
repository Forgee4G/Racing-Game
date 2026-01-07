import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.AffineTransform;
import java.util.*;
import java.util.List;

/**
 * Frank's 2D Racing Game (Java Swing)
 * Features:
 * - Menu (track, vehicle, difficulty)
 * - 5-second countdown with visible 3..2..1..GO
 * - Player controls (WASD/Arrows)
 * - NPC racers follow waypoints
 * - Roadblocks/obstacles + collision/explosion
 * - Boost pads that temporarily increase speed
 * - 3 tracks + "terrain" friction differences
 * - Finish line + results + rewards/unlocks
 *
 * How to run:
 *  javac RacingGame.java
 *  java RacingGame
 */
public class RacingGame extends JFrame {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new RacingGame().setVisible(true));
    }

    public RacingGame() {
        setTitle("Frank's 2D Racing Game (Java Swing)");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setResizable(false);

        GamePanel panel = new GamePanel();
        setContentPane(panel);
        pack();
        setLocationRelativeTo(null);
    }
}

/* =========================
   GAME PANEL + MAIN LOOP
   ========================= */
class GamePanel extends JPanel implements ActionListener, KeyListener, MouseListener {
    // Window size
    static final int W = 1000;
    static final int H = 700;

    // Loop
    private final Timer timer = new Timer(16, this); // ~60 FPS
    private long lastTimeNanos = System.nanoTime();

    // Game state
    enum State { MENU, COUNTDOWN, RACE, EXPLODE, RESULTS }
    private State state = State.MENU;

    // Countdown
    private int countdownMs = 5000; // 5 seconds total
    private int goFlashMs = 900;    // show GO! briefly

    // Input
    private final Set<Integer> keysDown = new HashSet<>();

    // Selection / progression
    private int selectedTrack = 0;
    private int selectedVehicle = 0;
    private int selectedDifficulty = 1; // 0 easy,1 normal,2 hard,3 impossible
    private int coins = 0;

    // Unlocks (simple)
    private boolean[] trackUnlocked = new boolean[]{ true, false, false };
    private boolean[] vehicleUnlocked = new boolean[]{ true, false, false };

    // Prices
    private final int[] trackCost = new int[]{ 0, 80, 140 };
    private final int[] vehicleCost = new int[]{ 0, 100, 160 };

    // World objects
    private Track track;
    private PlayerCar player;
    private List<NPCCar> npcs = new ArrayList<>();
    private List<Obstacle> obstacles = new ArrayList<>();
    private List<BoostPad> boosts = new ArrayList<>();
    private FinishLine finishLine;

    // Race results
    private long raceStartMs = 0;
    private long raceEndMs = 0;
    private boolean playerFinished = false;
    private int playerPlace = 0;

    // Explosion
    private int explodeMs = 0;
    private double lastImpactSpeed = 0;

    // UI rectangles (menu buttons)
    private final Rectangle btnStart = new Rectangle(60, 560, 240, 55);
    private final Rectangle btnBuy = new Rectangle(320, 560, 240, 55);
    private final Rectangle btnReset = new Rectangle(580, 560, 240, 55);

    public GamePanel() {
        setPreferredSize(new Dimension(W, H));
        setFocusable(true);
        addKeyListener(this);
        addMouseListener(this);
        timer.start();
    }

    /* =========================
       CORE LOOP
       ========================= */
    @Override
    public void actionPerformed(ActionEvent e) {
        long now = System.nanoTime();
        double dt = (now - lastTimeNanos) / 1_000_000_000.0;
        lastTimeNanos = now;

        // Clamp dt to avoid huge jumps (e.g., if window moved)
        dt = Math.min(dt, 0.05);

        update(dt);
        repaint();
    }

    private void update(double dt) {
        switch (state) {
            case MENU -> {
                // no physics
            }
            case COUNTDOWN -> updateCountdown(dt);
            case RACE -> updateRace(dt);
            case EXPLODE -> updateExplode(dt);
            case RESULTS -> {
                // no physics
            }
        }
    }

    private void updateCountdown(double dt) {
        countdownMs -= (int)(dt * 1000);
        if (countdownMs <= 0) {
            state = State.RACE;
            raceStartMs = System.currentTimeMillis();
        }
    }

    private void updateRace(double dt) {
        if (track == null) return;

        // Player update
        player.update(dt, keysDown, track);

        // Boost pad check
        for (BoostPad b : boosts) {
            if (!b.used && b.getRect().intersects(player.getRect())) {
                b.used = true;
                player.activateBoost();
            }
        }

        // Player collision with obstacles (explode if impact speed is high)
        for (Obstacle ob : obstacles) {
            if (ob.getRect().intersects(player.getRect())) {
                double speed = player.speedMagnitude();
                // Push player out a bit and reduce speed
                player.bumpBack();
                player.vx *= 0.4;
                player.vy *= 0.4;

                if (speed > 220) { // threshold for explosion
                    lastImpactSpeed = speed;
                    startExplosion();
                    return;
                }
            }
        }

        // NPC updates
        for (NPCCar npc : npcs) {
            npc.update(dt, track);

            // NPC boost
            for (BoostPad b : boosts) {
                if (!b.used && b.getRect().intersects(npc.getRect())) {
                    b.used = true;
                    npc.activateBoost();
                }
            }

            // NPC obstacle collision (slow down)
            for (Obstacle ob : obstacles) {
                if (ob.getRect().intersects(npc.getRect())) {
                    npc.bumpBack();
                    npc.vx *= 0.3;
                    npc.vy *= 0.3;
                }
            }
        }

        // Finish detection
        if (!playerFinished && finishLine != null && finishLine.rect.intersects(player.getRect())) {
            playerFinished = true;
            raceEndMs = System.currentTimeMillis();
            // Determine place by counting who finished earlier (simple: compare lapProgress)
            playerPlace = computePlace();
            awardRewards(playerPlace);
            state = State.RESULTS;
        }
    }

    private void updateExplode(double dt) {
        explodeMs -= (int)(dt * 1000);
        if (explodeMs <= 0) {
            // restart from "checkpoint" (here: start position)
            resetRaceObjects();
            state = State.COUNTDOWN;
            countdownMs = 5000;
        }
    }

    private void startExplosion() {
        state = State.EXPLODE;
        explodeMs = 1100; // explosion animation time
    }

    /* =========================
       SETUP / RESET
       ========================= */
    private void startRace() {
        // Build the track based on selection
        track = TrackFactory.makeTrack(selectedTrack);

        // Player vehicle based on selection
        VehicleStats vs = VehicleStatsFactory.get(selectedVehicle);
        player = new PlayerCar(track.startX, track.startY, vs);

        // Finish line
        finishLine = new FinishLine(track.finishRect);

        // Build obstacles + boosts based on difficulty
        obstacles = new ArrayList<>();
        boosts = new ArrayList<>();
        npcs = new ArrayList<>();
        buildPickupsAndObstacles();

        // Create NPCs
        createNPCs();

        // Reset state
        playerFinished = false;
        playerPlace = 0;
        countdownMs = 5000;
        state = State.COUNTDOWN;
    }

    private void resetRaceObjects() {
        // Keep same track/selection, reset cars and pickups (but do NOT reset coins)
        Track t = TrackFactory.makeTrack(selectedTrack);
        track = t;

        VehicleStats vs = VehicleStatsFactory.get(selectedVehicle);
        player = new PlayerCar(track.startX, track.startY, vs);

        finishLine = new FinishLine(track.finishRect);

        obstacles = new ArrayList<>();
        boosts = new ArrayList<>();
        npcs = new ArrayList<>();
        buildPickupsAndObstacles();
        createNPCs();

        playerFinished = false;
        playerPlace = 0;
        raceStartMs = 0;
        raceEndMs = 0;
    }

    private void buildPickupsAndObstacles() {
        Random r = new Random();

        // Difficulty tuning
        int obsCount;
        int boostCount;

        switch (selectedDifficulty) {
            case 0 -> { obsCount = 6;  boostCount = 5; }
            case 1 -> { obsCount = 9;  boostCount = 5; }
            case 2 -> { obsCount = 13; boostCount = 4; }
            default -> { obsCount = 16; boostCount = 3; } // impossible
        }

        // Place obstacles on the track area but not too close to start
        for (int i = 0; i < obsCount; i++) {
            Rectangle rect = randomRectOnRoad(r, 36, 36, 120);
            obstacles.add(new Obstacle(rect));
        }

        // Place boosts
        for (int i = 0; i < boostCount; i++) {
            Rectangle rect = randomRectOnRoad(r, 30, 30, 120);
            boosts.add(new BoostPad(rect));
        }
    }

    private Rectangle randomRectOnRoad(Random r, int w, int h, int minFromStart) {
        Rectangle road = track.roadBounds;
        for (int tries = 0; tries < 500; tries++) {
            int x = road.x + r.nextInt(Math.max(1, road.width - w));
            int y = road.y + r.nextInt(Math.max(1, road.height - h));
            Rectangle candidate = new Rectangle(x, y, w, h);

            // Must be inside "road bounds"
            if (!road.contains(candidate)) continue;

            // Keep away from start area
            double dx = (x - track.startX);
            double dy = (y - track.startY);
            if (Math.hypot(dx, dy) < minFromStart) continue;

            // Avoid placing inside finish line
            if (track.finishRect.intersects(candidate)) continue;

            return candidate;
        }
        // Fallback
        return new Rectangle(road.x + 200, road.y + 200, w, h);
    }

    private void createNPCs() {
        int npcCount;
        switch (selectedDifficulty) {
            case 0 -> npcCount = 2;
            case 1 -> npcCount = 3;
            case 2 -> npcCount = 4;
            default -> npcCount = 5;
        }


        for (int i = 0; i < npcCount; i++) {
            VehicleStats npcStats = VehicleStatsFactory.get(0).copy(); // sports-like base
            npcStats.maxSpeed *= npcSpeedScale;
            npcStats.accel *= npcSpeedScale;
            npcStats.handling *= npcSpeedScale;

            // Spawn NPCs slightly behind/side
            double sx = track.startX - 30 - i * 18;
            double sy = track.startY + 40 + i * 28;

            NPCCar npc = new NPCCar(sx, sy, npcStats, track.waypoints);
            npcs.add(npc);
        }
    }

    private int computePlace() {
        // Determine place by "progress" along waypoint index at finish moment (simple and stable)
        // Player finished now. Assume NPC "virtual finish" by waypoint progress (or if they overlap finish too).
        int better = 0;
        for (NPCCar npc : npcs) {
            // If npc already at/near finish region, count as better if it has higher progress
            if (npc.waypointIndex > player.virtualWaypointIndex) better++;
        }
        return better + 1;
    }

    /* =========================
       DRAWING
       ========================= */
    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();

        // Smooth
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // Background
        g2.setColor(new Color(20, 20, 24));
        g2.fillRect(0, 0, W, H);

        // Draw depending on state
        if (state == State.MENU) {
            drawMenu(g2);
        } else {
            drawWorld(g2);
            drawHUD(g2);

            if (state == State.COUNTDOWN) drawCountdownOverlay(g2);
            if (state == State.EXPLODE) drawExplosionOverlay(g2);
            if (state == State.RESULTS) drawResultsOverlay(g2);
        }

        g2.dispose();
    }

    private void drawMenu(Graphics2D g2) {
        // Title
        g2.setColor(Color.WHITE);
        g2.setFont(new Font("Arial", Font.BOLD, 36));
        g2.drawString("Frank's 2D Racing Game", 60, 80);

        g2.setFont(new Font("Arial", Font.PLAIN, 16));
        g2.drawString("Controls: WASD or Arrow Keys. R to restart race (during race). ESC to return to Menu.", 60, 110);

        // Coins
        g2.setFont(new Font("Arial", Font.BOLD, 18));
        g2.drawString("Coins: " + coins, 60, 150);

        // Track selection
        g2.setFont(new Font("Arial", Font.BOLD, 20));
        g2.drawString("Select Track:", 60, 210);

        for (int i = 0; i < 3; i++) {
            Rectangle r = new Rectangle(60 + i * 260, 230, 240, 70);
            drawOptionBox(g2, r,
                    "Track " + (i + 1),
                    TrackFactory.trackName(i),
                    (i == selectedTrack),
                    trackUnlocked[i],
                    trackCost[i]
            );
        }

        // Vehicle selection
        g2.drawString("Select Vehicle:", 60, 340);

        for (int i = 0; i < 3; i++) {
            Rectangle r = new Rectangle(60 + i * 260, 360, 240, 70);
            VehicleStats vs = VehicleStatsFactory.get(i);
            String line2 = "Max " + (int)vs.maxSpeed + "  Acc " + (int)vs.accel + "  Handle " + (int)(vs.handling * 100);
            drawOptionBox(g2, r,
                    vs.name,
                    line2,
                    (i == selectedVehicle),
                    vehicleUnlocked[i],
                    vehicleCost[i]
            );
        }

        // Difficulty
        g2.drawString("Select Difficulty:", 60, 470);
        String[] diffs = { "Easy", "Normal", "Hard", "Impossible" };
        for (int i = 0; i < diffs.length; i++) {
            Rectangle r = new Rectangle(60 + i * 180, 490, 160, 45);
            drawSmallOption(g2, r, diffs[i], i == selectedDifficulty);
        }

        // Buttons
        drawButton(g2, btnStart, "START RACE");
        drawButton(g2, btnBuy, "BUY UNLOCK");
        drawButton(g2, btnReset, "RESET COINS");

        g2.setFont(new Font("Arial", Font.PLAIN, 14));
        g2.setColor(new Color(210, 210, 210));
        g2.drawString("Tip: Select a locked item, then click BUY UNLOCK (if you have enough coins).", 60, 640);
    }

    private void drawWorld(Graphics2D g2) {
        // Track
        track.draw(g2);

        // Boosts
        for (BoostPad b : boosts) b.draw(g2);

        // Obstacles
        for (Obstacle ob : obstacles) ob.draw(g2);

        // Finish line
        if (finishLine != null) finishLine.draw(g2);

        // NPCs
        for (NPCCar npc : npcs) npc.draw(g2, false);

        // Player
        if (player != null) player.draw(g2, true);
    }

    private void drawHUD(Graphics2D g2) {
        g2.setFont(new Font("Arial", Font.BOLD, 16));
        g2.setColor(Color.WHITE);

        // Track / vehicle / coins
        String tName = TrackFactory.trackName(selectedTrack);
        g2.drawString("Track: " + tName + "   Vehicle: " + VehicleStatsFactory.get(selectedVehicle).name
                + "   Difficulty: " + difficultyName(selectedDifficulty)
                + "   Coins: " + coins, 20, 25);

        if (state == State.RACE || state == State.EXPLODE || state == State.RESULTS) {
            long now = System.currentTimeMillis();
            long t = (raceStartMs == 0) ? 0 : (now - raceStartMs);
            if (state == State.RESULTS) t = (raceEndMs - raceStartMs);

            g2.drawString("Time: " + formatTime(t), 20, 50);

            // Player speed
            if (player != null) {
                g2.drawString("Speed: " + (int)player.speedMagnitude(), 20, 75);
            }
        }
    }

    private void drawCountdownOverlay(Graphics2D g2) {
        // Determine message: 5..4..3..2..1..GO, but only display 3..2..1..GO as requested.
        int secLeft = (int)Math.ceil(countdownMs / 1000.0);
        String msg;
        if (secLeft >= 4) msg = "READY";
        else if (secLeft == 3) msg = "3";
        else if (secLeft == 2) msg = "2";
        else if (secLeft == 1) msg = "1";
        else msg = "GO!";

        // Overlay
        g2.setColor(new Color(0, 0, 0, 160));
        g2.fillRect(0, 0, W, H);

        g2.setColor(Color.WHITE);
        g2.setFont(new Font("Arial", Font.BOLD, 90));
        int sw = g2.getFontMetrics().stringWidth(msg);
        g2.drawString(msg, (W - sw) / 2, H / 2);

        g2.setFont(new Font("Arial", Font.PLAIN, 18));
        String sub = "Movement locked during countdown";
        int sw2 = g2.getFontMetrics().stringWidth(sub);
        g2.drawString(sub, (W - sw2) / 2, H / 2 + 60);
    }

    private void drawExplosionOverlay(Graphics2D g2) {
        // Explosion centered on player
        if (player == null) return;

        // Darken
        g2.setColor(new Color(0, 0, 0, 120));
        g2.fillRect(0, 0, W, H);

        // Explosion animation: expanding circles
        int total = 1100;
        double p = 1.0 - (explodeMs / (double) total);
        p = Math.max(0, Math.min(1, p));

        int cx = (int)player.x;
        int cy = (int)player.y;
        int radius = (int)(20 + p * 140);

        g2.setColor(new Color(255, 160, 0, 180));
        g2.fillOval(cx - radius, cy - radius, radius * 2, radius * 2);

        int r2 = (int)(radius * 0.65);
        g2.setColor(new Color(255, 60, 0, 180));
        g2.fillOval(cx - r2, cy - r2, r2 * 2, r2 * 2);

        g2.setColor(Color.WHITE);
        g2.setFont(new Font("Arial", Font.BOLD, 28));
        String msg = "CRASH! Restarting...";
        int sw = g2.getFontMetrics().stringWidth(msg);
        g2.drawString(msg, (W - sw) / 2, 80);

        g2.setFont(new Font("Arial", Font.PLAIN, 16));
        String msg2 = "Impact speed: " + (int)lastImpactSpeed;
        int sw2 = g2.getFontMetrics().stringWidth(msg2);
        g2.drawString(msg2, (W - sw2) / 2, 105);
    }

    private void drawResultsOverlay(Graphics2D g2) {
        g2.setColor(new Color(0, 0, 0, 170));
        g2.fillRect(0, 0, W, H);

        g2.setColor(Color.WHITE);
        g2.setFont(new Font("Arial", Font.BOLD, 52));
        String title = "RESULTS";
        int sw = g2.getFontMetrics().stringWidth(title);
        g2.drawString(title, (W - sw) / 2, 170);

        g2.setFont(new Font("Arial", Font.BOLD, 28));
        String place = "Place: " + playerPlace;
        int swP = g2.getFontMetrics().stringWidth(place);
        g2.drawString(place, (W - swP) / 2, 260);

        long t = raceEndMs - raceStartMs;
        g2.setFont(new Font("Arial", Font.PLAIN, 22));
        String time = "Time: " + formatTime(t);
        int swT = g2.getFontMetrics().stringWidth(time);
        g2.drawString(time, (W - swT) / 2, 305);

        g2.setFont(new Font("Arial", Font.PLAIN, 18));
        String hint = "Press ENTER to return to Menu, or R to race again.";
        int swH = g2.getFontMetrics().stringWidth(hint);
        g2.drawString(hint, (W - swH) / 2, 380);
    }

    private void drawOptionBox(Graphics2D g2, Rectangle r, String line1, String line2,
                               boolean selected, boolean unlocked, int cost) {
        g2.setColor(selected ? new Color(50, 120, 220) : new Color(40, 40, 50));
        g2.fillRoundRect(r.x, r.y, r.width, r.height, 16, 16);

        g2.setColor(new Color(255, 255, 255, 40));
        g2.drawRoundRect(r.x, r.y, r.width, r.height, 16, 16);

        g2.setColor(Color.WHITE);
        g2.setFont(new Font("Arial", Font.BOLD, 16));
        g2.drawString(line1, r.x + 12, r.y + 26);

        g2.setFont(new Font("Arial", Font.PLAIN, 13));
        g2.drawString(line2, r.x + 12, r.y + 48);

        if (!unlocked) {
            g2.setColor(new Color(0, 0, 0, 150));
            g2.fillRoundRect(r.x, r.y, r.width, r.height, 16, 16);

            g2.setColor(Color.WHITE);
            g2.setFont(new Font("Arial", Font.BOLD, 16));
            String lock = "LOCKED (" + cost + " coins)";
            int sw = g2.getFontMetrics().stringWidth(lock);
            g2.drawString(lock, r.x + (r.width - sw) / 2, r.y + 42);
        }
    }

    private void drawSmallOption(Graphics2D g2, Rectangle r, String text, boolean selected) {
        g2.setColor(selected ? new Color(50, 120, 220) : new Color(55, 55, 65));
        g2.fillRoundRect(r.x, r.y, r.width, r.height, 14, 14);
        g2.setColor(new Color(255, 255, 255, 40));
        g2.drawRoundRect(r.x, r.y, r.width, r.height, 14, 14);

        g2.setColor(Color.WHITE);
        g2.setFont(new Font("Arial", Font.BOLD, 15));
        int sw = g2.getFontMetrics().stringWidth(text);
        g2.drawString(text, r.x + (r.width - sw) / 2, r.y + 30);
    }

    private void drawButton(Graphics2D g2, Rectangle r, String text) {
        g2.setColor(new Color(80, 80, 95));
        g2.fillRoundRect(r.x, r.y, r.width, r.height, 18, 18);
        g2.setColor(new Color(255, 255, 255, 45));
        g2.drawRoundRect(r.x, r.y, r.width, r.height, 18, 18);

        g2.setColor(Color.WHITE);
        g2.setFont(new Font("Arial", Font.BOLD, 16));
        int sw = g2.getFontMetrics().stringWidth(text);
        g2.drawString(text, r.x + (r.width - sw) / 2, r.y + 34);
    }


    private String formatTime(long ms) {
        long sec = ms / 1000;
        long rem = ms % 1000;
        long min = sec / 60;
        sec = sec % 60;
        return String.format("%d:%02d.%03d", min, sec, rem);
    }

    /* =========================
       INPUT
       ========================= */
    @Override
    public void keyPressed(KeyEvent e) {
        keysDown.add(e.getKeyCode());

        if (state == State.RESULTS) {
            if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                state = State.MENU;
            } else if (e.getKeyCode() == KeyEvent.VK_R) {
                // replay same settings
                startRace();
            }
        } else if (state == State.RACE || state == State.COUNTDOWN || state == State.EXPLODE) {
            if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                state = State.MENU;
            }
            if (e.getKeyCode() == KeyEvent.VK_R && state == State.RACE) {
                resetRaceObjects();
                state = State.COUNTDOWN;
                countdownMs = 5000;
            }
        }
    }

    @Override
    public void keyReleased(KeyEvent e) {
        keysDown.remove(e.getKeyCode());
    }

    @Override public void keyTyped(KeyEvent e) {}

    @Override
    public void mouseClicked(MouseEvent e) {
        if (state != State.MENU) return;

        Point p = e.getPoint();

        // Track boxes
        for (int i = 0; i < 3; i++) {
            Rectangle r = new Rectangle(60 + i * 260, 230, 240, 70);
            if (r.contains(p)) selectedTrack = i;
        }

        // Vehicle boxes
        for (int i = 0; i < 3; i++) {
            Rectangle r = new Rectangle(60 + i * 260, 360, 240, 70);
            if (r.contains(p)) selectedVehicle = i;
        }

        // Difficulty
        for (int i = 0; i < 4; i++) {
            Rectangle r = new Rectangle(60 + i * 180, 490, 160, 45);
            if (r.contains(p)) selectedDifficulty = i;
        }

        // Buttons
        if (btnStart.contains(p)) {
            if (!trackUnlocked[selectedTrack] || !vehicleUnlocked[selectedVehicle]) {
                // Can't start if selection locked
                Toolkit.getDefaultToolkit().beep();
                return;
            }
            startRace();
        }
        if (btnBuy.contains(p)) {
            buyUnlockForSelection();
        }
        if (btnReset.contains(p)) {
            coins = 0;
            trackUnlocked = new boolean[]{ true, false, false };
            vehicleUnlocked = new boolean[]{ true, false, false };
        }
    }

    private void buyUnlockForSelection() {
        // Priority: unlock selected track if locked, else unlock selected vehicle if locked
        if (!trackUnlocked[selectedTrack]) {
            int cost = trackCost[selectedTrack];
            if (coins >= cost) {
                coins -= cost;
                trackUnlocked[selectedTrack] = true;
            } else Toolkit.getDefaultToolkit().beep();
            return;
        }
        if (!vehicleUnlocked[selectedVehicle]) {
            int cost = vehicleCost[selectedVehicle];
            if (coins >= cost) {
                coins -= cost;
                vehicleUnlocked[selectedVehicle] = true;
            } else Toolkit.getDefaultToolkit().beep();
        }
    }

    @Override public void mousePressed(MouseEvent e) {}
    @Override public void mouseReleased(MouseEvent e) {}
    @Override public void mouseEntered(MouseEvent e) {}
    @Override public void mouseExited(MouseEvent e) {}
}

/* =========================
   TRACKS
   ========================= */
class Track {
    String name;
    Rectangle roadBounds;     // simple road area (rectangle) for collision bounds
    Rectangle finishRect;     // finish line rectangle
    double startX, startY;
    double terrainFriction;   // affects handling / speed bleed
    List<Point> waypoints;    // NPC route guidance

    // Visual theme
    Color terrainColor;
    Color roadColor;

    public Track(String name, Rectangle roadBounds, Rectangle finishRect, double startX, double startY,
                 double terrainFriction, Color terrainColor, Color roadColor, List<Point> waypoints) {
        this.name = name;
        this.roadBounds = roadBounds;
        this.finishRect = finishRect;
        this.startX = startX;
        this.startY = startY;
        this.terrainFriction = terrainFriction;
        this.terrainColor = terrainColor;
        this.roadColor = roadColor;
        this.waypoints = waypoints;
    }

    public void draw(Graphics2D g2) {
        // terrain background
        g2.setColor(terrainColor);
        g2.fillRect(0, 0, GamePanel.W, GamePanel.H);

        // road
        g2.setColor(roadColor);
        g2.fillRoundRect(roadBounds.x, roadBounds.y, roadBounds.width, roadBounds.height, 80, 80);

        // "track markings"
        g2.setColor(new Color(255, 255, 255, 45));
        g2.setStroke(new BasicStroke(2f));
        g2.drawRoundRect(roadBounds.x + 18, roadBounds.y + 18, roadBounds.width - 36, roadBounds.height - 36, 70, 70);

        // waypoints preview (for debugging; comment out if you want)
        // g2.setColor(new Color(255,255,255,35));
        // for (Point p : waypoints) g2.fillOval(p.x - 6, p.y - 6, 12, 12);
    }
}

    static Track makeTrack(int i) {
        // Use simple rectangular roads + waypoint loops (keeps project manageable)
        if (i == 0) {
            Rectangle road = new Rectangle(80, 80, 840, 540);
            Rectangle finish = new Rectangle(80 + 820, 330, 20, 80); // right side
            List<Point> wps = Arrays.asList(
                    new Point(140, 140),
                    new Point(860, 140),
                    new Point(860, 560),
                    new Point(140, 560)
            );
            return new Track("City Circuit", road, finish, 160, 340,
                    0.985, new Color(30, 55, 40), new Color(55, 55, 60), wps);
        } else if (i == 1) {
            Rectangle road = new Rectangle(90, 110, 820, 500);
            Rectangle finish = new Rectangle(90, 330, 20, 80); // left side
            List<Point> wps = Arrays.asList(
                    new Point(820, 160),
                    new Point(160, 160),
                    new Point(160, 560),
                    new Point(820, 560)
            );
            return new Track("Desert Loop", road, finish, 820, 360,
                    0.975, new Color(160, 130, 70), new Color(80, 70, 60), wps);
        } else {
            Rectangle road = new Rectangle(110, 90, 800, 520);
            Rectangle finish = new Rectangle(480, 90, 80, 20); // top
            List<Point> wps = Arrays.asList(
                    new Point(860, 520),
                    new Point(860, 140),
                    new Point(160, 140),
                    new Point(160, 520)
            );
            return new Track("Snow Run", road, finish, 520, 540,
                    0.968, new Color(190, 210, 225), new Color(70, 80, 90), wps);
        }
    }
}

/* =========================
   VEHICLE STATS
   ========================= */
class VehicleStats {
    String name;
    double maxSpeed;   // px/s
    double accel;      // px/s^2
    double handling;   // turn responsiveness
    Color color;

    VehicleStats(String name, double maxSpeed, double accel, double handling, Color color) {
        this.name = name;
        this.maxSpeed = maxSpeed;
        this.accel = accel;
        this.handling = handling;
        this.color = color;
    }

    VehicleStats copy() {
        return new VehicleStats(name, maxSpeed, accel, handling, color);
    }
}

/* =========================
   CARS (PLAYER + NPC)
   ========================= */
abstract class Car {
    // Position is center
    double x, y;
    double vx, vy;
    double angleRad = 0;
    VehicleStats stats;

    // Boost
    int boostMs = 0;

    // Size
    int w = 28;
    int h = 16;

    // For "ranking"
    int waypointIndex = 0;

    Car(double x, double y, VehicleStats stats) {
        this.x = x;
        this.y = y;
        this.stats = stats;
    }

    Rectangle getRect() {
        return new Rectangle((int)(x - w / 2), (int)(y - h / 2), w, h);
    }

    double speedMagnitude() {
        return Math.hypot(vx, vy);
    }

    void activateBoost() {
        boostMs = 850; // boost duration
    }

    void bumpBack() {
        // push opposite velocity (or a small random if stopped)
        double s = Math.max(60, speedMagnitude());
        double nx = (s == 0) ? -1 : -(vx / (s));
        double ny = (s == 0) ? 0 : -(vy / (s));
        x += nx * 10;
        y += ny * 10;
    }

    void applyFriction(Track track) {
        // Terrain friction affects how quickly speed bleeds off
        vx *= track.terrainFriction;
        vy *= track.terrainFriction;
    }

    void clampToRoad(Track track) {
        // If outside road bounds, push back and slow
        Rectangle r = track.roadBounds;
        double left = r.x + w / 2.0;
        double right = r.x + r.width - w / 2.0;
        double top = r.y + h / 2.0;
        double bottom = r.y + r.height - h / 2.0;

        boolean hit = false;
        if (x < left) { x = left; hit = true; }
        if (x > right) { x = right; hit = true; }
        if (y < top) { y = top; hit = true; }
        if (y > bottom) { y = bottom; hit = true; }

        if (hit) {
            vx *= 0.6;
            vy *= 0.6;
        }
    }

    void draw(Graphics2D g2, boolean isPlayer) {
        // Car body rotated by angle
        AffineTransform old = g2.getTransform();
        g2.translate(x, y);
        g2.rotate(angleRad);

        g2.setColor(stats.color);
        g2.fillRoundRect(-w/2, -h/2, w, h, 8, 8);

        // windshield
        g2.setColor(new Color(255, 255, 255, 120));
        g2.fillRoundRect(-w/6, -h/3, w/3, h/2, 6, 6);

        // outline
        g2.setColor(new Color(0, 0, 0, 70));
        g2.drawRoundRect(-w/2, -h/2, w, h, 8, 8);

        // Boost effect
        if (boostMs > 0) {
            g2.setColor(new Color(255, 255, 255, 130));
            g2.fillOval(-w/2 - 8, -h/4, 10, 10);
        }

        // Player marker
        if (isPlayer) {
            g2.setColor(new Color(255, 255, 255, 200));
            g2.drawOval(-w/2 - 6, -h/2 - 6, w + 12, h + 12);
        }

        g2.setTransform(old);
    }
}

class PlayerCar extends Car {
    // used for a simple place calculation
    int virtualWaypointIndex = 0;

    PlayerCar(double x, double y, VehicleStats stats) {
        super(x, y, stats);
        angleRad = -Math.PI / 2; // face up-ish
    }

    void update(double dt, Set<Integer> keysDown, Track track) {
        // Countdown lock is handled by GamePanel state; but still keep controls here
        boolean up = keysDown.contains(KeyEvent.VK_W) || keysDown.contains(KeyEvent.VK_UP);
        boolean down = keysDown.contains(KeyEvent.VK_S) || keysDown.contains(KeyEvent.VK_DOWN);
        boolean left = keysDown.contains(KeyEvent.VK_A) || keysDown.contains(KeyEvent.VK_LEFT);
        boolean right = keysDown.contains(KeyEvent.VK_D) || keysDown.contains(KeyEvent.VK_RIGHT);

        // Turning based on current speed (more speed => more turn responsiveness)
        double speed = speedMagnitude();
        double turn = stats.handling * (0.9 + Math.min(speed / 250.0, 1.0)) * 2.2;

        if (left) angleRad -= turn * dt;
        if (right) angleRad += turn * dt;

        // Throttle
        double maxS = stats.maxSpeed;
        if (boostMs > 0) maxS *= 1.28;

        if (up) {
            vx += Math.cos(angleRad) * stats.accel * dt;
            vy += Math.sin(angleRad) * stats.accel * dt;
        }
        if (down) {
            vx -= Math.cos(angleRad) * stats.accel * 0.65 * dt;
            vy -= Math.sin(angleRad) * stats.accel * 0.65 * dt;
        }

        // Clamp speed
        double s = speedMagnitude();
        if (s > maxS) {
            double scale = maxS / s;
            vx *= scale;
            vy *= scale;
        }

        // Update position
        x += vx * dt;
        y += vy * dt;

        // Friction + road bounds
        applyFriction(track);
        clampToRoad(track);

        // Boost timer
        if (boostMs > 0) boostMs -= (int)(dt * 1000);

        // Approx progress: nearest waypoint index (simple)
        virtualWaypointIndex = estimateWaypointIndex(track.waypoints);
    }

    private int estimateWaypointIndex(List<Point> waypoints) {
        // Find closest waypoint as "progress indicator"
        double best = Double.MAX_VALUE;
        int idx = 0;
        for (int i = 0; i < waypoints.size(); i++) {
            Point p = waypoints.get(i);
            double d = (p.x - x) * (p.x - x) + (p.y - y) * (p.y - y);
            if (d < best) {
                best = d;
                idx = i;
            }
        }
        return idx;
    }
}

class NPCCar extends Car {
    private final List<Point> waypoints;

    NPCCar(double x, double y, VehicleStats stats, List<Point> waypoints) {
        super(x, y, stats);
        this.waypoints = waypoints;
        // Slightly different color tint so NPCs don't look identical
        stats.color = new Color(
                Math.max(0, stats.color.getRed() - 40),
                Math.max(0, stats.color.getGreen() - 40),
                Math.max(0, stats.color.getBlue() - 40)
        );
    }

    void update(double dt, Track track) {
        if (waypoints.isEmpty()) return;

        // Seek current waypoint
        Point target = waypoints.get(waypointIndex % waypoints.size());
        double dx = target.x - x;
        double dy = target.y - y;
        double dist = Math.hypot(dx, dy);

        if (dist < 60) {
            waypointIndex++;
            target = waypoints.get(waypointIndex % waypoints.size());
            dx = target.x - x;
            dy = target.y - y;
            dist = Math.hypot(dx, dy);
        }

        // Desired angle towards target
        double desired = Math.atan2(dy, dx);
        double diff = normalizeAngle(desired - angleRad);

        // Turn toward desired
        double turnRate = stats.handling * 2.0;
        angleRad += clamp(diff, -turnRate * dt, turnRate * dt);

        // Throttle forward
        double maxS = stats.maxSpeed;
        if (boostMs > 0) maxS *= 1.20;

        vx += Math.cos(angleRad) * stats.accel * 0.72 * dt;
        vy += Math.sin(angleRad) * stats.accel * 0.72 * dt;

        // Clamp speed
        double s = speedMagnitude();
        if (s > maxS) {
            double scale = maxS / s;
            vx *= scale;
            vy *= scale;
        }

        // Move
        x += vx * dt;
        y += vy * dt;

        // Friction + road bounds
        applyFriction(track);
        clampToRoad(track);

        // Boost timer
        if (boostMs > 0) boostMs -= (int)(dt * 1000);
    }

    private double normalizeAngle(double a) {
        while (a > Math.PI) a -= Math.PI * 2;
        while (a < -Math.PI) a += Math.PI * 2;
        return a;
    }

    private double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}

/* =========================
   OBSTACLES / BOOSTS / FINISH
   ========================= */
class Obstacle {
    Rectangle rect;
    Obstacle(Rectangle r) { this.rect = r; }

    Rectangle getRect() { return rect; }

    void draw(Graphics2D g2) {
        g2.setColor(new Color(25, 25, 25));
        g2.fillRoundRect(rect.x, rect.y, rect.width, rect.height, 10, 10);
        g2.setColor(new Color(255, 80, 80, 140));
        g2.setStroke(new BasicStroke(3f));
        g2.drawRoundRect(rect.x, rect.y, rect.width, rect.height, 10, 10);
    }
}

class BoostPad {
    Rectangle rect;
    boolean used = false;
    BoostPad(Rectangle r) { this.rect = r; }

    Rectangle getRect() { return rect; }

    void draw(Graphics2D g2) {
        if (used) {
            g2.setColor(new Color(255, 255, 255, 40));
            g2.fillRoundRect(rect.x, rect.y, rect.width, rect.height, 10, 10);
            return;
        }
        g2.setColor(new Color(80, 220, 120));
        g2.fillRoundRect(rect.x, rect.y, rect.width, rect.height, 10, 10);
        g2.setColor(new Color(255, 255, 255, 120));
        g2.setStroke(new BasicStroke(2f));
        g2.drawRoundRect(rect.x, rect.y, rect.width, rect.height, 10, 10);

        // small arrow
        int cx = rect.x + rect.width / 2;
        int cy = rect.y + rect.height / 2;
        Polygon arrow = new Polygon(
                new int[]{ cx - 8, cx - 8, cx + 10 },
                new int[]{ cy - 10, cy + 10, cy },
                3
        );
        g2.fillPolygon(arrow);
    }
}

class FinishLine {
    Rectangle rect;
    FinishLine(Rectangle r) { this.rect = r; }

    void draw(Graphics2D g2) {
        // checker pattern
        g2.setColor(Color.WHITE);
        g2.fillRect(rect.x, rect.y, rect.width, rect.height);

        g2.setColor(Color.BLACK);
        int size = 10;
        for (int y = rect.y; y < rect.y + rect.height; y += size) {
            for (int x = rect.x; x < rect.x + rect.width; x += size) {
                if (((x + y) / size) % 2 == 0) {
                    g2.fillRect(x, y, size, size);
                }
            }
        }

        g2.setColor(new Color(255, 255, 255, 140));
        g2.setStroke(new BasicStroke(2f));
        g2.drawRect(rect.x, rect.y, rect.width, rect.height);
    }
}
