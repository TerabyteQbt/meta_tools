package meta_tools.commit;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import misc1.commons.Maybe;
import misc1.commons.options.NamedBooleanFlagOptionsFragment;
import misc1.commons.options.NamedStringSingletonArgumentOptionsFragment;
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
import qbt.QbtTempDir;
import qbt.QbtUtils;
import qbt.RepoManifest;
import qbt.VcsVersionDigest;
import qbt.config.QbtConfig;
import qbt.options.ConfigOptionsDelegate;
import qbt.options.ManifestOptionsDelegate;
import qbt.options.ManifestOptionsResult;
import qbt.options.RepoActionOptionsDelegate;
import qbt.repo.LocalRepoAccessor;
import qbt.repo.PinnedRepoAccessor;
import qbt.utils.ProcessHelper;
import qbt.vcs.Repository;
import qbt.vcs.VcsRegistry;

public final class Commit extends QbtCommand<Commit.Options> {
    private static final Logger LOGGER = LoggerFactory.getLogger(Commit.class);

    @QbtCommandName("commit")
    public static interface Options extends QbtCommandOptions {
        public static final ConfigOptionsDelegate<Options> config = new ConfigOptionsDelegate<Options>();
        public static final ManifestOptionsDelegate<Options> manifest = new ManifestOptionsDelegate<Options>();
        public static final RepoActionOptionsDelegate<Options> repos = new RepoActionOptionsDelegate<Options>(RepoActionOptionsDelegate.NoArgsBehaviour.OVERRIDES);
        public static final OptionsFragment<Options, ?, Boolean> amend = new NamedBooleanFlagOptionsFragment<Options>(ImmutableList.of("--amend"), "Amend existing commit instead of making a new one");
        public static final OptionsFragment<Options, ?, Boolean> force = new NamedBooleanFlagOptionsFragment<Options>(ImmutableList.of("--force", "-f"), "Forcibly rebuild history/state to match recommended state rather than checking expected starting conditions closely and carefully avoiding history/state loss");
        public static final OptionsFragment<Options, ?, String> metaVcs = new NamedStringSingletonArgumentOptionsFragment<Options>(ImmutableList.of("--metaVcs"), Maybe.of("git"), "VCS for meta");
        public static final OptionsFragment<Options, ?, String> message = new NamedStringSingletonArgumentOptionsFragment<Options>(ImmutableList.of("--message", "-m"), Maybe.<String>of(null), "Commit message");
    }

    @Override
    public Class<Options> getOptionsClass() {
        return Options.class;
    }

    @Override
    public HelpTier getHelpTier() {
        return HelpTier.COMMON;
    }

    @Override
    public String getDescription() {
        return "commit status of all satellites and all changes in meta";
    }

    private interface CommitMaker {
        VcsVersionDigest commit(String message);
    }

    @Override
    public int run(OptionsResults<? extends Options> options) throws Exception {
        QbtConfig config = Options.config.getConfig(options);
        ManifestOptionsResult manifestResult = Options.manifest.getResult(options);
        QbtManifest manifest = manifestResult.parse();
        Collection<PackageTip> repos = Options.repos.getRepos(config, manifest, options);

        final boolean amend = options.get(Options.amend);
        final boolean force = options.get(Options.force);
        String metaVcs = options.get(Options.metaVcs);

        Path metaDir = QbtUtils.findInMeta("", null);
        Repository metaRepository = VcsRegistry.getLocalVcs(metaVcs).getRepository(metaDir);
        if(!metaRepository.isClean()) {
            throw new IllegalArgumentException("Meta is dirty!");
        }

        ImmutableList.Builder<QbtManifest> parentManifestsBuilder = ImmutableList.builder();
        for(VcsVersionDigest metaParent : metaRepository.getCommitData(metaRepository.getCurrentCommit()).parents) {
            parentManifestsBuilder.add(QbtManifest.parse(metaParent.getRawDigest() + ":qbt-manifest", metaRepository.showFile(metaParent, "qbt-manifest")));
        }
        final List<QbtManifest> parentManifests = parentManifestsBuilder.build();

        final ImmutableMap.Builder<PackageTip, CommitMaker> commitsBuilder = ImmutableMap.builder();
        final ImmutableList.Builder<String> messagePrompt = ImmutableList.builder();
        boolean fail = false;
        for(final PackageTip repo : repos) {
            RepoManifest repoManifest = manifest.repos.get(repo);

            final LocalRepoAccessor localRepoAccessor = config.localRepoFinder.findLocalRepo(repo);
            if(localRepoAccessor == null) {
                continue;
            }
            final Repository repoRepository = localRepoAccessor.vcs.getRepository(localRepoAccessor.dir);
            final VcsVersionDigest currentRepoVersion = repoRepository.getCurrentCommit();
            LOGGER.debug("[" + repo + "] currentRepoVersion = " + currentRepoVersion);

            final VcsVersionDigest manifestRepoVersion = repoManifest.version;
            LOGGER.debug("[" + repo + "] manifestRepoVersion = " + manifestRepoVersion);
            final PinnedRepoAccessor pinnedAccessor = config.localPinsRepo.requirePin(repo, repoManifest.version);
            pinnedAccessor.findCommit(localRepoAccessor.dir);

            class CommitMakerMaker {
                public boolean make() {
                    return amend ? makeAmend() : makeNonAmend();
                }

                private void addCommit(final CommitMaker commitMaker) {
                    commitsBuilder.put(repo, new CommitMaker() {
                        @Override
                        public VcsVersionDigest commit(String message) {
                            VcsVersionDigest commit = commitMaker.commit(message);
                            pinnedAccessor.addPin(localRepoAccessor.dir, commit);
                            return commit;
                        }
                    });
                }

                private boolean makeNonAmend() {
                    if(manifestRepoVersion.equals(currentRepoVersion)) {
                        if(repoRepository.isClean()) {
                            // nothing to do
                            return false;
                        }

                        // manifest and satellite matched but dirty, this is "good" and we'll make a new commit
                        addCommit(new CommitMaker() {
                            @Override
                            public VcsVersionDigest commit(String message) {
                                VcsVersionDigest commit = repoRepository.commitAll(message);
                                LOGGER.info("[" + repo + "] Committed " + commit.getRawDigest());
                                return commit;
                            }
                        });
                        messagePrompt.add("[" + repo + "] Dirty, new commit");
                        return false;
                    }

                    // not a blessed state
                    if(!force) {
                        LOGGER.error("[" + repo + "] Current state unintelligible!");
                        return true;
                    }

                    // uh, OK, time for some violence
                    addCommit(new CommitMaker() {
                        @Override
                        public VcsVersionDigest commit(String message) {
                            int removed = repoRepository.revWalk(manifestRepoVersion, currentRepoVersion).size();
                            int added = repoRepository.revWalk(currentRepoVersion, manifestRepoVersion).size();
                            if(!repoRepository.isClean()) {
                                repoRepository.commitAll("placeholder");
                            }
                            VcsVersionDigest commit = repoRepository.commitCrosswindSquash(ImmutableList.of(manifestRepoVersion), message);
                            LOGGER.info("[" + repo + "] Committed (crosswound squash onto " + manifestRepoVersion.getRawDigest() + ", removed " + removed + ", added " + added + ") " + commit.getRawDigest());
                            return commit;
                        }
                    });
                    messagePrompt.add("[" + repo + "] Dirty and a mess, crosswind squash");
                    return false;
                }

                private boolean makeAmend() {
                    // this gets weird, we need to see parent manifests
                    ImmutableList.Builder<VcsVersionDigest> expectedParentsBuilder = ImmutableList.builder();
                    for(QbtManifest parentManifest : parentManifests) {
                        RepoManifest parentRepoManifest = parentManifest.repos.get(repo);
                        if(parentRepoManifest == null) {
                            continue;
                        }
                        expectedParentsBuilder.add(parentRepoManifest.version);
                    }
                    final List<VcsVersionDigest> expectedParents = expectedParentsBuilder.build();
                    LOGGER.debug("[" + repo + "] expectedParents = " + expectedParents);

                    if(manifestRepoVersion.equals(currentRepoVersion) && expectedParents.equals(repoRepository.getCommitData(currentRepoVersion).parents)) {
                        // satellite HEAD is where it should be and the history makes sense (HEAD in meta only added one commit)

                        // well, this is a mess:
                        //
                        // if the repo is dirty we definitely want to amend and
                        // I think we may or may not want to rewrite the
                        // message
                        //
                        // if the repo is clean we might want to amend?
                        //
                        // similar to how `git commit --amend` will rewrite
                        // commit even if tree and message are identical, we
                        // decide to always rewrite (amend)

                        addCommit(new CommitMaker() {
                            @Override
                            public VcsVersionDigest commit(String message) {
                                VcsVersionDigest commit = repoRepository.commitAllAmend(message);
                                LOGGER.info("[" + repo + "] Committed (amend of " + currentRepoVersion.getRawDigest() + ") " + commit.getRawDigest());
                                return commit;
                            }
                        });
                        messagePrompt.add("[" + repo + "] " + (repoRepository.isClean() ? "Clean" : "Dirty") + ", amend");
                        return false;
                    }

                    if(manifestRepoVersion.equals(currentRepoVersion) && ImmutableList.of(manifestRepoVersion).equals(expectedParents)) {
                        // satellite HEAD is where it should be and the history makes other sense (HEAD in meta made no change)

                        if(repoRepository.isClean()) {
                            // nothing to do
                            return false;
                        }

                        // satellite was unchanged in this commit and we're dirty, we'll make a new commit
                        addCommit(new CommitMaker() {
                            @Override
                            public VcsVersionDigest commit(String message) {
                                VcsVersionDigest commit = repoRepository.commitAll(message);
                                LOGGER.info("[" + repo + "] Committed " + commit.getRawDigest());
                                return commit;
                            }
                        });
                        messagePrompt.add("[" + repo + "] Dirty, new commit");
                        return false;
                    }

                    // not a blessed state
                    if(!force) {
                        LOGGER.error("[" + repo + "] Current state unintelligible!");
                        return true;
                    }

                    // uh, OK, time for some violence
                    addCommit(new CommitMaker() {
                        @Override
                        public VcsVersionDigest commit(String message) {
                            if(!repoRepository.isClean()) {
                                repoRepository.commitAll("placeholder");
                            }
                            VcsVersionDigest commit = repoRepository.commitCrosswindSquash(expectedParents, message);
                            LOGGER.info("[" + repo + "] Committed (crosswound squash onto " + Lists.transform(expectedParents, VcsVersionDigest.DEPARSE_FUNCTION) + ") " + commit.getRawDigest());
                            return commit;
                        }
                    });
                    messagePrompt.add("[" + repo + "] Dirty and a mess, crosswind squash");
                    return false;
                }
            }
            if(new CommitMakerMaker().make()) {
                fail = true;
            }
        }
        if(fail) {
            return 1;
        }
        for(String line : messagePrompt.build()) {
            LOGGER.debug("messagePrompt: " + line);
        }
        String message = options.get(Options.message);
        if(message == null) {
            if(amend) {
                message = metaRepository.getCommitData(metaRepository.getCurrentCommit()).message;
            }
            else {
                ImmutableList.Builder<String> promptLines = ImmutableList.builder();
                promptLines.add("");
                promptLines.add("");
                for(String line : messagePrompt.build()) {
                    promptLines.add("# " + line);
                }

                try(QbtTempDir tempDir = new QbtTempDir()) {
                    Path promptFile = tempDir.resolve("commit.msg");
                    QbtUtils.writeLines(promptFile, promptLines.build());
                    String editor = System.getenv("EDITOR");
                    if(editor == null) {
                        // suck it, emacs!
                        editor = "vim";
                    }
                    new ProcessHelper(Paths.get("/"), editor, promptFile.toString()).inheritInput().inheritOutput().inheritError().completeVoid();
                    LinkedList<String> messageLines = Lists.newLinkedList(QbtUtils.readLines(promptFile));
                    for(Iterator<String> i = messageLines.iterator(); i.hasNext(); ) {
                        String line = i.next();
                        if(line.startsWith("#")) {
                            i.remove();
                        }
                    }
                    while(!messageLines.isEmpty() && messageLines.peekFirst().trim().equals("")) {
                        messageLines.removeFirst();
                    }
                    while(!messageLines.isEmpty() && messageLines.peekLast().trim().equals("")) {
                        messageLines.removeLast();
                    }
                    if(messageLines.isEmpty()) {
                        LOGGER.error("No message?  Bailing.");
                        return 1;
                    }
                    message = Joiner.on('\n').join(messageLines);
                }
            }
        }
        QbtManifest.Builder newManifest = manifest.builder();
        for(Map.Entry<PackageTip, CommitMaker> e : commitsBuilder.build().entrySet()) {
            PackageTip repo = e.getKey();
            VcsVersionDigest repoVersion = e.getValue().commit(message);
            newManifest = newManifest.with(repo, manifest.repos.get(repo).builder().withVersion(repoVersion).build());
        }
        manifestResult.deparse(newManifest.build());
        if(!amend) {
            VcsVersionDigest commit = metaRepository.commitAll(message);
            LOGGER.info("[meta] Committed " + commit.getRawDigest());
        }
        else {
            VcsVersionDigest commit = metaRepository.commitAllAmend(message);
            LOGGER.info("[meta] Committed (amend) " + commit.getRawDigest());
        }
        return 0;
    }
}
