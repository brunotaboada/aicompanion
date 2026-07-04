package io.aicompanion.console;

/**
 * Tiny stdout spinner with elapsed seconds. No-op when stdout is not a TTY,
 * so piped output stays clean. Thread-safe and idempotent stop().
 */
public final class Spinner {

    private static final String[] FRAMES = {
        "⠋","⠙","⠹","⠸","⠼",
        "⠴","⠦","⠧","⠇","⠏"
    };
    private static final long FRAME_MS = 80;
    private static final String CLEAR_LINE = "\r\u001b[K";

    private final String message;
    private volatile boolean running;
    private Thread thread;
    private long startedNs;

    public Spinner(String message) {
        this.message = message;
    }

    public synchronized void start() {
        if (!Ansi.enabled() || running) return;
        running = true;
        startedNs = System.nanoTime();
        thread = new Thread(this::loop, "aicompanion-spinner");
        thread.setDaemon(true);
        thread.start();
    }

    /** Stop the spinner and clear its line. Idempotent. */
    public synchronized void stop() {
        if (!running) return;
        running = false;
        try {
            thread.join(250);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        synchronized (System.out) {
            System.out.print(CLEAR_LINE);
            System.out.flush();
        }
    }

    private void loop() {
        int i = 0;
        try {
            while (running) {
                long elapsed = (System.nanoTime() - startedNs) / 1_000_000_000L;
                String frame = FRAMES[i++ % FRAMES.length];
                synchronized (System.out) {
                    System.out.print("\r" + Ansi.cyan(frame) + " " + message
                        + "  " + Ansi.dim("(" + elapsed + "s)") + "   ");
                    System.out.flush();
                }
                Thread.sleep(FRAME_MS);
            }
        } catch (InterruptedException ignored) {
            // exit loop
        }
    }
}
