package meta_tools.pinproxy;

import com.google.common.collect.ImmutableList;
import java.nio.file.Paths;
import misc1.commons.concurrent.WorkPool;
import misc1.commons.concurrent.ctree.ComputationTree;
import misc1.commons.options.OptionsFragment;
import misc1.commons.options.OptionsLibrary;
import misc1.commons.options.OptionsResults;
import misc1.commons.ph.ProcessHelper;
import org.apache.commons.lang3.tuple.Pair;
import qbt.HelpTier;
import qbt.QbtCommand;
import qbt.QbtCommandName;
import qbt.QbtCommandOptions;
import qbt.QbtHashUtils;
import qbt.VcsVersionDigest;
import qbt.options.ParallelismOptionsResult;

public class UpdateHook extends QbtCommand<UpdateHook.Options> {
    @QbtCommandName("updateHook")
    public static interface Options extends QbtCommandOptions {
        public static final OptionsLibrary<Options> o = OptionsLibrary.of();
        public static final OptionsFragment<Options, ImmutableList<String>> args = o.unparsed(false).transform(o.minMax(3, 3));
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
        PinProxyConfig config = PinProxyConfig.parse(Paths.get("./pin-proxy-config"));

        ImmutableList<String> args = options.get(Options.args);
        String ref = config.stripRef(args.get(0));
        VcsVersionDigest oldLocalCommit = new VcsVersionDigest(QbtHashUtils.parse(args.get(1)));
        VcsVersionDigest newLocalCommit = new VcsVersionDigest(QbtHashUtils.parse(args.get(2)));

        ComputationTree<Pair<VcsVersionDigest, VcsVersionDigest>> remoteCommitsCt = config.rewrite.localToRemote(Pair.of(oldLocalCommit, newLocalCommit));

        ParallelismOptionsResult por = WorkPool::defaultParallelism;
        Pair<VcsVersionDigest, VcsVersionDigest> remoteCommits = por.runComputationTree(remoteCommitsCt);
        VcsVersionDigest oldRemoteCommit = remoteCommits.getLeft();
        VcsVersionDigest newRemoteCommit = remoteCommits.getRight();

        ImmutableList.Builder<String> cmd = ImmutableList.builder();
        cmd.add("git", "push", config.gitRemote);
        cmd.add("--force-with-lease=" + config.unstripRef(ref) + ":" + oldRemoteCommit.getRawDigest());
        cmd.add(newRemoteCommit.getRawDigest() + ":" + config.unstripRef(ref));
        return ProcessHelper.of(Paths.get("."), cmd.build()).inheritIO().run().exitCode;
    }
}
