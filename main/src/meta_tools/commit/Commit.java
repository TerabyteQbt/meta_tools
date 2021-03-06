package meta_tools.commit;

import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import misc1.commons.options.OptionsFragment;
import misc1.commons.options.OptionsLibrary;
import misc1.commons.options.OptionsResults;
import misc1.commons.ph.ProcessHelper;
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
import qbt.manifest.current.QbtManifest;
import qbt.manifest.current.RepoManifest;
import qbt.options.ConfigOptionsDelegate;
import qbt.repo.LocalRepoAccessor;
import qbt.repo.PinnedRepoAccessor;
import qbt.tip.RepoTip;
import qbt.vcs.CommitData;
import qbt.vcs.CommitLevel;
import qbt.vcs.Repository;
import qbt.vcs.TreeAccessor;
import qbt.vcs.VcsRegistry;

public final class Commit extends QbtCommand<Commit.Options> {
    private static final Logger LOGGER = LoggerFactory.getLogger(Commit.class);

    @QbtCommandName("commit")
    public static interface Options extends QbtCommandOptions {
        public static final OptionsLibrary<Options> o = OptionsLibrary.of();
        public static final ConfigOptionsDelegate<Options> config = new ConfigOptionsDelegate<Options>();
        public static final OptionsFragment<Options, Boolean> amend = o.zeroArg("amend").transform(o.flag()).helpDesc("Amend existing commit instead of making a new one");
        public static final OptionsFragment<Options, String> metaVcs = o.oneArg("metaVcs").transform(o.singleton("git")).helpDesc("VCS for meta");
        public static final OptionsFragment<Options, String> message = o.oneArg("message", "m").transform(o.singleton(null)).helpDesc("Commit message");
        public static final OptionsFragment<Options, Boolean> aggressive = o.zeroArg("a").transform(o.flag()).helpDesc("Commit harder.");
        public static final OptionsFragment<Options, Boolean> veryAggressive = o.zeroArg("A").transform(o.flag()).helpDesc("Commit even harder.");
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
        final CommitLevel commitLevel;
        {
            CommitLevel commitLevelTemp = CommitLevel.STAGED;
            if(options.get(Options.aggressive)) {
                commitLevelTemp = CommitLevel.MODIFIED;
            }
            if(options.get(Options.veryAggressive)) {
                commitLevelTemp = CommitLevel.UNTRACKED;
            }
            commitLevel = commitLevelTemp;
        }

        final boolean amend = options.get(Options.amend);
        String metaVcs = options.get(Options.metaVcs);

        Path metaDir = QbtUtils.findInMeta("", null);
        final Repository metaRepository = VcsRegistry.getLocalVcs(metaVcs).getRepository(metaDir);

        TreeAccessor stagedTree = metaRepository.getTreeAccessor(metaRepository.getIndexTree());
        QbtManifest stagedManifest = config.manifestParser.parse(ImmutableList.copyOf(stagedTree.requireFileLines("qbt-manifest")));
        QbtManifest wtManifest = config.manifestParser.parse(QbtUtils.readLines(metaDir.resolve("qbt-manifest")));
        ImmutableList.Builder<QbtManifest> parentManifestsBuilder = ImmutableList.builder();
        for(VcsVersionDigest metaParent : metaRepository.getCommitData(metaRepository.getCurrentCommit()).get(CommitData.PARENTS)) {
            parentManifestsBuilder.add(config.manifestParser.parse(ImmutableList.copyOf(metaRepository.showFile(metaParent, "qbt-manifest"))));
        }
        final List<QbtManifest> parentManifests = parentManifestsBuilder.build();

        final ImmutableMap.Builder<RepoTip, CommitMaker> commitsBuilder = ImmutableMap.builder();
        final ImmutableList.Builder<String> messagePrompt = ImmutableList.builder();
        boolean fail = false;
        ImmutableSet.Builder<RepoTip> allRepos = ImmutableSet.builder();
        allRepos.addAll(stagedManifest.repos.keySet());
        allRepos.addAll(wtManifest.repos.keySet());
        for(final RepoTip repo : allRepos.build()) {
            RepoManifest stagedRepoManifest = stagedManifest.repos.get(repo);
            RepoManifest wtRepoManifest = wtManifest.repos.get(repo);

            final LocalRepoAccessor localRepoAccessor = config.localRepoFinder.findLocalRepo(repo);
            if(localRepoAccessor == null) {
                continue;
            }
            final Repository repoRepository = localRepoAccessor.vcs.getRepository(localRepoAccessor.dir);
            final VcsVersionDigest currentRepoVersion = repoRepository.getCurrentCommit();
            LOGGER.debug("[" + repo + "] currentRepoVersion = " + currentRepoVersion);

            final VcsVersionDigest stagedManifestRepoVersion = stagedRepoManifest == null ? null : stagedRepoManifest.version.get();
            final VcsVersionDigest wtManifestRepoVersion = wtRepoManifest == null ? null : wtRepoManifest.version.get();
            LOGGER.debug("[" + repo + "] stagedManifestRepoVersion = " + stagedManifestRepoVersion);
            LOGGER.debug("[" + repo + "] wtManifestRepoVersion = " + wtManifestRepoVersion);

            if(stagedManifestRepoVersion != null && !stagedManifestRepoVersion.equals(currentRepoVersion)) {
                LOGGER.error("[" + repo + "] Current state unintelligible: HEAD does not match staged manifest");
                fail = true;
                continue;
            }

            if(wtManifestRepoVersion != null && !wtManifestRepoVersion.equals(currentRepoVersion)) {
                LOGGER.error("[" + repo + "] Current state unintelligible: HEAD does not match working tree manifest");
                fail = true;
                continue;
            }

            final PinnedRepoAccessor pinnedAccessor = config.localPinsRepo.requirePin(repo, currentRepoVersion);
            pinnedAccessor.findCommit(localRepoAccessor.dir);

            class CommitMakerMaker {
                public boolean make() {
                    return amend ? makeAmend() : makeNonAmend();
                }

                private void addCommit(final CommitMaker commitMaker) {
                    commitsBuilder.put(repo, (message) -> {
                        VcsVersionDigest commit = commitMaker.commit(message);
                        config.localPinsRepo.addPin(repo, localRepoAccessor.dir, commit);
                        return commit;
                    });
                }

                private boolean makeNonAmend() {
                    if(repoRepository.isClean(commitLevel)) {
                        // nothing to do
                        return false;
                    }

                    // we'll make a new commit
                    addCommit((message) -> {
                        VcsVersionDigest commit = repoRepository.commit(false, message, commitLevel);
                        LOGGER.info("[" + repo + "] Committed " + commit.getRawDigest());
                        return commit;
                    });
                    messagePrompt.add("[" + repo + "] Dirty, new commit");
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
                        expectedParentsBuilder.add(parentRepoManifest.version.get());
                    }
                    final List<VcsVersionDigest> expectedParents = expectedParentsBuilder.build();
                    LOGGER.debug("[" + repo + "] expectedParents = " + expectedParents);

                    if(expectedParents.equals(repoRepository.getCommitData(currentRepoVersion).get(CommitData.PARENTS))) {
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

                        addCommit((message) -> {
                            VcsVersionDigest commit = repoRepository.commit(true, message, commitLevel);
                            LOGGER.info("[" + repo + "] Committed (amend of " + currentRepoVersion.getRawDigest() + ") " + commit.getRawDigest());
                            return commit;
                        });
                        messagePrompt.add("[" + repo + "] " + (repoRepository.isClean(commitLevel) ? "Clean" : "Dirty") + ", amend");
                        return false;
                    }

                    if(ImmutableSet.copyOf(expectedParents).equals(ImmutableSet.of(currentRepoVersion))) {
                        // satellite HEAD is where it should be and the history makes other sense (HEAD in meta made no change)

                        if(repoRepository.isClean(commitLevel)) {
                            // nothing to do
                            return false;
                        }

                        // satellite was unchanged in this commit and we're dirty, we'll make a new commit
                        addCommit((message) -> {
                            VcsVersionDigest commit = repoRepository.commit(false, message, commitLevel);
                            LOGGER.info("[" + repo + "] Committed " + commit.getRawDigest());
                            return commit;
                        });
                        messagePrompt.add("[" + repo + "] Dirty, new commit");
                        return false;
                    }

                    // not a blessed state
                    LOGGER.error("[" + repo + "] Current state unintelligible: --amend somewhere ... weird.");
                    return true;
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
                message = metaRepository.getCommitData(metaRepository.getCurrentCommit()).get(CommitData.MESSAGE);
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
                    ProcessHelper.of(Paths.get("/"), editor, promptFile.toString()).inheritInput().inheritOutput().inheritError().run().requireSuccess();
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
        QbtManifest.Builder newStagedManifest = stagedManifest.builder();
        QbtManifest.Builder newWtManifest = wtManifest.builder();
        for(Map.Entry<RepoTip, CommitMaker> e : commitsBuilder.build().entrySet()) {
            RepoTip repo = e.getKey();
            VcsVersionDigest repoVersion = e.getValue().commit(message);
            Function<Optional<RepoManifest.Builder>, Optional<RepoManifest.Builder>> f = (mrmb) -> mrmb.map((rmb) -> rmb.set(RepoManifest.VERSION, Optional.of(repoVersion)));
            newStagedManifest = newStagedManifest.transformOptional(repo, f);
            newWtManifest = newWtManifest.transformOptional(repo, f);
        }
        QbtUtils.writeLines(metaDir.resolve("qbt-manifest"), config.manifestParser.deparse(newWtManifest.build()));
        metaRepository.setIndexTree(stagedTree.replace("qbt-manifest", config.manifestParser.deparse(newStagedManifest.build())).getDigest());
        VcsVersionDigest commit = metaRepository.commit(amend, message, commitLevel);
        LOGGER.info("[meta] Committed" + (amend ? " (amend)" : "") + " " + commit.getRawDigest());
        return 0;
    }
}
