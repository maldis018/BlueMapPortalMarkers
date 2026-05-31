package dev.aldis.bluemapportalmarkers;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Thin logging helper providing a two-tier INFO/DEBUG split over the plugin
 * {@link Logger}.
 *
 * <p>Operational milestones use {@link #info}, which always reaches the server
 * console. Verbose, high-frequency diagnostics use {@link #debug}, which only
 * emits when the {@code logging.debug} config flag is on. Debug lines are
 * printed at {@link Level#INFO} with a {@code [DEBUG]} prefix so they appear on
 * the console/stdout without operators having to lower the server log level
 * (vanilla/Paper consoles suppress {@link Level#FINE}).</p>
 */
public final class Log {

    private final Logger logger;
    private volatile boolean debug;

    public Log(Logger logger, boolean debug) {
        this.logger = logger;
        this.debug = debug;
    }

    public boolean isDebug() {
        return debug;
    }

    /** Update the debug flag at runtime (used by {@code /bmportals reload}). */
    public void setDebug(boolean debug) {
        this.debug = debug;
    }

    /** Always-visible operational message. */
    public void info(String msg) {
        logger.info(msg);
    }

    /** Verbose diagnostic; emitted only when debug logging is enabled. */
    public void debug(String msg) {
        if (debug) {
            logger.info("[DEBUG] " + msg);
        }
    }

    public void warn(String msg) {
        logger.warning(msg);
    }

    public void warn(String msg, Throwable t) {
        logger.log(Level.WARNING, msg, t);
    }
}
