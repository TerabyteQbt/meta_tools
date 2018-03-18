package meta_tools.pinproxy;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import misc1.commons.ExceptionUtils;
import misc1.commons.concurrent.WorkPool;
import misc1.commons.concurrent.ctree.ComputationTree;
import misc1.commons.ph.ProcessHelper;
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
    public static final String HEAD_PREFIX = "refs/heads/";
    public static final String REMOTE_PREFIX = "refs/pin-proxy-remote/";

    private static ImmutableMap<String, VcsVersionDigest> readRefs(Path root) {
        ImmutableMap.Builder<String, VcsVersionDigest> b = ImmutableMap.builder();
        ImmutableList<String> refLines = ProcessHelper.of(root, "git", "show-ref").inheritError().run().requireSuccess().stdout;
        for(String refLine : refLines) {
            Matcher m = REF_LINE_PATTERN.matcher(refLine);
            if(!m.matches()) {
                throw new IllegalArgumentException("Bad git show-ref line: " + refLine);
            }
            VcsVersionDigest commit = new VcsVersionDigest(QbtHashUtils.parse(m.group(1)));
            String ref = m.group(2);
            b.put(ref, commit);
        }
        return b.build();
    }

    private static ImmutableMap<String, VcsVersionDigest> stripRefs(ImmutableMap<String, VcsVersionDigest> refs, String prefix) {
        ImmutableMap.Builder<String, VcsVersionDigest> b = ImmutableMap.builder();
        for(Map.Entry<String, VcsVersionDigest> e : refs.entrySet()) {
            String ref = e.getKey();
            VcsVersionDigest commit = e.getValue();
            if(ref.startsWith(prefix)) {
                b.put(ref.substring(prefix.length()), commit);
            }
        }
        return b.build();
    }

    public static void fetch(Path root, PinProxyConfig config) {
        String gitRemote = config.gitRemote;
        PinProxyRewrite rewrite = config.rewrite;

        ImmutableMap<String, VcsVersionDigest> oldRemotes = stripRefs(readRefs(root), REMOTE_PREFIX);

        runSimple(root, "git", "fetch", "--no-tags", gitRemote, "-p", "+" + HEAD_PREFIX + "*:" + REMOTE_PREFIX + "*");

        ImmutableMap<String, VcsVersionDigest> newRefs = readRefs(root);
        ImmutableMap<String, VcsVersionDigest> newRemotes = stripRefs(newRefs, REMOTE_PREFIX);
        ImmutableMap<String, VcsVersionDigest> heads = stripRefs(newRefs, HEAD_PREFIX);

        ComputationTree<ImmutableMap<VcsVersionDigest, VcsVersionDigest>> rewriteMapCt = rewrite.remoteToLocal(oldRemotes.values(), newRemotes.values());

        ParallelismOptionsResult por = WorkPool::defaultParallelism;
        ImmutableMap<VcsVersionDigest, VcsVersionDigest> rewriteMap = por.runComputationTree(rewriteMapCt);

        for(String name : ImmutableSet.<String>builder().addAll(heads.keySet()).addAll(newRemotes.keySet()).build()) {
            VcsVersionDigest oldLocalCommit = heads.get(name);
            VcsVersionDigest remoteCommit = newRemotes.get(name);
            if(remoteCommit == null) {
                if(oldLocalCommit == null) {
                    // ?
                }
                else {
                    runSimple(root, "git", "update-ref", "-d", "refs/heads/" + name);
                }
            }
            else {
                VcsVersionDigest localCommit = rewriteMap.get(remoteCommit);
                if(localCommit == null) {
                    throw new IllegalStateException();
                }
                if(oldLocalCommit == null || !oldLocalCommit.equals(localCommit)) {
                    runSimple(root, "git", "update-ref", "refs/heads/" + name, localCommit.getRawDigest().toString());
                }
            }
        }
    }

    public static final VcsVersionDigest ZEROES = new VcsVersionDigest(QbtHashUtils.parse("0000000000000000000000000000000000000000"));

    public static String refToHead(String ref) {
        if(!ref.startsWith("refs/heads/")) {
            throw new IllegalArgumentException("Bad ref: " + ref);
        }
        return ref.substring(11);
    }
}
