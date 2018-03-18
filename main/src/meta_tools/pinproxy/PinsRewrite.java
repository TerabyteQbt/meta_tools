package meta_tools.pinproxy;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import misc1.commons.concurrent.ctree.ComputationTree;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qbt.VcsVersionDigest;
import qbt.config.LocalPinsRepo;
import qbt.mains.FetchPins;
import qbt.mains.PushPins;
import qbt.manifest.QbtManifestParser;
import qbt.manifest.current.CurrentQbtManifestParser;
import qbt.manifest.current.QbtManifest;
import qbt.manifest.current.RepoManifest;
import qbt.remote.QbtRemote;
import qbt.tip.RepoTip;
import qbt.vcs.git.GitUtils;

public class PinsRewrite implements PinProxyRewrite {
    private static final Logger LOGGER = LoggerFactory.getLogger(PinsRewrite.class);

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

    interface CommonCallback {
        void run(RepoTip repo, Iterable<VcsVersionDigest> versions);
    }

    private ImmutableMultimap<RepoTip, VcsVersionDigest> compileRepoVersions(Iterable<VcsVersionDigest> commits) {
        ImmutableMultimap.Builder<RepoTip, VcsVersionDigest> ret = ImmutableMultimap.builder();
        for(VcsVersionDigest commit : ImmutableSet.copyOf(commits)) {
            if(commit.equals(PinProxyUtils.ZEROES)) {
                continue;
            }

            QbtManifest manifest;
            try {
                Iterable<String> lines = GitUtils.showFile(workspaceRoot, commit, "qbt-manifest");
                manifest = manifestParser.parse(ImmutableList.copyOf(lines));
            }
            catch(RuntimeException e) {
                LOGGER.warn("No valid manifest found in " + commit.getRawDigest() + ", ignoring...");
                continue;
            }

            for(Map.Entry<RepoTip, RepoManifest> e : manifest.repos.entrySet()) {
                RepoTip repo = e.getKey();
                RepoManifest repoManifest = e.getValue();
                Optional<VcsVersionDigest> maybeVersion = repoManifest.version;
                if(maybeVersion.isPresent()) {
                    ret.put(repo, maybeVersion.get());
                }
            }
        }
        return ret.build();
    }

    private <R> ComputationTree<R> common(R ret, Iterable<VcsVersionDigest> commits, CommonCallback cb) {
        ComputationTree<R> ct = ComputationTree.constant(ret);

        ImmutableMultimap<RepoTip, VcsVersionDigest> repoVersions = compileRepoVersions(commits);

        for(Map.Entry<RepoTip, Collection<VcsVersionDigest>> e : repoVersions.asMap().entrySet()) {
            ct = ct.combineLeft(ComputationTree.ofSupplier(() -> {
                cb.run(e.getKey(), e.getValue());
                return ObjectUtils.NULL;
            }));
        }

        return ct;
    }

    @Override
    public ComputationTree<Pair<VcsVersionDigest, VcsVersionDigest>> localToRemote(Pair<VcsVersionDigest, VcsVersionDigest> commits) {
        VcsVersionDigest oldCommit = commits.getLeft();
        VcsVersionDigest newCommit = commits.getRight();

        ImmutableMultimap<RepoTip, VcsVersionDigest> oldRepoVersions = compileRepoVersions(ImmutableList.of(oldCommit));

        return common(commits, ImmutableSet.of(newCommit), (repo, versions) -> {
            Set<VcsVersionDigest> push = Sets.newHashSet(versions);
            push.removeAll(oldRepoVersions.get(repo));
            PushPins.push(localPinsRepo, qbtRemote, repo, push);
        });
    }

    @Override
    public ComputationTree<ImmutableMap<VcsVersionDigest, VcsVersionDigest>> remoteToLocal(Iterable<VcsVersionDigest> commits) {
        ImmutableMap.Builder<VcsVersionDigest, VcsVersionDigest> ret = ImmutableMap.builder();
        for(VcsVersionDigest commit : ImmutableSet.copyOf(commits)) {
            ret.put(commit, commit);
        }
        return common(ret.build(), commits, (repo, versions) -> FetchPins.fetch(localPinsRepo, qbtRemote, repo, versions, true));
    }
}
