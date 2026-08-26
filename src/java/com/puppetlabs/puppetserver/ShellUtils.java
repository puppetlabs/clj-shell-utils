package com.puppetlabs.puppetserver;

import org.apache.commons.exec.DefaultExecuteResultHandler;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.PumpStreamHandler;
import org.apache.commons.exec.CommandLine;
import org.apache.commons.io.output.TeeOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.File;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public class ShellUtils {

    public static class ExecutionOptions {
        private boolean combineStdoutStderr = false;
        private Map<String, String> env = null;
        private InputStream stdin = null;
        private String cwd = null;
        private Long timeoutMillis = null;

        public boolean getCombineStdoutStderr() {
            return combineStdoutStderr;
        }

        public void setCombineStdoutStderr(boolean combineStdoutStderr) {
            this.combineStdoutStderr = combineStdoutStderr;
        }

        /**
         * The maximum amount of time, in milliseconds, to allow the process to
         * run before it is forcibly terminated. A value of {@code null} (the
         * default) means no timeout is enforced and the caller will block on
         * the process indefinitely, matching the historical behavior of this
         * class.
         */
        public Long getTimeoutMillis() {
            return timeoutMillis;
        }

        public void setTimeoutMillis(Long timeoutMillis) {
            this.timeoutMillis = timeoutMillis;
        }

        public InputStream getStdin() {
            return stdin;
        }

        public void setStdin(InputStream stdin) {
            this.stdin = stdin;
        }

        public Map<String, String> getEnv() {
            return env;
        }

        public void setEnv(Map<String, String> env) {
            this.env = env;
        }

        public String getWorkingDirectory() {
            return cwd;
        }

        public void setWorkingDirectory(String cwd) {
            this.cwd = cwd;
        }
    }

    private static final Logger log = LoggerFactory.getLogger(ShellUtils.class);

    // Once a timed-out process has been sent destroyForcibly() (SIGKILL on
    // POSIX systems), this is how long we're willing to wait for commons-exec's
    // own bookkeeping thread to notice the process has exited and finish
    // tearing down its stream pumps before we give up and return whatever
    // output was captured so far. SIGKILL cannot be blocked, deferred, or
    // ignored by the target process, so in practice this returns almost
    // immediately; the bound exists only as a defensive backstop against
    // pathological OS-level hangs (e.g. a process stuck in uninterruptible
    // I/O wait) so that a caller's configured timeout can never be turned
    // into an unbounded one by this bookkeeping step.
    private static final long POST_KILL_GRACE_PERIOD_MILLIS = 5000;

    // Polling interval used only by pollForProcess() below, while waiting for
    // a launched Process reference to show up.
    private static final long PROCESS_REF_POLL_INTERVAL_MILLIS = 10;

    /**
     * Polls the given reference for a non-null Process, for up to maxWaitMillis,
     * sleeping briefly between checks. Used to guard against the narrow race
     * where a caller's :timeout-ms is small enough that we notice the timeout
     * before the background execution thread has recorded the Process it just
     * launched.
     *
     * @return the Process once available, or null if maxWaitMillis elapses first.
     */
    private static Process pollForProcess(AtomicReference<Process> processRef, long maxWaitMillis)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + maxWaitMillis;
        Process process;
        while ((process = processRef.get()) == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(PROCESS_REF_POLL_INTERVAL_MILLIS);
        }
        return process;
    }

    /**
     * Takes a prepared CommandLine instance and executes it using some sane
     * defaults and a DefaultExecutor. Also makes a delicious pancake.
     *
     * @param commandLine CommandLine instance to execute
     * @param options optional object [ExecutionOptions] to control behavior; may be null.
     *                If options.getTimeoutMillis() is non-null, the process will be
     *                forcibly terminated (via Process#destroyForcibly()) if it has not
     *                completed within that many milliseconds, and the returned
     *                ExecutionResult's isTimedOut() will be true. If it is null (the
     *                default), the call blocks on the process indefinitely, as before.
     * @return An ExecutionResult with output[String], error[String],
     *                  the exit code[Integer] of the process, and whether the
     *                  process was killed for exceeding its timeout[boolean]
     *
     * @throws InterruptedException
     * @throws IOException
     */
    private static ExecutionResult executeExecutor(CommandLine commandLine,
                                                   ExecutionOptions options)
            throws InterruptedException, IOException {
        if (options == null) {
            options = new ExecutionOptions();
        }

        final Long timeoutMillis = options.getTimeoutMillis();
        // When a timeout is configured, capture a reference to the live
        // Process as soon as it's launched, so that it can be forcibly
        // terminated later if it overruns its timeout. Process#destroy()
        // (used internally by commons-exec's own ExecuteWatchdog) merely
        // sends SIGTERM, which some shells defer or ignore entirely while a
        // foreground child of their own is running -- exactly the "hung
        // policy-executable" scenario this timeout exists to guard against.
        // destroyForcibly() (SIGKILL) cannot be deferred or ignored, so we
        // manage the Process reference ourselves rather than relying on
        // ExecuteWatchdog.
        final AtomicReference<Process> processRef = new AtomicReference<>();
        DefaultExecutor executor = (timeoutMillis == null)
                ? new DefaultExecutor()
                : new DefaultExecutor() {
                    @Override
                    protected Process launch(CommandLine command, Map<String, String> env, File dir)
                            throws IOException {
                        Process process = super.launch(command, env, dir);
                        processRef.set(process);
                        return process;
                    }
                };

        ByteArrayOutputStream errStream = new ByteArrayOutputStream();
        // TODO the nice thing here would be to set up a piped stream
        // arrangement like:
        //    PipedOutputStream stdoutOutputStream = new PipedOutputStream();
        //    PipedInputStream stdoutInputStream = new PipedInputStream(stdoutOutputStream);
        // but this requires that the input stream be read on a different thread
        // than this one. this is currently out of scope.
        ByteArrayOutputStream stdoutOutputStream = new ByteArrayOutputStream();
        PumpStreamHandler streamHandler;
        if (options.getCombineStdoutStderr()) {
            log.debug("Combining STDOUT/STDERR for external command '" + commandLine.toString() + "'");
            streamHandler = new PumpStreamHandler(
                    stdoutOutputStream,
                    new TeeOutputStream(stdoutOutputStream, errStream),
                    options.getStdin());
        } else {
            streamHandler = new PumpStreamHandler(stdoutOutputStream, errStream,
                    options.getStdin());
        }

        // Don't throw exception on non-zero exit code
        executor.setExitValues(null);

        // Set up the handlers
        executor.setStreamHandler(streamHandler);

        // Set up cwd
        String cwd = options.getWorkingDirectory();
        if (cwd != null) {
            executor.setWorkingDirectory(new File(cwd));
        }

        Integer exitCode;
        boolean timedOut = false;

        if (timeoutMillis == null) {
            exitCode = executor.execute(commandLine, options.getEnv());
        } else {
            DefaultExecuteResultHandler resultHandler = new DefaultExecuteResultHandler();
            executor.execute(commandLine, options.getEnv(), resultHandler);
            resultHandler.waitFor(Duration.ofMillis(timeoutMillis));

            if (resultHandler.hasResult()) {
                exitCode = resultHandler.getExitValue();
            } else {
                timedOut = true;
                log.warn("External command '" + commandLine.toString() + "' exceeded its " +
                        timeoutMillis + "ms timeout and is being forcibly terminated");

                // On a very small :timeout-ms, it's possible for the waitFor()
                // above to expire before the background execution thread has
                // actually reached our launch() override and recorded the
                // Process. Poll briefly for it rather than giving up
                // immediately -- launching is normally near-instantaneous
                // once execute() has been invoked, so this should resolve
                // within a handful of iterations in practice.
                Process process = pollForProcess(processRef, POST_KILL_GRACE_PERIOD_MILLIS);
                if (process != null) {
                    process.destroyForcibly();
                } else {
                    log.warn("External command '" + commandLine.toString() + "' timed out, but " +
                            "no Process handle was available to terminate; it may not have " +
                            "finished starting yet");
                }

                resultHandler.waitFor(Duration.ofMillis(POST_KILL_GRACE_PERIOD_MILLIS));
                if (resultHandler.hasResult()) {
                    exitCode = resultHandler.getExitValue();
                } else {
                    log.warn("External command '" + commandLine.toString() + "' did not report " +
                            "an exit status within " + POST_KILL_GRACE_PERIOD_MILLIS +
                            "ms of being forcibly terminated; giving up on waiting for it");
                    exitCode = -1;
                }
            }

            if (resultHandler.hasResult() && resultHandler.getException() != null) {
                throw resultHandler.getException();
            }
        }

        ByteArrayInputStream stdoutInputStream = new ByteArrayInputStream(stdoutOutputStream.toByteArray());
        String stdErr = errStream.toString();

        if ( ! stdErr.isEmpty() ) {
            log.warn("Executed an external process which logged to STDERR: " + stdErr);
        }

        return new ExecutionResult(stdoutInputStream, stdErr, exitCode, timedOut);
    }

    /**
     * Executes the given command in a separate process.
     *
     * @param commandLine the command to execute.
     * @param options optional object [ExecutionOptions] to control behavior; may be null.
     *
     * @return An ExecutionResult with output[String], error[String], and
     *                 the exit code[Integer] of the process
     *
     * @throws InterruptedException
     */
    protected static ExecutionResult executeCommand(CommandLine commandLine,
                                                    ExecutionOptions options)
            throws InterruptedException {
        try {
            return executeExecutor(commandLine, options);
        } catch (IOException e) {
            // this nonsense is due to a weird edge-case incompatibility between JDK8
            // and apache commons-exec.  See SERVER-1116; hopefully we can remove this
            // conditional once that is resolved.
            if (e.getMessage() == "Stream closed") {
                log.warn("An error occurred while executing the command '" + commandLine.getExecutable() +
                        ".  The most likely culprit is that you are on JDK8, " +
                        "and we executed an external process with data on its STDIN that was not " +
                        "consumed by the process.  Please make sure the command above processes STDIN " +
                        "correctly.  For more information, see " +
                        "https://tickets.puppetlabs.com/browse/SERVER-1116 .  If you do not believe " +
                        "that this is the root cause of this error message, please file a bug at " +
                        "https://tickets.puppetlabs.com/browse/SERVER.");
            }
            throw new IllegalStateException(
                    "Exception while executing '" + commandLine.getExecutable() + "': " + e.getMessage(),
                    e);
        }
    }

    /**
     * Executes the given command in a separate process.
     *
     * @param command the command [String] to execute. arguments can be
     *                included in the string.
     * @return An ExecutionResult with output[String], error[String], and
     *                the exit code[Integer] of the process
     *
     * @throws InterruptedException
     * @throws IOException
     */
    public static ExecutionResult executeCommand(String command)
            throws InterruptedException, IOException {
        CommandLine commandLine = CommandLine.parse(command);

        return executeCommand(commandLine, null);
    }

    /**
     * Executes the given command in a separate process.
     *
     *
     * @param command the command [String] to execute. arguments can be
     *                included in the string.
     * @param options optional object [ExecutionOptions] to control behavior; may be null.
     * @return An ExecutionResult with output[String], error[String], and
     *                the exit code[Integer] of the process
     *
     * @throws InterruptedException
     * @throws IOException
     */
    public static ExecutionResult executeCommand(String command, ExecutionOptions options)
            throws InterruptedException, IOException {
        CommandLine commandLine = CommandLine.parse(command);

        return executeCommand(commandLine, options);
    }

    /**
     * Executes the given command in a separate process.
     *
     * @param command the command [String] to execute.
     * @param arguments arguments [Array of Strings] to add to the command being executed
     * @return An ExecutionResult with output[String], error[String], and
     *                 the exit code[Integer] of the process
     *
     * @throws InterruptedException
     * @throws IOException
     */
    public static ExecutionResult executeCommand(String command, String[] arguments)
            throws InterruptedException, IOException {
        return executeCommand(command, arguments, null);
    }

    /**
     * Executes the given command in a separate process.
     *
     * @param command the command [String] to execute.
     * @param arguments arguments [Array of Strings] to add to the command being executed
     * @param options optional object [ExecutionOptions] to control behavior; may be null.
     *
     * @return An ExecutionResult with output[String], error[String], and
     *                 the exit code[Integer] of the process
     *
     * @throws InterruptedException
     * @throws IOException
     */
    public static ExecutionResult executeCommand(String command, String[] arguments,
                                                 ExecutionOptions options)
            throws InterruptedException, IOException {
        CommandLine commandLine = new CommandLine(command);
        commandLine.addArguments(arguments, false);

        return executeCommand(commandLine, options);
    }


}
