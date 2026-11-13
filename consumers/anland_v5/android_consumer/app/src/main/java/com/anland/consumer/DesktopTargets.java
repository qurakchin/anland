package com.anland.consumer;

import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Which container a window is looking at, and how to reach into its session.
 *
 * <p>DroidSpaces keeps one socket per container session under
 * {@code /data/local/Droidspaces/Net/}, chosen by the daemon and therefore new
 * every time the container starts. The fixed entry point the app used to rely on
 * goes stale on the first restart. Next to the sockets, the runtime also writes
 * {@code Pids/<name>.asock} holding the path of the container's current socket,
 * which is the only stable container → socket mapping on the device; that is
 * what {@link #running()} reads.
 *
 * <p>Everything here talks to the device through {@code su}, because
 * {@code /data/local/Droidspaces} is not readable by an untrusted app. Names are
 * validated before they reach a command line: they arrive from a preference the
 * user can edit, and a container name with a semicolon in it would be a root
 * shell command otherwise.
 */
final class DesktopTargets {
    private static final String TAG = "AnlandTargets";

    private static final String RUNTIME_DIR = "/data/local/Droidspaces";
    private static final String PIDS_DIR = RUNTIME_DIR + "/Pids";
    private static final String BINARY = RUNTIME_DIR + "/bin/droidspaces";

    private static final long LIST_TIMEOUT_MS = 4000L;
    private static final long LOCK_TIMEOUT_MS = 6000L;

    /** One running container and the Anland socket it is serving right now. */
    static final class Container {
        final String name;
        final String socket;

        Container(String name, String socket) {
            this.name = name;
            this.socket = socket;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private DesktopTargets() {
    }

    /** A container name is only ever used verbatim in a command line. */
    static boolean isSafeName(String name) {
        if (name == null || name.isEmpty() || name.length() > 64)
            return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.';
            if (!ok)
                return false;
        }
        return true;
    }

    /**
     * Every container whose Anland socket is still a listening socket.
     *
     * <p>The {@code .asock} files of stopped containers stay behind, so the file
     * existing says nothing; {@code -S} on the path it names does.
     */
    static List<Container> running() {
        List<Container> containers = new ArrayList<>();
        String command = "for f in " + PIDS_DIR + "/*.asock; do "
                + "[ -f \"$f\" ] || continue; "
                + "n=${f##*/}; n=${n%.asock}; "
                + "s=$(cat \"$f\" 2>/dev/null); "
                + "[ -n \"$s\" ] && [ -S \"$s\" ] && echo \"$n|$s\"; "
                + "done";
        SuCommand.CommandResult result = new SuCommand.SuRunner().run(command, LIST_TIMEOUT_MS);
        if (!result.isClean()) {
            Log.w(TAG, "listing containers failed: " + result.stderr.trim());
            return containers;
        }
        for (String line : result.stdout.split("\n")) {
            int bar = line.indexOf('|');
            if (bar <= 0)
                continue;
            String name = line.substring(0, bar).trim();
            String socket = line.substring(bar + 1).trim();
            if (isSafeName(name) && socket.startsWith(RUNTIME_DIR + "/"))
                containers.add(new Container(name, socket));
        }
        return containers;
    }

    /** The socket a container is serving now, or null when it is not running. */
    static String socketOf(String name) {
        if (!isSafeName(name))
            return null;
        String command = "cat " + SuCommand.shellQuote(PIDS_DIR + "/" + name + ".asock")
                + " 2>/dev/null";
        SuCommand.CommandResult result = new SuCommand.SuRunner().run(command, LIST_TIMEOUT_MS);
        if (!result.isClean())
            return null;
        String socket = result.stdout.trim();
        return socket.isEmpty() ? null : socket;
    }

    /** The container currently serving {@code socket}, or null. */
    static String containerFor(String socket) {
        if (socket == null || socket.isEmpty())
            return null;
        for (Container container : running()) {
            if (socket.equals(container.socket))
                return container.name;
        }
        return null;
    }

    /**
     * Locks a container's desktop session.
     *
     * <p>There is no logind inside the container, so neither KDE's idle detection
     * nor its power management can fire; the session has to be locked from
     * outside. {@code org.freedesktop.ScreenSaver} is served by kscreenlocker and
     * needs only the session bus address, so the first bus that answers wins and
     * no user id is assumed.
     */
    static String lockCommand(String container) {
        if (!isSafeName(container))
            return null;
        // The session bus only answers a peer running as the user that owns
        // /run/user/<id>; a root peer gets "NoReply" from the bus. The owner is
        // read from the directory rather than assumed to be uid 1000.
        String script = "for b in /run/user/*/bus; do "
                + "[ -S \"$b\" ] || continue; "
                + "u=$(stat -c %U \"${b%/bus}\" 2>/dev/null) || continue; "
                + "[ -n \"$u\" ] || continue; "
                + "su - \"$u\" -c \"DBUS_SESSION_BUS_ADDRESS=unix:path=$b "
                + "qdbus6 org.freedesktop.ScreenSaver /ScreenSaver Lock\" "
                + ">/dev/null 2>&1 && exit 0; "
                + "done; exit 1";
        return BINARY + " -n " + container + " run -- sh -c " + SuCommand.shellQuote(script);
    }

    /** Runs {@link #lockCommand} and reports whether the session locked. */
    static boolean lock(String container) {
        String command = lockCommand(container);
        if (command == null)
            return false;
        SuCommand.CommandResult result = new SuCommand.SuRunner().run(command, LOCK_TIMEOUT_MS);
        boolean ok = result.isClean();
        if (!ok)
            Log.w(TAG, "locking " + container + " failed: " + result.stderr.trim());
        return ok;
    }
}
