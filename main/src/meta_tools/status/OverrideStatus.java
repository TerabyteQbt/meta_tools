package meta_tools.status;

import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.util.Collection;
import misc1.commons.options.NamedBooleanFlagOptionsFragment;
import misc1.commons.options.OptionsFragment;
import misc1.commons.options.OptionsResults;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qbt.HelpTier;
import qbt.PackageTip;
import qbt.QbtCommand;
import qbt.QbtCommandName;
import qbt.QbtCommandOptions;
import qbt.QbtManifest;
import qbt.RepoManifest;
import qbt.VcsVersionDigest;
import qbt.config.QbtConfig;
import qbt.options.ConfigOptionsDelegate;
import qbt.options.ManifestOptionsDelegate;
import qbt.options.RepoActionOptionsDelegate;
import qbt.repo.LocalRepoAccessor;
import qbt.repo.PinnedRepoAccessor;
import qbt.vcs.CommitDataUtils;
import qbt.vcs.LocalVcs;
import qbt.vcs.Repository;

public final class OverrideStatus extends QbtCommand<OverrideStatus.Options> {
    private static final Logger LOGGER = LoggerFactory.getLogger(OverrideStatus.class);

    @QbtCommandName("status")
    public static interface Options extends QbtCommandOptions {
        public static final ConfigOptionsDelegate<Options> config = new ConfigOptionsDelegate<Options>();
        public static final ManifestOptionsDelegate<Options> manifest = new ManifestOptionsDelegate<Options>();
        public static final RepoActionOptionsDelegate<Options> repos = new RepoActionOptionsDelegate<Options>(RepoActionOptionsDelegate.NoArgsBehaviour.OVERRIDES);
        public static final OptionsFragment<Options, ?, Boolean> showBoring = new NamedBooleanFlagOptionsFragment<Options>(ImmutableList.of("--show-boring"), "Show overrides even if they're clean and match manifest version");

    }

    @Override
    public Class<Options> getOptionsClass() {
        return Options.class;
    }

    @Override
    public String getDescription() {
        return "show the status of an override repository";
    }

    @Override
    public int run(OptionsResults<? extends Options> options) throws Exception {
        QbtConfig config = Options.config.getConfig(options);
        QbtManifest manifest = Options.manifest.getResult(options).parse();
        Collection<PackageTip> repos = Options.repos.getRepos(config, manifest, options);
        boolean showBoring = options.get(Options.showBoring);

        for(PackageTip repoTip : repos) {
            RepoManifest repoManifest = manifest.repos.get(repoTip);
            LocalRepoAccessor localRepoAccessor = config.localRepoFinder.findLocalRepo(repoTip);

            if(localRepoAccessor == null) {
                LOGGER.info("not overridden: {}\n", repoTip);
                continue;
            }

            LocalVcs vcs = localRepoAccessor.vcs;

            OverrideState overrideState = getOverrideState(repoTip, repoManifest, config, vcs);

            if(!showBoring && isBoring(overrideState)) {
                continue;
            }

            LOGGER.info("override {}:", repoTip);
            LOGGER.info("  at commit '{}'", overrideState.oneliner);

            boolean printCanonical = false;
            if(overrideState.commitsAhead > 0) {
                LOGGER.info("  override is ahead of canonical version by {} commits", overrideState.commitsAhead);
                printCanonical = true;
            }
            if(overrideState.commitsBehind > 0) {
                LOGGER.info("  override is behind canonical version by {} commits", overrideState.commitsBehind);
                printCanonical = true;
            }

            if(printCanonical) {
                LOGGER.info("    canonical version is '{}'", overrideState.canonicalOneliner);
            }

            if(overrideState.isDirty) {
                LOGGER.info("  is dirty or contains untracked files");
            }

            LOGGER.info("");
        }
        return 0;
    }

    private boolean isBoring(OverrideState overrideState) {
        if(overrideState.commitsAhead != 0) {
            return false;
        }
        if(overrideState.commitsBehind != 0) {
            return false;
        }
        if(overrideState.isDirty) {
            return false;
        }
        return true;
    }

    public static OverrideState getOverrideState(PackageTip repoTip, RepoManifest repoManifest, QbtConfig config, LocalVcs vcs) {
        Path repoPath = config.localRepoFinder.findLocalRepo(repoTip).dir;
        Repository overrideRepository = vcs.getRepository(repoPath);
        VcsVersionDigest repoHash = overrideRepository.getCurrentCommit();
        VcsVersionDigest canonicalHash = repoManifest.version;

        String oneliner = CommitDataUtils.getOneLiner(overrideRepository, overrideRepository.getCurrentCommit());

        if(!overrideRepository.commitExists(canonicalHash)) {
            PinnedRepoAccessor pinnedAccessor = config.localPinsRepo.requirePin(repoTip, canonicalHash);
            pinnedAccessor.findCommit(repoPath);
        }
        String canonicalOneliner = CommitDataUtils.getOneLiner(overrideRepository, canonicalHash);

        int commitsAhead = overrideRepository.revWalk(canonicalHash, repoHash).size();
        int commitsBehind = overrideRepository.revWalk(repoHash, canonicalHash).size();

        boolean isDirty = !overrideRepository.isClean();

        return new OverrideState(oneliner, canonicalOneliner, commitsAhead, commitsBehind, isDirty);
    }

    public static class OverrideState {
        public final String oneliner;
        public final String canonicalOneliner;
        public final int commitsAhead;
        public final int commitsBehind;
        public final boolean isDirty;

        public OverrideState(String oneliner, String canonicalOneliner, int commitsAhead, int commitsBehind, boolean isDirty) {
            this.oneliner = oneliner;
            this.canonicalOneliner = canonicalOneliner;
            this.commitsAhead = commitsAhead;
            this.commitsBehind = commitsBehind;
            this.isDirty = isDirty;
        }
    }

    @Override
    public HelpTier getHelpTier() {
        return HelpTier.COMMON;
    }
}
