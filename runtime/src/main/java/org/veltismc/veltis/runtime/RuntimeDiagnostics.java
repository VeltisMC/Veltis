package org.veltismc.veltis.runtime;

import org.veltismc.veltis.runtime.binding.RuntimeBindingResult;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class RuntimeDiagnostics {

    private RuntimeDiagnostics() {
    }

    public static DiagnosticResult check(MinecraftRuntime runtime) {
        var results = new ArrayList<Check>();
        var start = Instant.now();

        results.add(checkBinding(runtime));
        results.add(checkState(runtime));
        results.add(checkPort(runtime));

        var duration = Duration.between(start, Instant.now());
        var allPassed = results.stream().allMatch(Check::passed);
        return new DiagnosticResult(allPassed, results, duration);
    }

    private static Check checkBinding(MinecraftRuntime runtime) {
        try {
            var adapter = runtime.adapter();
            var result = adapter instanceof ReflectiveBindingProvider rbp
                ? rbp.bindingResult()
                : RuntimeBindingResult.simulation("No binding provider");
            if (result.isReal()) {
                return new Check("Server Classes", true,
                    "Bound to " + result.serverJar() + " (" + result.mode() + " mode)");
            }
            return new Check("Server Classes", true,
                "Running in " + result.mode() + " mode");
        } catch (Exception e) {
            return new Check("Server Classes", false, "Error: " + e.getMessage());
        }
    }

    private static Check checkState(MinecraftRuntime runtime) {
        var state = runtime.state();
        var running = runtime.isRunning();
        var ready = runtime.isReady();
        var ok = running && state == RuntimeState.RUNNING;
        return new Check("Runtime State", ok,
            "State=" + state + " running=" + running + " ready=" + ready);
    }

    private static Check checkPort(MinecraftRuntime runtime) {
        var port = runtime.adapter().port();
        var listening = false;
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 200);
            listening = true;
        } catch (IOException e) {
            listening = false;
        }
        return new Check("Port " + port, listening,
            listening ? "Listening on port " + port : "Port " + port + " not reachable");
    }

    public record Check(String name, boolean passed, String message) {
    }

    public record DiagnosticResult(boolean allPassed, List<Check> checks, Duration duration) {

        public void print(java.io.PrintStream out) {
            out.println("=== Runtime Diagnostics ===");
            out.println("Duration: " + duration.toMillis() + "ms");
            out.println("Result: " + (allPassed ? "PASSED" : "FAILED"));
            for (var check : checks) {
                var icon = check.passed() ? "[PASS]" : "[FAIL]";
                out.println("  " + icon + " " + check.name() + ": " + check.message());
            }
        }
    }

    public interface ReflectiveBindingProvider {
        RuntimeBindingResult bindingResult();
    }
}


