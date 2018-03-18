package meta_tools.pinproxy;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import misc1.commons.concurrent.ctree.ComputationTree;
import org.apache.commons.lang3.ObjectUtils;
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

    private ComputationTree<ImmutableMap<VcsVersionDigest, VcsVersionDigest>> common(Iterable<VcsVersionDigest> commits, CommonCallback cb) {
        ImmutableMap.Builder<VcsVersionDigest, VcsVersionDigest> ret = ImmutableMap.builder();
        ImmutableMultimap.Builder<RepoTip, VcsVersionDigest> repoVersions = ImmutableMultimap.builder();
        for(VcsVersionDigest commit : ImmutableSet.copyOf(commits)) {
            Iterable<String> lines = GitUtils.showFile(workspaceRoot, commit, "qbt-manifest");

            QbtManifest manifest = manifestParser.parse(ImmutableList.copyOf(lines));
            for(Map.Entry<RepoTip, RepoManifest> e : manifest.repos.entrySet()) {
                RepoTip repo = e.getKey();
                RepoManifest repoManifest = e.getValue();
                Optional<VcsVersionDigest> maybeVersion = repoManifest.version;
                if(maybeVersion.isPresent()) {
                    repoVersions.put(repo, maybeVersion.get());
                }
            }

            ret.put(commit, commit);
        }
        ComputationTree runCt = ComputationTree.list(Iterables.transform(repoVersions.build().asMap().entrySet(), (e) -> {
            return ComputationTree.ofSupplier(() -> {
                cb.run(e.getKey(), e.getValue());
                return ObjectUtils.NULL;
            });
        }));
        return ComputationTree.constant(ret.build()).combineLeft(runCt);
    }

    @Override
    public ComputationTree<ImmutableMap<VcsVersionDigest, VcsVersionDigest>> localToRemote(Iterable<VcsVersionDigest> localCommits) {
        return common(localCommits, (repo, versions) -> PushPins.push(localPinsRepo, qbtRemote, repo, versions));
    }

    @Override
    public ComputationTree<ImmutableMap<VcsVersionDigest, VcsVersionDigest>> remoteToLocal(Iterable<VcsVersionDigest> remoteCommits) {
        return common(remoteCommits, (repo, versions) -> FetchPins.fetch(localPinsRepo, qbtRemote, repo, versions));
    }
}
