package meta_tools.pinproxy;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Paths;
import misc1.commons.concurrent.WorkPool;
import misc1.commons.concurrent.ctree.ComputationTree;
import misc1.commons.options.OptionsFragment;
import misc1.commons.options.OptionsLibrary;
import misc1.commons.options.OptionsResults;
import misc1.commons.ph.ProcessHelper;
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

    private static final VcsVersionDigest ZEROES = new VcsVersionDigest(QbtHashUtils.parse("0000000000000000000000000000000000000000"));

    @Override
    public int run(final OptionsResults<? extends Options> options) throws Exception {
        ImmutableList<String> args = options.get(Options.args);
        String ref = args.get(0);
        if(!ref.startsWith("refs/")) {
            throw new IllegalArgumentException("Bad ref: " + ref);
        }
        ref = ref.substring(5);
        VcsVersionDigest oldLocalCommit = new VcsVersionDigest(QbtHashUtils.parse(args.get(1)));
        VcsVersionDigest newLocalCommit = new VcsVersionDigest(QbtHashUtils.parse(args.get(2)));

        PinProxyConfig config = PinProxyConfig.parse(Paths.get("./pin-proxy-config"));

        ImmutableSet.Builder<VcsVersionDigest> allLocalCommits = ImmutableSet.builder();
        if(!oldLocalCommit.equals(ZEROES)) {
            allLocalCommits.add(oldLocalCommit);
        }
        if(!newLocalCommit.equals(ZEROES)) {
            allLocalCommits.add(newLocalCommit);
        }
        ComputationTree<ImmutableMap<VcsVersionDigest, VcsVersionDigest>> rewriteMapCt = config.rewrite.localToRemote(allLocalCommits.build());

        ParallelismOptionsResult por = WorkPool::defaultParallelism;
        ImmutableMap<VcsVersionDigest, VcsVersionDigest> rewriteMap = por.runComputationTree(rewriteMapCt);

        ImmutableList.Builder<String> cmd = ImmutableList.builder();
        cmd.add("git", "push", config.gitRemote);
        VcsVersionDigest oldRemoteCommit = oldLocalCommit.equals(ZEROES) ? ZEROES : rewriteMap.get(oldLocalCommit);
        VcsVersionDigest newRemoteCommit = newLocalCommit.equals(ZEROES) ? ZEROES : rewriteMap.get(newLocalCommit);
        if(oldRemoteCommit == null || newRemoteCommit == null) {
            throw new IllegalStateException();
        }
        cmd.add("--force-with-lease=refs/" + ref + ":" + oldRemoteCommit.getRawDigest());
        cmd.add(newRemoteCommit.getRawDigest() + ":refs/" + ref);

        return ProcessHelper.of(Paths.get("."), cmd.build()).inheritIO().run().exitCode;
    }
}
