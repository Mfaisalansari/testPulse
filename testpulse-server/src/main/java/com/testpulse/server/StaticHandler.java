package com.testpulse.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Serves dashboard static files from JAR resources at {@code /static/}.
 * Maps the URL path to a classpath resource — {@code /} returns
 * {@code static/index.html}, {@code /runs/r_abc} also returns index.html
 * (SPA-style: the JavaScript router handles the path).
 *
 * <p>Anything matching a real file is returned directly with a sensible
 * Content-Type. The asset paths are deliberately narrow — we don't allow
 * arbitrary classpath browsing.
 */
public final class StaticHandler implements HttpHandler {

    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger(StaticHandler.class.getName());

    @Override
    public void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            if (path == null || path.isEmpty() || "/".equals(path)) {
                serveResource(ex, "static/index.html", path);
                return;
            }
            if (path.contains("..")) {
                send(ex, 400, "bad path", "text/plain");
                return;
            }

            // Asset paths (CSS/JS/images): direct file lookup, no SPA fallback
            if (path.startsWith("/assets/") || path.startsWith("/static/")) {
                String resource = "static" + (path.startsWith("/static") ? path.substring(7) : path);
                serveResource(ex, resource, path);
                return;
            }

            // Try the actual file first (so /run.html serves the run.html file, not index.html)
            String resource = "static" + path;
            if (StaticHandler.class.getClassLoader().getResource(resource) != null) {
                serveResource(ex, resource, path);
                return;
            }

            // Fall back to index.html for SPA-style routes that have no real file
            LOG.info("Static: " + path + " -> static/index.html (SPA fallback, no matching file)");
            serveResource(ex, "static/index.html", path);

        } finally {
            ex.close();
        }
    }

    private void serveResource(HttpExchange ex, String resource, String requestPath) throws IOException {
        InputStream in = StaticHandler.class.getClassLoader().getResourceAsStream(resource);
        if (in == null) {
            LOG.warning("Static: " + requestPath + " -> NOT FOUND (looked for classpath resource: " + resource + ")");
            send(ex, 404, "not found: " + resource, "text/plain");
            return;
        }
        try {
            byte[] bytes = readAll(in);
            ex.getResponseHeaders().add("Content-Type", contentType(resource));
            // no-store + must-revalidate prevents browsers from serving cached
            // copies after a server-side rebuild. Important during development:
            // a stale /run.html or app.js cached by the browser will mask
            // server fixes and make navigation appear broken.
            ex.getResponseHeaders().add("Cache-Control", "no-store, no-cache, must-revalidate");
            ex.getResponseHeaders().add("Pragma", "no-cache");
            ex.sendResponseHeaders(200, bytes.length);
            OutputStream out = ex.getResponseBody();
            out.write(bytes);
            out.close();
            LOG.fine("Static: " + requestPath + " -> " + resource + " (" + bytes.length + " bytes)");
        } finally {
            in.close();
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) baos.write(buf, 0, n);
        return baos.toByteArray();
    }

    private static String contentType(String path) {
        if (path.endsWith(".html")) return "text/html; charset=utf-8";
        if (path.endsWith(".js"))   return "application/javascript; charset=utf-8";
        if (path.endsWith(".css"))  return "text/css; charset=utf-8";
        if (path.endsWith(".svg"))  return "image/svg+xml";
        if (path.endsWith(".png"))  return "image/png";
        if (path.endsWith(".ico"))  return "image/x-icon";
        if (path.endsWith(".json")) return "application/json; charset=utf-8";
        return "text/plain; charset=utf-8";
    }

    private void send(HttpExchange ex, int status, String body, String contentType) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", contentType);
        ex.sendResponseHeaders(status, bytes.length);
        OutputStream out = ex.getResponseBody();
        out.write(bytes);
        out.close();
    }
}
