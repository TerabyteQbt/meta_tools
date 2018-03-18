package meta_tools.pinproxy;

import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import misc1.commons.concurrent.WorkPool;
import misc1.commons.concurrent.ctree.ComputationTree;
import misc1.commons.options.OptionsResults;
import misc1.commons.ph.ProcessHelper;
import org.apache.commons.lang3.tuple.Triple;
import qbt.HelpTier;
import qbt.QbtCommand;
import qbt.QbtCommandName;
import qbt.QbtCommandOptions;
import qbt.QbtHashUtils;
import qbt.VcsVersionDigest;
import qbt.options.ParallelismOptionsResult;

public class PreReceiveHook extends QbtCommand<PreReceiveHook.Options> {
    @QbtCommandName("preReceiveHook")
    public static interface Options extends QbtCommandOptions {
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
    private static final Pattern INPUT_PATTERN = Pattern.compile("^([0-9a-f]{40}) ([0-9a-f]{40}) refs/(.*)$");

    @Override
    public int run(final OptionsResults<? extends Options> options) throws Exception {
        PinProxyConfig config = PinProxyConfig.parse(Paths.get("./pin-proxy-config"));

        ImmutableList.Builder<String> inputs = ImmutableList.builder();
        try(InputStreamReader isr = new InputStreamReader(System.in, Charsets.UTF_8)) {
            try(BufferedReader br = new BufferedReader(isr)) {
                while(true) {
                    String input = br.readLine();
                    if(input == null) {
                        break;
                    }
                    inputs.add(input);
                }
            }
        }

        ComputationTree<ImmutableList<Triple<String, VcsVersionDigest, VcsVersionDigest>>> ct = ComputationTree.transformIterableExec(inputs.build(), (input) -> {
            Matcher m = INPUT_PATTERN.matcher(input);
            if(!m.matches()) {
                throw new IllegalArgumentException("Bad pre-receive hook input: " + input);
            }
            String ref = m.group(3);
            VcsVersionDigest oldLocalCommit = new VcsVersionDigest(QbtHashUtils.parse(m.group(1)));
            VcsVersionDigest newLocalCommit = new VcsVersionDigest(QbtHashUtils.parse(m.group(2)));
            ComputationTree<VcsVersionDigest> oldRemoteCommit = oldLocalCommit.equals(ZEROES) ? ComputationTree.constant(ZEROES) : config.rewrite.localToRemote(oldLocalCommit);
            ComputationTree<VcsVersionDigest> newRemoteCommit = newLocalCommit.equals(ZEROES) ? ComputationTree.constant(ZEROES) : config.rewrite.localToRemote(newLocalCommit);
            return ComputationTree.tuple(ComputationTree.constant(ref), oldRemoteCommit, newRemoteCommit, Triple::of);
        });

        ParallelismOptionsResult por = WorkPool::defaultParallelism;
        ImmutableList<Triple<String, VcsVersionDigest, VcsVersionDigest>> pushes = por.runComputationTree(ct);

        ImmutableList.Builder<String> cmd = ImmutableList.builder();
        cmd.add("git", "push", config.gitRemote);
        for(Triple<String, VcsVersionDigest, VcsVersionDigest> push : pushes) {
            String ref = push.getLeft();
            VcsVersionDigest oldRemoteCommit = push.getMiddle();
            VcsVersionDigest newRemoteCommit = push.getRight();
            cmd.add("--force-with-lease=refs/" + ref + ":" + oldRemoteCommit.getRawDigest());
            cmd.add(newRemoteCommit.getRawDigest() + ":refs/" + ref);
        }

        return ProcessHelper.of(Paths.get("."), cmd.build()).inheritIO().run().exitCode;
    }
}
