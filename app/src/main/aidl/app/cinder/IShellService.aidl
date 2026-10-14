package app.cinder;

// Runs shell commands for the sandbox's `rish`, inside a Shizuku user service (adb-shell uid).
interface IShellService {
    // Shizuku calls this (transaction 16777115) when it tears the service down.
    void destroy() = 16777114;

    // Starts `sh -c command` (or `sh -i` when interactive) with its stdio wired to the given fds.
    // Returns an id for waitFor/kill.
    int start(String command, boolean interactive, in ParcelFileDescriptor stdin,
              in ParcelFileDescriptor stdout, in ParcelFileDescriptor stderr) = 1;

    // Blocks until the command exits and all its output is written; returns its exit code.
    int waitFor(int id) = 2;

    void kill(int id) = 3;
}
