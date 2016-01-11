package meta_tools.merge;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Sets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import misc1.commons.ds.LazyCollector;
import misc1.commons.options.OptionsFragment;
import misc1.commons.options.OptionsLibrary;
import misc1.commons.options.OptionsResults;
import misc1.commons.ph.ProcessHelper;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qbt.HelpTier;
import qbt.QbtCommand;
import qbt.QbtCommandName;
import qbt.QbtCommandOptions;
import qbt.QbtUtils;
import qbt.VcsVersionDigest;
import qbt.config.QbtConfig;
import qbt.mains.MergeManifests;
import qbt.manifest.LegacyQbtManifest;
import qbt.manifest.QbtManifestVersion;
import qbt.manifest.QbtManifestVersions;
import qbt.manifest.current.QbtManifest;
import qbt.manifest.current.RepoManifest;
import qbt.map.DependencyComputer;
import qbt.map.PackageTipDependenciesMapper;
import qbt.repo.LocalRepoAccessor;
import qbt.repo.PinnedRepoAccessor;
import qbt.tip.PackageTip;
import qbt.tip.RepoTip;
import qbt.utils.ProcessHelperUtils;
import qbt.vcs.Repository;

public class ResolveManifestConflicts extends QbtCommand<ResolveManifestConflicts.Options> {
    private static final Logger LOGGER = LoggerFactory.getLogger(ResolveManifestConflicts.class);

    @QbtCommandName("resolveManifestConflicts")
    public static interface Options extends QbtCommandOptions {
        public static final OptionsLibrary<Options> o = OptionsLibrary.of();
        public static final OptionsFragment<Options, MergeManifests.StrategyEnum> strategy = o.oneArg("strategy").transform(o.singleton(null)).transform(o.parseEnum(MergeManifests.StrategyEnum.class)).helpDesc("\"Strategy\" for [attempting resolution in] satellites");
        public static final OptionsFragment<Options, Boolean> noDeps = o.zeroArg("noDeps").transform(o.flag()).helpDesc("Don't insist on resolving in dep order");
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
        return "resolve conflicts in qbt-manifest interactively";
    }

    @Override
    public int run(final OptionsResults<? extends Options> options) throws Exception {
        final QbtConfig config = QbtConfig.parse(QbtUtils.findInMeta("qbt-config", null));
        final MergeManifests.Strategy strategy = options.get(Options.strategy);
        final Path manifestPath = QbtUtils.findInMeta("qbt-manifest", null);
        Triple<ImmutableList<String>, ImmutableList<String>, ImmutableList<String>> inLines = QbtUtils.parseConflictLines(QbtUtils.readLines(manifestPath));
        final LegacyQbtManifest<?, ?> lhsLegacyResult = QbtManifestVersions.parseLegacy(inLines.getLeft());
        final LegacyQbtManifest<?, ?> mhsLegacyResult = QbtManifestVersions.parseLegacy(inLines.getMiddle());
        final LegacyQbtManifest<?, ?> rhsLegacyResult = QbtManifestVersions.parseLegacy(inLines.getRight());
        QbtManifestVersion<?, ?> targetVersion = MergeManifests.chooseTargetVersion(lhsLegacyResult, mhsLegacyResult, rhsLegacyResult);
        return new Object() {
            public <M, B> int run(QbtManifestVersion<M, B> targetVersion) {
                QbtManifest lhsCurrent = lhsLegacyResult.current();
                QbtManifest mhsCurrent = mhsLegacyResult.current();
                QbtManifest rhsCurrent = rhsLegacyResult.current();

                ImmutableMultimap.Builder<RepoTip, RepoTip> repoDeps = ImmutableMultimap.builder();
                if(!options.get(Options.noDeps)) {
                    addDeps(repoDeps, lhsCurrent);
                    addDeps(repoDeps, mhsCurrent);
                    addDeps(repoDeps, rhsCurrent);
                }
                StepsBuilder b = new StepsBuilder(config, strategy, repoDeps.build(), lhsCurrent, mhsCurrent, rhsCurrent);
                b.addSteps(lhsCurrent.repos.keySet(), false);
                b.addSteps(mhsCurrent.repos.keySet(), false);
                b.addSteps(rhsCurrent.repos.keySet(), false);
                ImmutableList<Step> steps = b.build();

                B lhsNew = lhsLegacyResult.upgrade(targetVersion).builder().builder;
                B mhsNew = mhsLegacyResult.upgrade(targetVersion).builder().builder;
                B rhsNew = rhsLegacyResult.upgrade(targetVersion).builder().builder;
                for(Step s : steps) {
                    StepResult sr = s.run();
                    for(Pair<RepoTip, VcsVersionDigest> update : sr.updates) {
                        RepoTip repo = update.getLeft();
                        VcsVersionDigest version = update.getRight();
                        LOGGER.info("Resolved " + repo + " to " + version.getRawDigest());
                        lhsNew = targetVersion.withRepoVersion(lhsNew, repo, version);
                        mhsNew = targetVersion.withRepoVersion(mhsNew, repo, version);
                        rhsNew = targetVersion.withRepoVersion(rhsNew, repo, version);
                    }
                    QbtUtils.writeLines(manifestPath, targetVersion.parser().deparse("LHS", targetVersion.build(lhsNew), "MHS", targetVersion.build(mhsNew), "RHS", targetVersion.build(rhsNew)).getRight());
                    if(sr.allBailNow) {
                        LOGGER.info("Exit requested");
                        return 1;
                    }
                }

                return 0;
            }
        }.run(targetVersion);
    }

    private static void addDeps(ImmutableMultimap.Builder<RepoTip, RepoTip> b, QbtManifest manifest) {
        DependencyComputer dc = new DependencyComputer(manifest);
        PackageTipDependenciesMapper dependenciesMapper = new PackageTipDependenciesMapper();
        for(Map.Entry<RepoTip, RepoManifest> e : manifest.repos.entrySet()) {
            RepoTip repo = e.getKey();
            ImmutableList.Builder<LazyCollector<PackageTip>> depPkgs = ImmutableList.builder();
            for(String packageName : e.getValue().packages.keySet()) {
                PackageTip pkg = repo.toPackage(packageName);
                depPkgs.add(dependenciesMapper.transform(dc.compute(pkg)));
            }
            for(PackageTip depPkg : LazyCollector.unionIterable(depPkgs.build()).forceSet()) {
                RepoTip depRepo = manifest.packageToRepo.get(depPkg);
                if(repo.equals(depRepo)) {
                    // you're cool
                    continue;
                }
                b.put(repo, depRepo);
            }
        }
    }

    private static final class StepResult {
        public final ImmutableList<Pair<RepoTip, VcsVersionDigest>> updates;
        public final boolean allBailNow;

        public StepResult(ImmutableList<Pair<RepoTip, VcsVersionDigest>> updates, boolean allBailNow) {
            this.updates = updates;
            this.allBailNow = allBailNow;
        }
    }

    private interface Step {
        StepResult run();
    }

    private final static class StepsBuilder {
        private final QbtConfig config;
        private final MergeManifests.Strategy strategy;
        private final ImmutableMultimap<RepoTip, RepoTip> repoDeps;
        private final QbtManifest lhs;
        private final QbtManifest mhs;
        private final QbtManifest rhs;

        private final ImmutableList.Builder<Step> stepsBuilder = ImmutableList.builder();
        private final Set<RepoTip> started = Sets.newHashSet();
        private final Set<RepoTip> finished = Sets.newHashSet();

        public StepsBuilder(QbtConfig config, MergeManifests.Strategy strategy, ImmutableMultimap<RepoTip, RepoTip> repoDeps, QbtManifest lhs, QbtManifest mhs, QbtManifest rhs) {
            this.config = config;
            this.strategy = strategy;
            this.repoDeps = repoDeps;
            this.lhs = lhs;
            this.mhs = mhs;
            this.rhs = rhs;
        }

        private void addSteps(Iterable<RepoTip> repos, boolean require) {
            for(RepoTip repo : repos) {
                addSteps(repo, require);
            }
        }

        private VcsVersionDigest requireVersion(QbtManifest manifest, RepoTip repo) {
            RepoManifest repoManifest = manifest.repos.get(repo);
            if(repoManifest == null) {
                throw new IllegalArgumentException("Repo " + repo + " is not present in all manifests");
            }
            return repoManifest.version;
        }

        private void addSteps(final RepoTip repo, boolean require) {
            if(finished.contains(repo)) {
                // if it's been actually built already we've got nothing to do
                return;
            }

            final VcsVersionDigest lhsVersion = requireVersion(lhs, repo);
            final VcsVersionDigest mhsVersion = requireVersion(mhs, repo);
            final VcsVersionDigest rhsVersion = requireVersion(rhs, repo);

            boolean conflicted = !(mhsVersion.equals(lhsVersion) && mhsVersion.equals(rhsVersion));
            if(!conflicted && !require) {
                // if it's not actually conflicted and we haven't been forced to build steps for it we'll skip it
                return;
            }

            final Repository overrideRepo;
            {
                LocalRepoAccessor override = config.localRepoFinder.findLocalRepo(repo);
                if(override != null) {
                    overrideRepo = override.vcs.getRepository(override.dir);
                    if(!overrideRepo.isClean()) {
                        throw new IllegalArgumentException("Repo " + repo + " is overridden and dirty!");
                    }
                }
                else {
                    overrideRepo = null;
                }
            }

            // now we're actually committed to building steps for it, go go go
            if(!started.add(repo)) {
                throw new IllegalArgumentException("Cycle in repo dependencies at " + repo);
            }

            if(!conflicted) {
                // this is a little fucked up, but if we're not conflicted we don't really need our deps
                // if something outwards from us is conflicted it will get our deps transitively
                if(overrideRepo != null) {
                    // already resolved, overridden, we'll update
                    stepsBuilder.add(() -> {
                        PinnedRepoAccessor lhsResult = config.localPinsRepo.requirePin(repo, lhsVersion);
                        lhsResult.findCommit(overrideRepo.getRoot());
                        overrideRepo.checkout(lhsVersion);
                        return new StepResult(ImmutableList.<Pair<RepoTip, VcsVersionDigest>>of(), false);
                    });
                }
                else {
                    // already resolved, not overridden, do nothing
                }
                finished.add(repo);
            }
            else {
                // since we're conflicted we need to actually have our deps resolved (and first)
                addSteps(repoDeps.get(repo), true);
                if(overrideRepo != null) {
                    // conflicted, override, we'll resolve interactively
                    stepsBuilder.add(() -> {
                        PinnedRepoAccessor lhsResult = config.localPinsRepo.requirePin(repo, lhsVersion);
                        lhsResult.findCommit(overrideRepo.getRoot());
                        PinnedRepoAccessor mhsResult = config.localPinsRepo.requirePin(repo, mhsVersion);
                        mhsResult.findCommit(overrideRepo.getRoot());
                        PinnedRepoAccessor rhsResult = config.localPinsRepo.requirePin(repo, rhsVersion);
                        rhsResult.findCommit(overrideRepo.getRoot());

                        if(strategy != null) {
                            try {
                                strategy.invoke(repo, overrideRepo, lhsVersion, mhsVersion, rhsVersion);

                                // Oh, uh, that worked?  Maybe you didn't
                                // set up mergeDriver, maybe you created
                                // the conflict some other wacky way.
                                // Whatever the case, we honor it,
                                // uninteractively.
                                VcsVersionDigest result = overrideRepo.getCurrentCommit();
                                lhsResult.addPin(overrideRepo.getRoot(), result);
                                rhsResult.addPin(overrideRepo.getRoot(), result);
                                return new StepResult(ImmutableList.of(Pair.of(repo, result)), false);
                            }
                            catch(RuntimeException e) {
                                LOGGER.error("Attempted specified resolution strategy for " + repo + " failed", e);
                            }
                        }
                        else {
                            overrideRepo.checkout(lhsVersion);
                        }

                        ProcessHelper p = ProcessHelper.of(overrideRepo.getRoot(), System.getenv("SHELL"), "-i");
                        p = p.apply(ProcessHelperUtils::stripGitEnv);
                        p = p.putEnv("LHS", lhsVersion.getRawDigest().toString());
                        p = p.putEnv("MHS", mhsVersion.getRawDigest().toString());
                        p = p.putEnv("RHS", rhsVersion.getRawDigest().toString());
                        LOGGER.info("Invoking resolution shell for " + repo + "...");
                        LOGGER.info("    LHS is " + lhsVersion.getRawDigest() + " (in $LHS)");
                        LOGGER.info("    MHS is " + mhsVersion.getRawDigest() + " (in $MHS)");
                        LOGGER.info("    RHS is " + rhsVersion.getRawDigest() + " (in $RHS)");
                        LOGGER.info("    Exit with success to indicate HEAD is result");
                        LOGGER.info("    Exit with failure to stop resolving here (but keep previously completed results)");
                        p = p.inheritInput();
                        p = p.inheritOutput();
                        p = p.inheritError();
                        int exitCode = p.run().exitCode;
                        if(exitCode == 0) {
                            VcsVersionDigest result = overrideRepo.getCurrentCommit();
                            lhsResult.addPin(overrideRepo.getRoot(), result);
                            rhsResult.addPin(overrideRepo.getRoot(), result);
                            return new StepResult(ImmutableList.of(Pair.of(repo, result)), false);
                        }
                        else {
                            return new StepResult(ImmutableList.<Pair<RepoTip, VcsVersionDigest>>of(), true);
                        }
                    });
                }
                else {
                    // conflicted, not overridden, not OK
                    throw new IllegalArgumentException("Repo " + repo + " is conflicted, but not overriden");
                }
                finished.add(repo);
            }
        }

        private ImmutableList<Step> build() {
            return stepsBuilder.build();
        }
    }
}
