package com.capman.dialer;

/**
 * The visual theme of the incoming call screen.
 *
 * A theme changes the LOOK only; the mechanic stays the same everywhere - drag
 * the answer object onto the target, drag the reject object onto the answer
 * object. That way the gesture a user has learned does not break when the theme
 * changes, and adding a theme means writing a few drawing routines, nothing
 * more.
 */
public final class CallThemes {

    public static final int PACMAN = 0;
    public static final int PLAIN = 1;
    public static final int ROCKET = 2;
    public static final int BALLOON = 3;
    public static final int FOOTBALL = 4;
    public static final int CAT = 5;

    /** The order shown in settings; the first one is the default. */
    public static final int[] ALL = {PACMAN, PLAIN, ROCKET, BALLOON, FOOTBALL, CAT};

    private CallThemes() {
    }

    public static String label(int theme) {
        switch (theme) {
            case PLAIN: return "Plain";
            case ROCKET: return "Rocket and meteor";
            case BALLOON: return "Balloon and needle";
            case FOOTBALL: return "Football";
            case CAT: return "Cat and fish";
            default: return "Chomper";
        }
    }

    public static String hint(int theme) {
        switch (theme) {
            case PLAIN: return "Take the green button to the target, the red one onto it";
            case ROCKET: return "Take the rocket to the planet, the meteor onto the rocket";
            case BALLOON: return "Take the balloon to the cloud, the needle onto the balloon";
            case FOOTBALL: return "Take the ball to the goal, the red card onto the ball";
            case CAT: return "Take the cat to the fish, the water drop onto the cat";
            default: return "Take the chomper to the handset, the skull onto the chomper";
        }
    }

    /** A small preview glyph for the settings list. */
    public static String emoji(int theme) {
        switch (theme) {
            case PLAIN: return "📞";
            case ROCKET: return "🚀";
            case BALLOON: return "🎈";
            case FOOTBALL: return "⚽";
            case CAT: return "🐱";
            default: return "🟡";
        }
    }

    // ------------------------------------------------------------------ colours

    /** Main colour of the draggable "answer" object. */
    public static int handleColor(int theme) {
        switch (theme) {
            case PLAIN: return 0xFF2EE59D;
            case ROCKET: return 0xFFD8DEE9;
            case BALLOON: return 0xFFFF5C7A;
            case FOOTBALL: return 0xFFF2F4F7;
            case CAT: return 0xFFF2A25C;
            default: return 0xFFFFD426;
        }
    }

    /** Colour of the target; the path band and the pellets follow it. */
    public static int answerColor(int theme) {
        switch (theme) {
            case ROCKET: return 0xFF7C6CF0;
            case BALLOON: return 0xFF9FD3FF;
            case FOOTBALL: return 0xFF2EE59D;
            case CAT: return 0xFF57D9A3;
            default: return 0xFF2EE59D;
        }
    }

    /** Colour of the reject object. */
    public static int rejectColor(int theme) {
        switch (theme) {
            case ROCKET: return 0xFFFF8A3D;
            case BALLOON: return 0xFFB9C2CE;
            case CAT: return 0xFF4FC3F7;
            default: return 0xFFFF4D6D;
        }
    }
}
