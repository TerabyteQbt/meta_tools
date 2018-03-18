package meta_tools.pinproxy;

import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.nio.file.Paths;
import misc1.commons.options.OptionsFragment;
import misc1.commons.options.OptionsLibrary;
import misc1.commons.options.OptionsResults;
import misc1.commons.ph.ProcessHelper;
import qbt.HelpTier;
import qbt.QbtCommand;
import qbt.QbtCommandName;
import qbt.QbtCommandOptions;

public class ReceivePackDriver extends QbtCommand<ReceivePackDriver.Options> {
    @QbtCommandName("receivePackDriver")
    public static interface Options extends QbtCommandOptions {
        public static final OptionsLibrary<Options> o = OptionsLibrary.of();
        public static final OptionsFragment<Options, ImmutableList<String>> args = o.unparsed(false);
    }

    @Override
    public Class<Options> getOptionsClass() {
        return Options.class;
    }

    @Override
    public HelpTier getHelpTier() {
        return HelpTier.ARCANE;
    }

    @Override
    public String getDescription() {
        return null;
    }

    @Override
    public int run(final OptionsResults<? extends Options> options) throws Exception {
        ImmutableList<String> args = options.get(Options.args);
        Path root = Paths.get(args.get(args.size() - 1));
        PinProxyConfig config = PinProxyConfig.parse(root.resolve("pin-proxy-config"));

        return PinProxyUtils.locked(root, () -> {
            PinProxyUtils.fetch(root, config);

            ImmutableList<String> cmd = ImmutableList.<String>builder().add("git", "receive-pack").addAll(args).build();
            return ProcessHelper.of(Paths.get("."), cmd).inheritIO().run().exitCode;
        });
    }
}
