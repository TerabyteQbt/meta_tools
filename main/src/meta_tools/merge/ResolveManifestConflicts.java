package meta_tools.merge;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Sets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import misc1.commons.Maybe;
import misc1.commons.ds.LazyCollector;
import misc1.commons.options.NamedEnumSingletonArgumentOptionsFragment;
import misc1.commons.options.OptionsFragment;
import misc1.commons.options.OptionsResults;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qbt.HelpTier;
import qbt.NormalDependencyType;
import qbt.PackageManifest;
import qbt.PackageTip;
import qbt.QbtCommand;
import qbt.QbtCommandName;
import qbt.QbtCommandOptions;
import qbt.QbtManifest;
import qbt.QbtUtils;
import qbt.RepoManifest;
import qbt.VcsVersionDigest;
import qbt.config.QbtConfig;
import qbt.mains.MergeManifests;
import qbt.map.DependencyComputer;
import qbt.map.SimpleDependencyComputer;
import qbt.repo.LocalRepoAccessor;
import qbt.repo.PinnedRepoAccessor;
import qbt.utils.ProcessHelper;
import qbt.vcs.Repository;

public class ResolveManifestConflicts extends QbtCommand<ResolveManifestConflicts.Options> {
    private static final Logger LOGGER = LoggerFactory.getLogger(ResolveManifestConflicts.class);

    @QbtCommandName("resolveManifestConflicts")
    public static interface Options extends QbtCommandOptions {
        public static final OptionsFragment<Options, ?, MergeManifests.StrategyEnum> strategy = new NamedEnumSingletonArgumentOptionsFragment<Options, MergeManifests.StrategyEnum>(MergeManifests.StrategyEnum.class, ImmutableList.of("--strategy"), Maybe.<MergeManifests.StrategyEnum>of(null), "\"Strategy\" for [attempting resolution in] satellites");
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
        MergeManifests.Strategy strategy = options.get(Options.strategy);
        Path manifestPath = QbtUtils.findInMeta("qbt-manifest", null);
        Triple<Iterable<String>, Iterable<String>, Iterable<String>> inLines = QbtUtils.parseConflictLines(QbtUtils.readLines(manifestPath));
        QbtManifest lhs = QbtManifest.parse(manifestPath + "@{LHS}", inLines.getLeft());
        QbtManifest mhs = QbtManifest.parse(manifestPath + "@{MHS}", inLines.getMiddle());
        QbtManifest rhs = QbtManifest.parse(manifestPath + "@{RHS}", inLines.getRight());

        StepsBuilder b = new StepsBuilder(config, strategy, lhs, mhs, rhs);
        b.addSteps(lhs.repos.keySet(), false);
        b.addSteps(mhs.repos.keySet(), false);
        b.addSteps(rhs.repos.keySet(), false);
        ImmutableList<Step> steps = b.build();

        for(Step s : steps) {
            StepResult sr = s.run();
            QbtManifest.Builder lhsBuilder = lhs.builder();
            QbtManifest.Builder mhsBuilder = mhs.builder();
            QbtManifest.Builder rhsBuilder = rhs.builder();
            for(Pair<PackageTip, VcsVersionDigest> update : sr.updates) {
                PackageTip repo = update.getLeft();
                VcsVersionDigest version = update.getRight();
                LOGGER.info("Resolved " + repo + " to " + version.getRawDigest());
                lhsBuilder = lhsBuilder.with(repo, lhs.repos.get(repo).builder().withVersion(version).build());
                mhsBuilder = mhsBuilder.with(repo, mhs.repos.get(repo).builder().withVersion(version).build());
                rhsBuilder = rhsBuilder.with(repo, rhs.repos.get(repo).builder().withVersion(version).build());
            }
            lhs = lhsBuilder.build();
            mhs = mhsBuilder.build();
            rhs = rhsBuilder.build();
            QbtUtils.writeLines(manifestPath, QbtManifest.deparseConflicts("LHS", lhs, "MHS", mhs, "RHS", rhs).getRight());
            if(sr.allBailNow) {
                LOGGER.info("Exit requested");
                return 1;
            }
        }

        return 0;
    }

    private static ImmutableMultimap<PackageTip, PackageTip> computeRepoDeps(Iterable<QbtManifest> manifests) {
        ImmutableMultimap.Builder<PackageTip, PackageTip> b = ImmutableMultimap.builder();
        for(QbtManifest manifest : manifests) {
            DependencyComputer<?, LazyCollector<PackageTip>> dc = new SimpleDependencyComputer<LazyCollector<PackageTip>>(manifest) {
                @Override
                protected LazyCollector<PackageTip> map(PackageManifest intermediate, PackageTip packageTip, Map<String, Pair<NormalDependencyType, LazyCollector<PackageTip>>> dependencyResults) {
                    LazyCollector<PackageTip> ret = LazyCollector.of();
                    for(Pair<NormalDependencyType, LazyCollector<PackageTip>> e : dependencyResults.values()) {
                        ret = ret.union(e.getRight());
                    }
                    ret = ret.union(LazyCollector.of(packageTip));
                    return ret;
                }
            };
            for(Map.Entry<PackageTip, RepoManifest> e : manifest.repos.entrySet()) {
                PackageTip repo = e.getKey();
                for(String packageName : e.getValue().packages.keySet()) {
                    PackageTip pkg = repo.replacePackage(packageName);
                    for(PackageTip depPkg : dc.compute(pkg).forceSet()) {
                        PackageTip depRepo = manifest.packageToRepo.get(depPkg);
                        if(repo.equals(depRepo)) {
                            // you're cool
                            continue;
                        }
                        b.put(repo, depRepo);
                    }
                }
            }
        }
        return b.build();
    }

    private static final class StepResult {
        public final ImmutableList<Pair<PackageTip, VcsVersionDigest>> updates;
        public final boolean allBailNow;

        public StepResult(ImmutableList<Pair<PackageTip, VcsVersionDigest>> updates, boolean allBailNow) {
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
        private final QbtManifest lhs;
        private final QbtManifest mhs;
        private final QbtManifest rhs;
        private final ImmutableMultimap<PackageTip, PackageTip> repoDeps;

        private final ImmutableList.Builder<Step> stepsBuilder = ImmutableList.builder();
        private final Set<PackageTip> started = Sets.newHashSet();
        private final Set<PackageTip> finished = Sets.newHashSet();

        public StepsBuilder(QbtConfig config, MergeManifests.Strategy strategy, QbtManifest lhs, QbtManifest mhs, QbtManifest rhs) {
            this.config = config;
            this.strategy = strategy;
            this.lhs = lhs;
            this.mhs = mhs;
            this.rhs = rhs;
            this.repoDeps = computeRepoDeps(ImmutableList.of(lhs, mhs, rhs));
        }

        private void addSteps(Iterable<PackageTip> repos, boolean require) {
            for(PackageTip repo : repos) {
                addSteps(repo, require);
            }
        }

        private VcsVersionDigest requireVersion(QbtManifest manifest, PackageTip repo) {
            RepoManifest repoManifest = manifest.repos.get(repo);
            if(repoManifest == null) {
                throw new IllegalArgumentException("Repo " + repo + " is not present in all manifests");
            }
            return repoManifest.version;
        }

        private void addSteps(final PackageTip repo, boolean require) {
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
                    stepsBuilder.add(new Step() {
                        @Override
                        public StepResult run() {
                            PinnedRepoAccessor lhsResult = config.localPinsRepo.requirePin(repo, lhsVersion);
                            lhsResult.findCommit(overrideRepo.getRoot());
                            overrideRepo.checkout(lhsVersion);
                            return new StepResult(ImmutableList.<Pair<PackageTip, VcsVersionDigest>>of(), false);
                        }
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
                    stepsBuilder.add(new Step() {
                        @Override
                        public StepResult run() {
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
                                    return new StepResult(ImmutableList.of(Pair.of(repo, overrideRepo.getCurrentCommit())), false);
                                }
                                catch(RuntimeException e) {
                                    LOGGER.error("Attempted specified resolution strategy for " + repo + " failed", e);
                                }
                            }
                            else {
                                overrideRepo.checkout(lhsVersion);
                            }

                            ProcessHelper p = new ProcessHelper(overrideRepo.getRoot(), System.getenv("SHELL"), "-i");
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
                            int exitCode = p.completeExitCode();
                            if(exitCode == 0) {
                                VcsVersionDigest result = overrideRepo.getCurrentCommit();
                                lhsResult.addPin(overrideRepo.getRoot(), result);
                                rhsResult.addPin(overrideRepo.getRoot(), result);
                                return new StepResult(ImmutableList.of(Pair.of(repo, result)), false);
                            }
                            else {
                                return new StepResult(ImmutableList.<Pair<PackageTip, VcsVersionDigest>>of(), true);
                            }
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
