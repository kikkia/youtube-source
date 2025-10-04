package dev.lavalink.youtube.cipher;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.jetbrains.annotations.NotNull;

/**
 * Describes one signature cipher
 */
public class SignatureCipher {
    private final String executableScript;
    private final String signatureFunctionName;
    private final String nFunctionName;

    public SignatureCipher(@NotNull String executableScript,
                           @NotNull String signatureFunctionName,
                           @NotNull String nFunctionName) {
        this.executableScript = executableScript;
        this.signatureFunctionName = signatureFunctionName;
        this.nFunctionName = nFunctionName;
    }

    /**
     * Applies the signature cipher to the given text.
     *
     * @param context The GraalJS context to execute in.
     * @param cipherVal    value to apply the cipher on.
     * @return DecipheredSig
     */
    public String apply(@NotNull Context context, @NotNull String cipherVal) {
        context.eval("js", executableScript);
        Value decryptFunction = context.getBindings("js").getMember(signatureFunctionName);
        return decryptFunction.execute(cipherVal).asString();
    }

    /**
     * Applies the n-parameter transformation to the given text.
     *
     * @param context The GraalJS context to execute in.
     * @param n    Text to transform.
     * @return The result of the n-parameter transformation.
     */
    public String transform(@NotNull Context context, @NotNull String n) {
        context.eval("js", executableScript);
        Value decryptFunction = context.getBindings("js").getMember(nFunctionName);
        return decryptFunction.execute(n).asString();
    }
}
