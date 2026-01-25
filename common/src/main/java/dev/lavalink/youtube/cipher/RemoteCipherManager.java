package dev.lavalink.youtube.cipher;

import com.grack.nanojson.JsonWriter;
import com.grack.nanojson.JsonStringWriter;
import com.sedmelluq.discord.lavaplayer.tools.DataFormatTools;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import dev.lavalink.youtube.ExceptionWithResponseBody;
import dev.lavalink.youtube.http.YoutubeHttpContextFilter;
import dev.lavalink.youtube.track.format.StreamFormat;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.util.EntityUtils;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

import static com.sedmelluq.discord.lavaplayer.tools.ExceptionTools.throwWithDebugInfo;

/**
 * Handles parsing and caching of ciphers via a remote service
 */
public class RemoteCipherManager implements CipherManager {
    private static final Logger log = LoggerFactory.getLogger(RemoteCipherManager.class);

    private final @NotNull List<String> remoteUrls;

    protected volatile CachedPlayerScript cachedPlayerScript;

    /**
     * Create a new remote cipher manager
     */
    public RemoteCipherManager(@NotNull String remoteUrl) {
        this(Collections.singletonList(remoteUrl));
    }

    public RemoteCipherManager(@NotNull List<String> remoteUrls) {
        this.remoteUrls = remoteUrls;
    }

    @NotNull
    public List<String> getRemoteUrls() {
        return remoteUrls;
    }


    /**
     * Produces a valid playback URL for the specified track
     *
     * @param httpInterface HTTP interface to use
     * @param playerScript  Address of the script which is used to decipher signatures
     * @param format        The track for which to get the URL
     * @return Valid playback URL
     * @throws IOException On network IO error
     */
    @NotNull
    public URI resolveFormatUrl(@NotNull HttpInterface httpInterface,
                                @NotNull String playerScript,
                                @NotNull StreamFormat format) throws IOException {
        return resolveUrl(
            httpInterface,
            format.getUrl(),
            playerScript,
            format.getSignature(),
            format.getNParameter(),
            format.getSignatureKey()
        );
    }

    public CachedPlayerScript getCachedPlayerScript(@NotNull HttpInterface httpInterface) {
        if (cachedPlayerScript == null || System.currentTimeMillis() >= cachedPlayerScript.expireTimestampMs) {
            synchronized (this) {
                if (cachedPlayerScript == null || System.currentTimeMillis() >= cachedPlayerScript.expireTimestampMs) {
                    try {
                        return (cachedPlayerScript = getPlayerScript(httpInterface));
                    } catch (RuntimeException e) {
                        if (e instanceof ExceptionWithResponseBody) {
                            throw throwWithDebugInfo(log, null, e.getMessage(), "html", ((ExceptionWithResponseBody) e).getResponseBody());
                        }

                        throw e;
                    }
                }
            }
        }

        return cachedPlayerScript;
    }

    public String getTimestamp(HttpInterface httpInterface, String sourceUrl) throws IOException {
        String requestBody = JsonWriter.string()
            .object()
            .value("player_url", sourceUrl)
            .end()
            .done();

        String responseBody = executeRequest(httpInterface, "get_sts", requestBody);
        JsonBrowser json = JsonBrowser.parse(responseBody);
        return json.get("sts").text();
    }

    private String getRemoteEndpoint(String remoteUrl, String path) {
        return remoteUrl.endsWith("/") ? remoteUrl + path : remoteUrl + "/" + path;
    }

    public HttpInterface configureHttpInterface(HttpInterface httpInterface) {
        httpInterface.getContext().setAttribute(YoutubeHttpContextFilter.ATTRIBUTE_CIPHER_REQUEST_SPECIFIED, true);
        return httpInterface;
    }

    private URI resolveUrl(HttpInterface httpInterface,
                           URI baseUrl,
                           String playerScript,
                           String signature,
                           String nParam,
                           String sigKey) throws IOException {
        log.debug("Resolving stream url {} with player script {}", baseUrl, playerScript);

        JsonStringWriter writer = JsonWriter.string()
            .object()
            .value("stream_url", baseUrl.toString())
            .value("player_url", playerScript);

        if (signature != null) {
            writer.value("encrypted_signature", signature);
        }
        if (nParam != null) {
            writer.value("n_param", nParam);
        }
        if (sigKey != null) {
            writer.value("signature_key", sigKey);
        }

        String requestBody = writer.end().done();
        String responseBody = executeRequest(httpInterface, "resolve_url", requestBody);
        JsonBrowser json = JsonBrowser.parse(responseBody);
        String resolvedUrl = json.get("resolved_url").text();

        if (resolvedUrl == null || resolvedUrl.isEmpty()) {
            throw new IOException("Remote cipher service did not return a resolved URL.");
        }

        try {
            return new URI(resolvedUrl);
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    private String executeRequest(HttpInterface httpInterface, String path, String requestBody) throws IOException {
        List<String> urls = new ArrayList<>(remoteUrls);
        IOException lastException = null;

        for (String remoteUrl : urls) {
            HttpPost request = new HttpPost(getRemoteEndpoint(remoteUrl, path));
            request.setEntity(new StringEntity(requestBody, ContentType.APPLICATION_JSON));

            try (CloseableHttpResponse response = configureHttpInterface(httpInterface).execute(request)) {
                return validateAndGetResponseBody(response);
            } catch (IOException e) {
                lastException = e;
                log.error("Failed to make request to remote cipher server {}: {}", remoteUrl, e.getMessage());
            }
        }

        throw new IOException("All remote cipher servers failed.", lastException);
    }

    @NotNull
    public String validateAndGetResponseBody(@NotNull HttpResponse response) throws IOException {
        int statusCode = response.getStatusLine().getStatusCode();
        HttpEntity entity = response.getEntity();
        String responseBody = (entity != null) ? EntityUtils.toString(entity, StandardCharsets.UTF_8) : null;

        if (!HttpClientTools.isSuccessWithContent(statusCode)) {
            throw new IOException("Remote cipher service request to resolve URL failed with status code: " + statusCode + ". Response: " + responseBody);
        }

        if (DataFormatTools.isNullOrEmpty(responseBody)) {
            throw new IOException("Received empty successful response from remote cipher service.");
        }

        return responseBody;
    }
}

