package org.veltismc.nativeengine;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The one place the distribution is allowed to touch the network.
 *
 * <p>Every request it makes obeys four rules:
 * <ul>
 *   <li><b>HTTPS only</b>, with a single carve-out for plain HTTP to a
 *       loopback address, where no packet leaves the machine — that carve-out
 *       is what lets the test suite run against a local {@code HttpServer}
 *       without weakening what a real installation accepts.</li>
 *   <li><b>Official hosts only</b> (this repository's GitHub hosts) plus the
 *       host of an explicitly configured repository override: an operator who
 *       points the server at their own mirror takes responsibility for it, but
 *       a manifest can never redirect the downloader somewhere new.</li>
 *   <li><b>Every redirect hop is re-checked</b> against the same rules;
 *       redirects are followed manually, never by the HTTP client itself.</li>
 *   <li><b>Bounded</b>: connect and request timeouts come from configuration,
 *       bodies are capped, and an archive download must be exactly the size
 *       the signed manifest declared.</li>
 * </ul>
 */
final class NativeDistributionTransport implements AutoCloseable {

    private static final String USER_AGENT =
            "VeltisMC-NativeDistribution/1.0 (+https://github.com/VeltisMC/Veltis)";

    private static final int MAX_REDIRECTS = 5;
    private static final int COPY_BUFFER_BYTES = 64 * 1024;

    /** Asset names are file names: no separators, so no path to escape into. */
    private static final Pattern ASSET_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,199}");

    private static final Set<String> OFFICIAL_HOSTS = Set.of(
            "github.com",
            "api.github.com",
            "raw.githubusercontent.com",
            "objects.githubusercontent.com",
            "release-assets.githubusercontent.com");

    private final HttpClient client;
    private final URI repositoryBase;
    private final String repositoryHost;
    private final int timeoutSeconds;

    /**
     * @param repository     the base URL assets are resolved against; must be
     *                       HTTPS (or loopback HTTP for tests)
     * @param timeoutSeconds per-request timeout
     * @throws IOException when the repository URL is not acceptable
     */
    NativeDistributionTransport(String repository, int timeoutSeconds) throws IOException {
        this.repositoryBase = validateRepository(repository);
        this.repositoryHost = repositoryBase.getHost().toLowerCase(Locale.ROOT);
        this.timeoutSeconds = Math.max(1, timeoutSeconds);
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(this.timeoutSeconds))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * Validates and normalises a repository URL: HTTPS (or HTTP to loopback),
     * a host, no query or fragment, and a trailing slash so that resolving an
     * asset name against it cannot drop the last path segment.
     */
    static URI validateRepository(String repository) throws IOException {
        if (repository == null || repository.isBlank()) {
            throw new IOException("the repository URL is empty");
        }
        final URI uri;
        try {
            uri = URI.create(repository.trim());
        } catch (IllegalArgumentException e) {
            throw new IOException("the repository URL is not a URL: " + repository, e);
        }
        var scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        var host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IOException("the repository URL has no host: " + repository);
        }
        if (uri.getQuery() != null || uri.getFragment() != null) {
            throw new IOException("the repository URL must not carry a query or fragment: " + repository);
        }
        requireTransport(scheme, host.toLowerCase(Locale.ROOT), repository);
        var normalised = uri.toString();
        return URI.create(normalised.endsWith("/") ? normalised : normalised + "/");
    }

    /**
     * Fetches one file (the manifest or its signature) into memory.
     *
     * @param maxBytes hard cap on the body; a larger response is an error,
     *                 not a truncation
     */
    byte[] fetch(String asset, long maxBytes) throws IOException {
        var target = resolveAsset(asset);
        // HttpResponse itself is not closeable; the body stream is what holds
        // the connection, and sendFollowingRedirects has already disposed of
        // every response it did not return.
        var response = sendFollowingRedirects(target);
        try (var body = response.body()) {
            var bytes = body.readNBytes(checkedReadLimit(maxBytes));
            if (bytes.length > maxBytes) {
                throw new IOException(asset + " is larger than the " + maxBytes + "-byte limit");
            }
            return bytes;
        }
    }

    /**
     * Downloads one archive to {@code target}, refusing anything that is not
     * exactly the size the signed manifest declared. A failed download leaves
     * no file behind.
     */
    void download(String asset, Path target, long expectedSize, long maxBytes) throws IOException {
        var uri = resolveAsset(asset);
        try {
            var response = sendFollowingRedirects(uri);
            // The body stream is closed explicitly: an open stream would pin
            // the connection after the download finished or failed.
            try (var body = response.body();
                 var out = Files.newOutputStream(target,
                         StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                         StandardOpenOption.WRITE)) {
                var buffer = new byte[COPY_BUFFER_BYTES];
                long total = 0;
                int read;
                while ((read = body.read(buffer)) != -1) {
                    total += read;
                    if (total > maxBytes) {
                        throw new IOException(asset + " exceeds the " + maxBytes + "-byte limit");
                    }
                    out.write(buffer, 0, read);
                }
                if (total != expectedSize) {
                    throw new IOException(asset + " is " + total
                            + " bytes but the manifest declares " + expectedSize);
                }
            }
        } catch (IOException e) {
            deleteQuietly(target);
            throw e;
        } catch (RuntimeException e) {
            deleteQuietly(target);
            throw new IOException(e);
        }
    }

    private static void deleteQuietly(Path target) {
        try {
            Files.deleteIfExists(target);
        } catch (IOException ignored) {
            // The download failure is the reportable problem.
        }
    }

    String repository() {
        return repositoryBase.toString();
    }

    private static int checkedReadLimit(long maxBytes) {
        if (maxBytes > Integer.MAX_VALUE - 1) {
            return Integer.MAX_VALUE - 1;
        }
        return (int) maxBytes + 1;
    }

    private URI resolveAsset(String asset) throws IOException {
        if (asset == null || !ASSET_NAME.matcher(asset).matches()) {
            throw new IOException("unsafe asset name '" + asset + "'");
        }
        return repositoryBase.resolve(asset);
    }

    /** Follows redirects by hand so every hop passes {@link #requireAllowed}. */
    private HttpResponse<InputStream> sendFollowingRedirects(URI target) throws IOException {
        var current = target;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            requireAllowed(current);
            var request = HttpRequest.newBuilder(current)
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .build();
            final HttpResponse<InputStream> response;
            try {
                response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while waiting for " + current, e);
            } catch (java.net.http.HttpTimeoutException e) {
                throw new IOException("no response from " + current + " within "
                        + timeoutSeconds + " seconds", e);
            } catch (IOException e) {
                throw new IOException("could not reach " + current + ": " + e.getMessage(), e);
            }

            var status = response.statusCode();
            if (status >= 300 && status < 400) {
                var location = response.headers().firstValue("location").orElse(null);
                try {
                    response.body().close();
                } catch (IOException ignored) {
                    // The status line already told us what happened.
                }
                if (location == null || location.isBlank()) {
                    throw new IOException("HTTP " + status + " from " + current
                            + " without a Location header");
                }
                current = current.resolve(location.trim());
                continue;
            }
            if (status != 200) {
                try {
                    response.body().close();
                } catch (IOException ignored) {
                    // The status is the failure.
                }
                throw new IOException("HTTP " + status + " from " + current);
            }
            return response;
        }
        throw new IOException("more than " + MAX_REDIRECTS + " redirects while fetching " + target);
    }

    /**
     * The transport rules, in one place: scheme first (HTTPS everywhere except
     * loopback, where the test suite's local server lives), then host (the
     * official release hosts, or whatever host the operator configured).
     */
    private void requireAllowed(URI uri) throws IOException {
        var scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        var host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IOException("refusing a URL with no host: " + uri);
        }
        var lowered = host.toLowerCase(Locale.ROOT);
        requireTransport(scheme, lowered, uri.toString());
        if (!OFFICIAL_HOSTS.contains(lowered)
                && !lowered.endsWith(".githubusercontent.com")
                && !lowered.equals(repositoryHost)) {
            throw new IOException("refusing host '" + lowered + "': downloads are limited to the"
                    + " official release hosts and the configured repository");
        }
    }

    private static void requireTransport(String scheme, String host, String url) throws IOException {
        if ("https".equals(scheme)) {
            return;
        }
        if ("http".equals(scheme)) {
            if (isLoopback(host)) {
                return;
            }
            throw new IOException("refusing plain HTTP to " + host + "; downloads require HTTPS: " + url);
        }
        throw new IOException("refusing scheme '" + scheme + "' for " + url);
    }

    /** Addresses whose traffic never leaves this machine. */
    private static boolean isLoopback(String host) {
        return host.equals("localhost")
                || host.endsWith(".localhost")
                || host.equals("::1")
                || host.equals("[::1]")
                || host.equals("0:0:0:0:0:0:0:1")
                || host.matches("127(?:\\.\\d{1,3}){3}");
    }

    @Override
    public void close() {
        client.close();
    }
}
