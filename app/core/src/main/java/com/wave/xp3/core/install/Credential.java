package com.wave.xp3.core.install;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A private source's sign-in, for downloading its packages.
 *
 * It comes from the source's own address (`https://user:token@host/owner/repo`),
 * which only the phone keeps; manifests never carry one, and nothing that is
 * shown, logged, written down or kept in a receipt does either. It lives only on
 * a plan's packages in memory, and {@link #toString} names the host alone.
 *
 * It is sent only where the source's owner already sent it, request by request
 * (each redirect is judged again, so a CDN's signed address never gets it):
 * <ul>
 * <li>a GitHub release asset of the same repository through GitHub's API
 *   (`https://api.github.com/repos/OWNER/REPO/releases/assets/ID`), as
 *   `Authorization: Bearer` with `Accept: application/octet-stream`, which is
 *   how a private repository's asset is fetched;</li>
 * <li>any address on the source's own host (same scheme, host and port), as
 *   `Authorization: Basic`, the way the source's Git refresh signs in;</li>
 * <li>nowhere else.</li>
 * </ul>
 */
public final class Credential {

    private static final Pattern GITHUB_ASSET =
        Pattern.compile("/repos/([^/]+)/([^/]+)/releases/assets/[0-9]+");

    private final String scheme;
    private final String host;
    private final int port;
    private final String owner;
    private final String repo;
    private final String basic;
    private final String bearer;

    private Credential(String scheme, String host, int port, String owner, String repo, String user, String password) {
        this.scheme = scheme;
        this.host = host;
        this.port = port;
        this.owner = owner;
        this.repo = repo;
        basic = "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
        // GitHub takes a token as the password, or alone as the user name.
        bearer = "Bearer " + (password.isEmpty() ? user : password);
    }

    /** The sign-in in a source's address; null when it has none (or isn't an address). */
    public static Credential of(String sourceUrl) {
        if (sourceUrl == null) return null;
        URI uri;
        try {
            uri = new URI(sourceUrl);
        } catch (URISyntaxException e) {
            return null;
        }
        String info = uri.getUserInfo();
        if (info == null || info.isEmpty() || uri.getHost() == null || uri.getScheme() == null) return null;
        int colon = info.indexOf(':');
        String user = colon < 0 ? info : info.substring(0, colon);
        String password = colon < 0 ? "" : info.substring(colon + 1);
        if (user.isEmpty() && password.isEmpty()) return null;
        String scheme = uri.getScheme().toLowerCase(Locale.US);
        String owner = null;
        String repo = null;
        String path = uri.getPath() == null ? "" : uri.getPath();
        String[] parts = path.replaceAll("^/+|/+$", "").split("/+");
        if (parts.length == 2) {
            owner = parts[0].toLowerCase(Locale.US);
            repo = parts[1].toLowerCase(Locale.US).replaceAll("\\.git$", "");
        }
        return new Credential(scheme, uri.getHost().toLowerCase(Locale.US), port(scheme, uri.getPort()),
            owner, repo, user, password);
    }

    private static int port(String scheme, int port) {
        if (port >= 0) return port;
        return "http".equals(scheme) ? 80 : 443;
    }

    /** What to send with a request for `url`: the headers, or null for none. */
    public String[] headersFor(String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            return null;
        }
        if (uri.getScheme() == null || uri.getHost() == null || uri.getRawUserInfo() != null) return null;
        String s = uri.getScheme().toLowerCase(Locale.US);
        String h = uri.getHost().toLowerCase(Locale.US);
        int p = port(s, uri.getPort());
        if (s.equals(scheme) && h.equals(host) && p == port) {
            return new String[] {"Authorization", basic};
        }
        if ("https".equals(s) && "api.github.com".equals(h) && p == 443 && uri.getRawQuery() == null
                && ("github.com".equals(host) || "www.github.com".equals(host)) && owner != null) {
            Matcher m = GITHUB_ASSET.matcher(uri.getRawPath() == null ? "" : uri.getRawPath());
            if (m.matches() && m.group(1).toLowerCase(Locale.US).equals(owner)
                    && m.group(2).toLowerCase(Locale.US).equals(repo)) {
                return new String[] {"Authorization", bearer, "Accept", "application/octet-stream"};
            }
        }
        return null;
    }

    /** The host alone: never the user name or token. */
    @Override
    public String toString() {
        return "Credential(" + host + ")";
    }
}
