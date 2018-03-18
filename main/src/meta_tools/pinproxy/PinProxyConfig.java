package meta_tools.pinproxy;

import groovy.lang.GroovyShell;
import java.nio.file.Path;
import misc1.commons.ExceptionUtils;

public final class PinProxyConfig {
    public final String gitRemote;
    public final PinProxyRewrite rewrite;
    private final String refPrefix;

    public PinProxyConfig(String gitRemote, PinProxyRewrite rewrite) {
        this(gitRemote, rewrite, "refs");
    }

    public PinProxyConfig(String gitRemote, PinProxyRewrite rewrite, String refPrefix) {
        this.gitRemote = gitRemote;
        this.rewrite = rewrite;
        this.refPrefix = refPrefix;
    }

    public static PinProxyConfig parse(Path f) {
        GroovyShell shell = new GroovyShell();
        shell.setVariable("workspaceRoot", f.getParent());
        try {
            return (PinProxyConfig) shell.evaluate(f.toFile());
        }
        catch(Exception e) {
            throw ExceptionUtils.commute(e);
        }
    }

    public String stripRef(String ref) {
        if(!ref.startsWith(refPrefix + "/")) {
            throw new IllegalArgumentException("Bad ref: " + ref);
        }
        return ref.substring(refPrefix.length() + 1);
    }

    public String unstripRef(String ref) {
        return refPrefix + "/" + ref;
    }
}
