package meta_tools.pinproxy;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import misc1.commons.ExceptionUtils;
import misc1.commons.concurrent.WorkPool;
import misc1.commons.concurrent.ctree.ComputationTree;
import misc1.commons.ph.ProcessHelper;
import org.apache.commons.lang3.tuple.Pair;
import qbt.QbtHashUtils;
import qbt.VcsVersionDigest;
import qbt.options.ParallelismOptionsResult;

public final class PinProxyUtils {
    private PinProxyUtils() {
        // no
    }

    public static <T> T locked(Path root, Supplier<T> c) {
        try(FileChannel fc = FileChannel.open(root.resolve("qbt-pin-proxy-lock"), StandardOpenOption.WRITE, StandardOpenOption.CREATE)) {
            try(FileLock lock = fc.lock(0, Long.MAX_VALUE, false)) {
                return c.get();
            }
        }
        catch(IOException e) {
            throw ExceptionUtils.commute(e);
        }
    }

    private static void runSimple(Path dir, String... cmd) {
        ProcessHelper.of(dir, cmd).inheritIO().run().requireSuccess();
    }

    private static final Pattern REF_LINE_PATTERN = Pattern.compile("^([0-9a-f]{40}) (.*)$");
    public static void fetch(Path root, PinProxyConfig config) {
        String gitRemote = config.gitRemote;
        PinProxyRewrite rewrite = config.rewrite;

        String wildcard = config.unstripRef("*");
        runSimple(root, "git", "fetch", "--no-tags", gitRemote, "-p", "+" + wildcard + ":" + wildcard);

        ImmutableList<String> refLines = ProcessHelper.of(root, "git", "show-ref").inheritError().run().requireSuccess().stdout;

        ImmutableList.Builder<Pair<String, VcsVersionDigest>> refs = ImmutableList.builder();
        ImmutableSet.Builder<VcsVersionDigest> allRemoteCommits = ImmutableSet.builder();
        for(String refLine : refLines) {
            Matcher m = REF_LINE_PATTERN.matcher(refLine);
            if(!m.matches()) {
                throw new IllegalArgumentException("Bad git show-ref line: " + refLine);
            }
            String ref = config.stripRef(m.group(2));
            VcsVersionDigest remoteCommit = new VcsVersionDigest(QbtHashUtils.parse(m.group(1)));
            refs.add(Pair.of(ref, remoteCommit));
            allRemoteCommits.add(remoteCommit);
        }

        ComputationTree<ImmutableMap<VcsVersionDigest, VcsVersionDigest>> rewriteMapCt = rewrite.remoteToLocal(allRemoteCommits.build());

        ParallelismOptionsResult por = WorkPool::defaultParallelism;
        ImmutableMap<VcsVersionDigest, VcsVersionDigest> rewriteMap = por.runComputationTree(rewriteMapCt);

        for(Pair<String, VcsVersionDigest> tuple : refs.build()) {
            String ref = tuple.getLeft();
            VcsVersionDigest remoteCommit = tuple.getRight();
            VcsVersionDigest localCommit = rewriteMap.get(remoteCommit);
            if(localCommit == null) {
                throw new IllegalStateException();
            }
            runSimple(root, "git", "update-ref", config.unstripRef(ref), localCommit.getRawDigest().toString());
        }
    }

    public static final VcsVersionDigest ZEROES = new VcsVersionDigest(QbtHashUtils.parse("0000000000000000000000000000000000000000"));
}
