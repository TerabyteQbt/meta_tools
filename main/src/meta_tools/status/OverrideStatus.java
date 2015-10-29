package meta_tools.status;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import java.util.Collection;
import misc1.commons.options.OptionsResults;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qbt.HelpTier;
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
import qbt.tip.RepoTip;
import qbt.vcs.LocalVcs;
import qbt.vcs.Repository;

public final class OverrideStatus extends QbtCommand<OverrideStatus.Options> {
    private static final Logger LOGGER = LoggerFactory.getLogger(OverrideStatus.class);

    @QbtCommandName("status")
    public static interface Options extends QbtCommandOptions {
        public static final ConfigOptionsDelegate<Options> config = new ConfigOptionsDelegate<Options>();
        public static final ManifestOptionsDelegate<Options> manifest = new ManifestOptionsDelegate<Options>();
        public static final RepoActionOptionsDelegate<Options> repos = new RepoActionOptionsDelegate<Options>(RepoActionOptionsDelegate.NoArgsBehaviour.OVERRIDES);
    }

    @Override
    public Class<Options> getOptionsClass() {
        return Options.class;
    }

    @Override
    public String getDescription() {
        return "show the status of [dirty] override";
    }

    @Override
    public int run(OptionsResults<? extends Options> options) throws Exception {
        QbtConfig config = Options.config.getConfig(options);
        QbtManifest manifest = Options.manifest.getResult(options).parse();
        Collection<RepoTip> repos = Options.repos.getRepos(config, manifest, options);

        for(RepoTip repo : repos) {
            RepoManifest repoManifest = manifest.repos.get(repo);
            LocalRepoAccessor localRepoAccessor = config.localRepoFinder.findLocalRepo(repo);

            if(localRepoAccessor == null) {
                continue;
            }

            LocalVcs vcs = localRepoAccessor.vcs;
            VcsVersionDigest manifestVersion = repoManifest.version;
            Repository repoRepository = localRepoAccessor.vcs.getRepository(localRepoAccessor.dir);
            VcsVersionDigest repoVersion = repoRepository.getCurrentCommit();
            config.localPinsRepo.requirePin(repo, manifestVersion).findCommit(localRepoAccessor.dir);

            int commitsAhead = repoRepository.revWalk(ImmutableList.of(manifestVersion), ImmutableList.of(repoVersion)).size();
            int commitsBehind = repoRepository.revWalk(ImmutableList.of(repoVersion), ImmutableList.of(manifestVersion)).size();
            boolean isDirty = !repoRepository.isClean();

            ImmutableList.Builder<String> bannerBuilder = ImmutableList.builder();
            if(commitsAhead > 0) {
                bannerBuilder.add(commitsAhead + " commit(s) ahead");
            }
            if(commitsBehind > 0) {
                bannerBuilder.add(commitsBehind + " commit(s) behind");
            }
            if(isDirty) {
                bannerBuilder.add("dirty");
            }
            ImmutableList<String> banner = bannerBuilder.build();
            if(!banner.isEmpty()) {
                LOGGER.info("[" + repo + "] " + Joiner.on(", ").join(banner));
            }
        }
        return 0;
    }

    @Override
    public HelpTier getHelpTier() {
        return HelpTier.COMMON;
    }
}
