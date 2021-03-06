package meta_tools.diff;

import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.nio.file.Paths;
import misc1.commons.options.OptionsFragment;
import misc1.commons.options.OptionsLibrary;
import misc1.commons.options.OptionsResults;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qbt.HelpTier;
import qbt.QbtCommand;
import qbt.QbtCommandName;
import qbt.QbtCommandOptions;
import qbt.QbtUtils;
import qbt.config.QbtConfig;
import qbt.manifest.current.QbtManifest;
import qbt.options.ConfigOptionsDelegate;

public class SdiffDriver extends QbtCommand<SdiffDriver.Options> {
    private static final Logger LOGGER = LoggerFactory.getLogger(SdiffDriver.class);

    @QbtCommandName("sdiffDriver")
    public static interface Options extends QbtCommandOptions {
        public static final OptionsLibrary<Options> o = OptionsLibrary.of();
        public static final ConfigOptionsDelegate<Options> config = new ConfigOptionsDelegate<Options>();
        public static final Sdiff.CommonOptionsDelegate<Options> commonOptions = new Sdiff.CommonOptionsDelegate<Options>();

        // path old-file old-hex old-mode new-file new-hex new-mode
        public static final OptionsFragment<Options, ImmutableList<String>> args = o.unparsed(true).transform(o.minMax(7, 7)).helpDesc("Horrible, git-provided arguments.");
    }

    @Override
    public Class<Options> getOptionsClass() {
        return Options.class;
    }

    @Override
    public HelpTier getHelpTier() {
        return HelpTier.PLUMBING;
    }

    @Override
    public String getDescription() {
        return "act as git low-level diff driver for qbt-manifest";
    }

    @Override
    public int run(final OptionsResults<? extends Options> options) throws Exception {
        final QbtConfig config = Options.config.getConfig(options);
        Path workspaceRoot = QbtUtils.findInMeta("", null);

        ImmutableList<String> args = options.get(Options.args);
        String lhsFile = args.get(1);
        String rhsFile = args.get(4);

        QbtManifest lhs = config.manifestParser.parse(ImmutableList.copyOf(QbtUtils.readLines(Paths.get(lhsFile))));
        QbtManifest rhs = config.manifestParser.parse(ImmutableList.copyOf(QbtUtils.readLines(Paths.get(rhsFile))));

        return Sdiff.run(config, workspaceRoot, "diff", options, Options.commonOptions, lhs, rhs);
    }
}
