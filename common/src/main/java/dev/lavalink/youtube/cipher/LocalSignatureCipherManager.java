package dev.lavalink.youtube.cipher;

import com.sedmelluq.discord.lavaplayer.tools.DataFormatTools;
import com.sedmelluq.discord.lavaplayer.tools.ExceptionTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import dev.lavalink.youtube.YoutubeSource;
import dev.lavalink.youtube.cipher.ScriptExtractionException.ExtractionFailureType;
import dev.lavalink.youtube.track.format.StreamFormat;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.util.EntityUtils;
import org.graalvm.polyglot.Context;
import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TSTree;
import org.treesitter.TreeSitterJavascript;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.sedmelluq.discord.lavaplayer.tools.ExceptionTools.throwWithDebugInfo;

/**
 * Handles parsing and caching of signature ciphers
 */
public class LocalSignatureCipherManager implements CipherManager {
    private static final Logger log = LoggerFactory.getLogger(LocalSignatureCipherManager.class);
private static final Pattern TIMESTAMP_PATTERN = Pattern.compile("(signatureTimestamp|sts):(\\d+)");

private final ConcurrentMap<String, SignatureCipher> cipherCache;
    private final Set<String> dumpedScriptUrls;
    private final Context scriptContext;
    private final Object cipherLoadLock;
    private TSParser parser;

    protected volatile CachedPlayerScript cachedPlayerScript;

    /**
     * Create a new local signature cipher manager
     */
    public LocalSignatureCipherManager() {
        this.cipherCache = new ConcurrentHashMap<>();
        this.dumpedScriptUrls = new HashSet<>();
        this.cipherLoadLock = new Object();
        this.scriptContext = Context.create("js");

        // Prime the tree-sitter native library in a thread-safe way.
        try {
            loadTreeSitter();
            parser = new TSParser();
            log.info("Tree-sitter native library loaded successfully.");
        } catch (Throwable t) {
            log.error("Failed to prime tree-sitter native library", t);
            this.parser = null;
        }
    }

    // fat jar issues, these are packed into the jar at /lib/ where tree-sitter wants them ar root
    private void loadTreeSitter() throws IOException {
        String os = System.getProperty("os.name").toLowerCase();
        String arch = System.getProperty("os.arch");

        if (arch.equals("amd64")) {
            arch = "x86_64";
        }

        String libName;
        String libExtension;

        if (os.contains("win")) {
            libExtension = ".dll";
            libName = "x86_64-windows-tree-sitter" + libExtension;
        } else if (os.contains("nix") || os.contains("nux") || os.contains("aix")) {
            libExtension = ".so";
            libName = "x86_64-linux-gnu-tree-sitter" + libExtension;
        } else if (os.contains("mac")) {
            libExtension = ".dylib";
            libName = "x86_64-macos-tree-sitter" + libExtension;
        } else {
            throw new UnsupportedOperationException("Unsupported operating system: " + os);
        }

        String libPath = "/lib/" + libName;

        try (InputStream libStream = LocalSignatureCipherManager.class.getResourceAsStream(libPath)) {
            if (libStream == null) {
                throw new IOException("Native library not found at " + libPath);
            }

            Path tempFile = Files.createTempFile("libtree-sitter-", libExtension);
            Files.copy(libStream, tempFile, StandardCopyOption.REPLACE_EXISTING);
            System.load(tempFile.toAbsolutePath().toString());
            tempFile.toFile().deleteOnExit();
        }
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
        String signature = format.getSignature();
        String nParameter = format.getNParameter();
        URI initialUrl = format.getUrl();

        URIBuilder uri = new URIBuilder(initialUrl);

        SignatureCipher cipher = getCipherScript(httpInterface, playerScript);

        if (!DataFormatTools.isNullOrEmpty(signature)) {
            try {
                uri.setParameter(format.getSignatureKey(), cipher.apply(scriptContext, signature));
            } catch (Exception e) {
                dumpProblematicScript(playerScript, "Can't transform s parameter " + signature, e);
            }
        }


        if (!DataFormatTools.isNullOrEmpty(nParameter)) {
            try {
                String transformed = cipher.transform(scriptContext, nParameter);
                String logMessage = null;

                if (transformed == null) {
                    logMessage = "Transformed n parameter is null, n function possibly faulty";
                } else if (nParameter.equals(transformed)) {
                    logMessage = "Transformed n parameter is the same as input, n function possibly short-circuited";
                } else if (transformed.startsWith("enhanced_except_") || transformed.endsWith("_w8_" + nParameter)) {
                    logMessage = "N function did not complete due to exception";
                }

                if (logMessage != null) {
                    log.warn("{} (in: {}, out: {}, player script: {}, source version: {})",
                        logMessage, nParameter, transformed, playerScript, YoutubeSource.VERSION);
                }

                uri.setParameter("n", transformed);
            } catch (Exception e) {
                // URLs can still be played without a resolved n parameter. It just means they're
                // throttled. But we shouldn't throw an exception anyway as it's not really fatal.
                dumpProblematicScript(playerScript, "Can't transform n parameter " + nParameter, e);
            }
        }

        try {
            return uri.build(); // setParameter("ratebypass", "yes")  -- legacy parameter that will give 403 if tampered with.
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    private CachedPlayerScript getPlayerScript(@NotNull HttpInterface httpInterface) {
        synchronized (cipherLoadLock) {
            try (CloseableHttpResponse response = httpInterface.execute(new HttpGet("https://www.youtube.com/embed/"))) {
                HttpClientTools.assertSuccessWithContent(response, "fetch player script (embed)");

                String responseText = EntityUtils.toString(response.getEntity());
                String scriptUrl = DataFormatTools.extractBetween(responseText, "\"jsUrl\":\"", "\"");

                if (scriptUrl == null) {
                    throw throwWithDebugInfo(log, null, "no jsUrl found", "html", responseText);
                }

                return (cachedPlayerScript = new CachedPlayerScript(scriptUrl));
            } catch (IOException e) {
                throw ExceptionTools.toRuntimeException(e);
            }
        }
    }

    public CachedPlayerScript getCachedPlayerScript(@NotNull HttpInterface httpInterface) {
        if (cachedPlayerScript == null || System.currentTimeMillis() >= cachedPlayerScript.expireTimestampMs) {
            synchronized (cipherLoadLock) {
                if (cachedPlayerScript == null || System.currentTimeMillis() >= cachedPlayerScript.expireTimestampMs) {
                    return getPlayerScript(httpInterface);
                }
            }
        }

        return cachedPlayerScript;
    }

    private SignatureCipher getCipherScript(@NotNull HttpInterface httpInterface,
                                           @NotNull String cipherScriptUrl) throws IOException {
        SignatureCipher cipher = cipherCache.get(cipherScriptUrl);

        if (cipher == null) {
            synchronized (cipherLoadLock) {
                cipher = cipherCache.get(cipherScriptUrl);
                if (cipher != null) {
                    return cipher;
                }

                log.debug("Parsing player script {}", cipherScriptUrl);

                try (CloseableHttpResponse response = httpInterface.execute(new HttpGet(CipherUtils.parseTokenScriptUrl(cipherScriptUrl)))) {
                    int statusCode = response.getStatusLine().getStatusCode();

                    if (!HttpClientTools.isSuccessWithContent(statusCode)) {
                        throw new IOException("Received non-success response code " + statusCode + " from script url " +
                            cipherScriptUrl + " ( " + CipherUtils.parseTokenScriptUrl(cipherScriptUrl) + " )");
                    }

                    String scriptText = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
                    cipher = extractFromScriptWithAst(scriptText, cipherScriptUrl);
                    cipherCache.put(cipherScriptUrl, cipher);
                }
            }
        }

        return cipher;
    }

    private void dumpProblematicScript(@NotNull String sourceUrl, @NotNull String issue, @NotNull Exception thrown) {
        if (!dumpedScriptUrls.add(sourceUrl)) {
            return;
        }

        try {
            Path path = Files.createTempFile("lavaplayer-yt-player-script", ".js");
            // In the future, we may want to dump the raw script content here.
            // For now, we'll just log the URL.
            log.error("Problematic YouTube player script {} detected (issue: {}).", sourceUrl, issue, thrown);
            log.error("Script dumped to {}", path.toAbsolutePath());
        } catch (Exception e) {
            log.error("Failed to dump problematic YouTube player script {} (issue: {})", sourceUrl, issue, e);
        }
    }

    public String getTimestamp(HttpInterface httpInterface, String sourceUrl) throws IOException {
        synchronized (cipherLoadLock) {
            log.debug("Timestamp from script {}", sourceUrl);

            try (CloseableHttpResponse response = httpInterface.execute(new HttpGet(CipherUtils.parseTokenScriptUrl(sourceUrl)))) {
                int statusCode = response.getStatusLine().getStatusCode();

                if (!HttpClientTools.isSuccessWithContent(statusCode)) {
                    throw new IOException("Received non-success response code " + statusCode + " from script url " +
                        sourceUrl + " ( " + CipherUtils.parseTokenScriptUrl(sourceUrl) + " )");
                }

                String scriptText = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
                Matcher matcher = TIMESTAMP_PATTERN.matcher(scriptText);

                if (matcher.find()) {
                    return matcher.group(2);
                }
            }

            throw new ScriptExtractionException("Could not find timestamp in player script.", ExtractionFailureType.TIMESTAMP_NOT_FOUND);
        }
    }

    private SignatureCipher extractFromScriptWithAst(@NotNull String script, @NotNull String sourceUrl) {
        if (this.parser == null) {
            scriptExtractionFailed(sourceUrl, "Tree-sitter parser is not available.", new ScriptExtractionException("Tree-sitter parser is not available.", ExtractionFailureType.UNKNOWN));
        }

        // Maybe delete and reuse ts parser
        parser.setLanguage(new TreeSitterJavascript());
        TSTree tree = parser.parseString(null, script);
        TSNode rootNode = tree.getRootNode();

        // Very basic heuristics to find our functions. A real implementation would be more robust.
        TSNode signatureFunction = null;
        TSNode nFunction = null;
        TSNode helperObject = null;

        for (int i = 0; i < rootNode.getNamedChildCount(); i++) {
            TSNode child = rootNode.getNamedChild(i);
            String type = child.getType();

            if ("variable_declaration".equals(type)) {
                String varContent = script.substring(child.getStartByte(), child.getEndByte());
                if (varContent.contains("split") && varContent.contains("join")) {
                    helperObject = child;
                }
            }

            if ("function_declaration".equals(type)) {
                String funcContent = script.substring(child.getStartByte(), child.getEndByte());
                if (funcContent.contains("split") && funcContent.contains("join")) {
                    signatureFunction = child;
                } else if (funcContent.contains("slice") || funcContent.contains("splice")) {
                    nFunction = child;
                }
            }
        }

        if (signatureFunction == null) {
            scriptExtractionFailed(sourceUrl, "Failed to find signature function.", new ScriptExtractionException("Failed to find signature function.", ExtractionFailureType.DECIPHER_FUNCTION_NOT_FOUND));
        }

        if (nFunction == null) {
            scriptExtractionFailed(sourceUrl, "Failed to find n-function.", new ScriptExtractionException("Failed to find n-function.", ExtractionFailureType.N_FUNCTION_NOT_FOUND));
        }

        StringBuilder scriptBuilder = new StringBuilder();
        scriptBuilder.append("var window = {}; var document = {}; self = window; window.location = { href: '' };\n");

        if (helperObject != null) {
            scriptBuilder.append(script.substring(helperObject.getStartByte(), helperObject.getEndByte())).append(";\n");
        }

        String signatureFunctionName = "solveSignature";
        String nFunctionName = "solveN";

        // We need to rewrite the function declaration to have our desired name.
        String sigFuncText = script.substring(signatureFunction.getStartByte(), signatureFunction.getEndByte());
        sigFuncText = sigFuncText.replaceFirst("function.*?\\(", "function " + signatureFunctionName + "(");
        scriptBuilder.append(sigFuncText).append("\n");

        String nFuncText = script.substring(nFunction.getStartByte(), nFunction.getEndByte());
        nFuncText = nFuncText.replaceFirst("function.*?\\(", "function " + nFunctionName + "(");
        scriptBuilder.append(nFuncText).append("\n");

        return new SignatureCipher(scriptBuilder.toString(), signatureFunctionName, nFunctionName);
    }

    private void scriptExtractionFailed(String sourceUrl, String issue, Exception thrown) {
        dumpProblematicScript(sourceUrl, issue, thrown);
        if (thrown instanceof ScriptExtractionException) {
            throw (ScriptExtractionException) thrown;
        }
        throw new ScriptExtractionException(issue, ExtractionFailureType.UNKNOWN, thrown);
    }
}
