package meta_tools.pinproxy;

import groovy.lang.GroovyShell;
import java.nio.file.Path;
import misc1.commons.ExceptionUtils;

public final class PinProxyConfig {
    public final String gitRemote;
    public final PinProxyRewrite rewrite;

    public PinProxyConfig(String gitRemote, PinProxyRewrite rewrite) {
        this.gitRemote = gitRemote;
        this.rewrite = rewrite;
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
}
