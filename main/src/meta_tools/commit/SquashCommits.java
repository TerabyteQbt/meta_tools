package meta_tools.commit;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import misc1.commons.options.OptionsFragment;
import misc1.commons.options.OptionsLibrary;
import misc1.commons.options.OptionsResults;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qbt.HelpTier;
import qbt.QbtCommand;
import qbt.QbtCommandName;
import qbt.QbtCommandOptions;
import qbt.QbtTempDir;
import qbt.QbtUtils;
import qbt.VcsVersionDigest;
import qbt.config.QbtConfig;
import qbt.manifest.LegacyQbtManifest;
import qbt.manifest.QbtManifestVersions;
import qbt.manifest.current.QbtManifest;
import qbt.manifest.current.RepoManifest;
import qbt.options.ConfigOptionsDelegate;
import qbt.options.ManifestOptionsDelegate;
import qbt.options.ManifestOptionsResult;
import qbt.options.RepoActionOptionsDelegate;
import qbt.repo.LocalRepoAccessor;
import qbt.repo.PinnedRepoAccessor;
import qbt.tip.RepoTip;
import qbt.vcs.CommitData;
import qbt.vcs.CommitDataUtils;
import qbt.vcs.CommitLevel;
import qbt.vcs.LocalVcs;
import qbt.vcs.Repository;
import qbt.vcs.VcsRegistry;

public final class SquashCommits extends QbtCommand<SquashCommits.Options> {
    private static final Logger LOGGER = LoggerFactory.getLogger(SquashCommits.class);

    @QbtCommandName("squashCommits")
    public static interface Options extends QbtCommandOptions {
        public static final OptionsLibrary<Options> o = OptionsLibrary.of();
        public static final ConfigOptionsDelegate<Options> config = new ConfigOptionsDelegate<Options>();
        public static final ManifestOptionsDelegate<Options> manifest = new ManifestOptionsDelegate<Options>();
        public static final RepoActionOptionsDelegate<Options> repos = new RepoActionOptionsDelegate<Options>(RepoActionOptionsDelegate.NoArgsBehaviour.ALL);
        public static final OptionsFragment<Options, String> metaVcs = o.oneArg("metaVcs").transform(o.singleton("git")).helpDesc("VCS for meta");
    }

    @Override
    public Class<Options> getOptionsClass() {
        return Options.class;
    }

    @Override
    public HelpTier getHelpTier() {
        return HelpTier.UNCOMMON;
    }

    @Override
    public String getDescription() {
        return "squash satellite histories";
    }

    @Override
    public int run(OptionsResults<? extends Options> options) throws Exception {
        QbtConfig config = Options.config.getConfig(options);
        ManifestOptionsResult manifestResult = Options.manifest.getResult(options);
        LegacyQbtManifest<?, ?> manifestLegacy = manifestResult.parseLegacy();

        QbtManifest manifestCurrent = manifestLegacy.current();
        Collection<RepoTip> repos = Options.repos.getRepos(config, manifestCurrent, options);

        String metaVcs = options.get(Options.metaVcs);

        Path metaDir = QbtUtils.findInMeta("", null);
        Repository metaRepository = VcsRegistry.getLocalVcs(metaVcs).getRepository(metaDir);
        if(!metaRepository.isClean()) {
            throw new IllegalArgumentException("Meta is dirty!");
        }
        VcsVersionDigest metaCurrentCommit = metaRepository.getCurrentCommit();
        CommitData metaCurrentCd = metaRepository.getCommitData(metaCurrentCommit);

        ImmutableList.Builder<QbtManifest> parentManifestsBuilder = ImmutableList.builder();
        for(VcsVersionDigest metaParent : metaCurrentCd.get(CommitData.PARENTS)) {
            parentManifestsBuilder.add(QbtManifestVersions.parse(ImmutableList.copyOf(metaRepository.showFile(metaParent, "qbt-manifest"))));
        }
        List<QbtManifest> parentManifests = parentManifestsBuilder.build();

        LegacyQbtManifest.Builder<?, ?> newManifest = manifestLegacy.builder();
        boolean fail = false;
        boolean changed = false;
        try(QbtTempDir tempDir = new QbtTempDir()) {
            for(RepoTip repo : repos) {
                RepoManifest repoManifest = manifestCurrent.repos.get(repo);

                VcsVersionDigest tip = repoManifest.version;
                LOGGER.debug("[" + repo + "] tip = " + tip);

                PinnedRepoAccessor pinnedAccessor = config.localPinsRepo.requirePin(repo, tip);

                Path repoDir;
                Repository repoRepository;

                LocalRepoAccessor localRepoAccessor = config.localRepoFinder.findLocalRepo(repo);
                if(localRepoAccessor != null) {
                    repoDir = localRepoAccessor.dir;
                    repoRepository = localRepoAccessor.vcs.getRepository(localRepoAccessor.dir);
                }
                else {
                    LocalVcs localVcs = pinnedAccessor.getLocalVcs();
                    repoDir = tempDir.resolve(repo.tip).resolve(repo.name);
                    QbtUtils.mkdirs(repoDir);
                    repoRepository = localVcs.createWorkingRepo(repoDir);
                }

                pinnedAccessor.findCommit(repoDir);

                List<VcsVersionDigest> bases = Lists.newArrayList();
                for(QbtManifest parentManifest : parentManifests) {
                    RepoManifest parentRepoManifest = parentManifest.repos.get(repo);
                    if(parentRepoManifest == null) {
                        continue;
                    }
                    VcsVersionDigest newBase = parentRepoManifest.version;
                    config.localPinsRepo.requirePin(repo, newBase).findCommit(repoDir);
                    boolean covered = false;
                    for(VcsVersionDigest base : bases) {
                        if(repoRepository.isAncestorOf(newBase, base)) {
                            covered = true;
                            break;
                        }
                    }
                    if(covered) {
                        continue;
                    }
                    bases.add(newBase);
                }
                Set<VcsVersionDigest> basesSet = ImmutableSet.copyOf(bases);

                LOGGER.debug("[" + repo + "] bases = " + bases);

                if(basesSet.contains(tip)) {
                    continue;
                }

                Map<VcsVersionDigest, CommitData> revWalk = repoRepository.revWalk(bases, ImmutableList.of(tip));
                Iterable<Pair<VcsVersionDigest, CommitData>> revWalkFlat = CommitDataUtils.revWalkFlatten(revWalk, ImmutableList.of(tip));
                boolean exoticExit = false;
                for(Pair<VcsVersionDigest, CommitData> p : revWalkFlat) {
                    VcsVersionDigest commit = p.getLeft();
                    CommitData cd = p.getRight();
                    for(VcsVersionDigest parent : cd.get(CommitData.PARENTS)) {
                        if(revWalk.containsKey(parent)) {
                            continue;
                        }
                        if(basesSet.contains(parent)) {
                            continue;
                        }
                        LOGGER.error("[" + repo + "] Exotic exit from bases..tip via " + commit.getRawDigest() + " -> " + parent.getRawDigest());
                        exoticExit = true;
                        break;
                    }
                    if(exoticExit) {
                        break;
                    }
                }
                if(exoticExit) {
                    fail = true;
                    continue;
                }

                CommitData newCd = revWalk.get(tip);
                newCd = newCd.set(CommitData.MESSAGE, metaCurrentCd.get(CommitData.MESSAGE));
                newCd = newCd.set(CommitData.PARENTS, ImmutableList.copyOf(bases));
                VcsVersionDigest newTip = repoRepository.createCommit(newCd);

                if(newTip.equals(tip)) {
                    continue;
                }

                changed = true;
                config.localPinsRepo.addPin(repo, repoDir, newTip);
                newManifest = newManifest.withRepoVersion(repo, newTip);
                LOGGER.info("[" + repo + "] Rebuilt " + revWalk.size() + " commit(s) behind " + tip.getRawDigest() + " -> " + newTip.getRawDigest());
            }
        }
        if(fail) {
            return 1;
        }
        if(!changed) {
            LOGGER.info("No change.");
            return 0;
        }
        manifestResult.deparse(newManifest.build());
        VcsVersionDigest commit = metaRepository.commit(true, metaCurrentCd.get(CommitData.MESSAGE), CommitLevel.MODIFIED);
        LOGGER.info("[meta] Committed (amend) " + commit.getRawDigest());
        return 0;
    }
}
