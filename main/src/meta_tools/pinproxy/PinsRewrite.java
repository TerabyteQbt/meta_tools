package meta_tools.pinproxy;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import misc1.commons.concurrent.ctree.ComputationTree;
import misc1.commons.ph.ProcessHelper;
import qbt.VcsVersionDigest;
import qbt.config.LocalPinsRepo;
import qbt.mains.FetchPins;
import qbt.mains.PushPins;
import qbt.manifest.QbtManifestParser;
import qbt.manifest.current.CurrentQbtManifestParser;
import qbt.manifest.current.QbtManifest;
import qbt.remote.QbtRemote;
import qbt.vcs.git.GitUtils;

public class PinsRewrite implements PinProxyRewrite {
    private final Path workspaceRoot;
    private final LocalPinsRepo localPinsRepo;
    private final QbtRemote qbtRemote;
    private final QbtManifestParser manifestParser;

    public PinsRewrite(Path workspaceRoot, LocalPinsRepo localPinsRepo, QbtRemote qbtRemote) {
        this(workspaceRoot, localPinsRepo, qbtRemote, new CurrentQbtManifestParser());
    }

    public PinsRewrite(Path workspaceRoot, LocalPinsRepo localPinsRepo, QbtRemote qbtRemote, QbtManifestParser manifestParser) {
        this.workspaceRoot = workspaceRoot;
        this.localPinsRepo = localPinsRepo;
        this.qbtRemote = qbtRemote;
        this.manifestParser = manifestParser;
    }

    private final LoadingCache<VcsVersionDigest, ComputationTree<VcsVersionDigest>> localToRemote = CacheBuilder.newBuilder().build(new CacheLoader<VcsVersionDigest, ComputationTree<VcsVersionDigest>>() {
        @Override
        public ComputationTree<VcsVersionDigest> load(VcsVersionDigest localCommit) {
            // Ugh, this is a mess.  pre-receive hooks run in a "quarantine
            // env" where incoming objects aren't actually in the repo yet and
            // are only visible to `git` commands due to insane GIT_ env
            // variables.  Similarly any objects created during pre-receive
            // aren't real until (unless!) the receive succeeds.
            //
            // For us right here, right now this "just" means we can't use
            // GitUtils.showFile() which will wipe the GIT_ stuff.  In the
            // longer term for monoRepo proxy we're going to have other
            // problems (the entire inline/extract process will have to
            // similarly operate w/o wiping GIT_ for any git commands in meta).
            Iterable<String> lines = ProcessHelper.of(workspaceRoot, "git", "show", localCommit.getRawDigest() + ":qbt-manifest").run().requireSuccess().stdout;

            QbtManifest manifest = manifestParser.parse(ImmutableList.copyOf(lines));
            ComputationTree ct = ComputationTree.constant(localCommit);
            return ct.combineLeft(PushPins.pushCt(localPinsRepo, qbtRemote, manifest, manifest.repos.keySet()));
        }
    });

    @Override
    public ComputationTree<VcsVersionDigest> localToRemote(VcsVersionDigest localCommit) {
        return localToRemote.getUnchecked(localCommit);
    }

    private final LoadingCache<VcsVersionDigest, ComputationTree<VcsVersionDigest>> remoteToLocal = CacheBuilder.newBuilder().build(new CacheLoader<VcsVersionDigest, ComputationTree<VcsVersionDigest>>() {
        @Override
        public ComputationTree<VcsVersionDigest> load(VcsVersionDigest remoteCommit) {
            Iterable<String> lines = GitUtils.showFile(workspaceRoot, remoteCommit, "qbt-manifest");
            QbtManifest manifest = manifestParser.parse(ImmutableList.copyOf(lines));
            ComputationTree ct = ComputationTree.constant(remoteCommit);
            return ct.combineLeft(FetchPins.fetchCt(localPinsRepo, qbtRemote, manifest, manifest.repos.keySet()));
        }
    });

    @Override
    public ComputationTree<VcsVersionDigest> remoteToLocal(VcsVersionDigest remoteCommit) {
        return remoteToLocal.getUnchecked(remoteCommit);
    }
}
