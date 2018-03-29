package meta_tools.pinproxy;

import java.nio.file.Path;
import qbt.script.QbtScriptEngine;

public final class PinProxyConfig {
    public final String gitRemote;
    public final PinProxyRewrite rewrite;

    public PinProxyConfig(String gitRemote, PinProxyRewrite rewrite) {
        this.gitRemote = gitRemote;
        this.rewrite = rewrite;
    }

    public static PinProxyConfig parse(Path f) {
        QbtScriptEngine.Builder b = QbtScriptEngine.TYPE.builder();
        b = b.addVariable("workspaceRoot", f.getParent());
        return b.build().eval(f);
    }
}
