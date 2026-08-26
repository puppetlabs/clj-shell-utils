package com.puppetlabs.puppetserver;

import java.io.InputStream;
import java.io.IOException;
import org.apache.commons.io.IOUtils;

public class ExecutionResult {
    private final InputStream output;
    private final String error;
    private final int exitCode;
    private final boolean timedOut;

    public ExecutionResult(InputStream output, String error, int exitCode) {
        this(output, error, exitCode, false);
    }

    public ExecutionResult(InputStream output, String error, int exitCode, boolean timedOut) {
        this.output = output;
        this.error = error;
        this.exitCode = exitCode;
        this.timedOut = timedOut;
    }

    public int getExitCode() {
        return exitCode;
    }

    public String getOutput() throws IOException {
        return IOUtils.toString(output, "UTF-8");
    }

    public InputStream getOutputAsStream() {
        return output;
    }

    public String getError() {
        return error;
    }

    /**
     * @return true if the process was forcibly terminated for exceeding an
     * execution timeout configured via ExecutionOptions#setTimeoutMillis;
     * false if the process completed on its own (including completing with
     * a non-zero exit code) or if no timeout was configured.
     */
    public boolean isTimedOut() {
        return timedOut;
    }
}
