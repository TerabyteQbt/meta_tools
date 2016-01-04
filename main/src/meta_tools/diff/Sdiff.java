package meta_tools.diff;

import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import misc1.commons.Maybe;
import misc1.commons.options.NamedBooleanFlagOptionsFragment;
import misc1.commons.options.NamedStringListArgumentOptionsFragment;
import misc1.commons.options.NamedStringSingletonArgumentOptionsFragment;
import misc1.commons.options.OptionsDelegate;
import misc1.commons.options.OptionsException;
import misc1.commons.options.OptionsFragment;
import misc1.commons.options.OptionsResults;
import misc1.commons.options.UnparsedOptionsFragment;
import misc1.commons.ph.ProcessHelper;
import qbt.HelpTier;
import qbt.QbtCommand;
import qbt.QbtCommandName;
import qbt.QbtCommandOptions;
import qbt.QbtTempDir;
import qbt.QbtUtils;
import qbt.VcsVersionDigest;
import qbt.config.QbtConfig;
import qbt.manifest.QbtManifestVersions;
import qbt.manifest.current.QbtManifest;
import qbt.manifest.current.RepoManifest;
import qbt.options.ConfigOptionsDelegate;
import qbt.repo.LocalRepoAccessor;
import qbt.repo.PinnedRepoAccessor;
import qbt.tip.RepoTip;
import qbt.utils.MapDiffer;
import qbt.utils.ProcessHelperUtils;
import qbt.vcs.LocalVcs;
import qbt.vcs.Repository;
import qbt.vcs.git.GitLocalVcs;

public class Sdiff extends QbtCommand<Sdiff.Options> {
    public static final class CommonOptionsDelegate<O> implements OptionsDelegate {
        public final OptionsFragment<O, ?, Boolean> override = new NamedBooleanFlagOptionsFragment<O>(ImmutableList.of("--override", "-o"), "Run commands in overrides");
        public final OptionsFragment<O, ?, ImmutableList<String>> extraArgs = new NamedStringListArgumentOptionsFragment<O>(ImmutableList.of("--extra-arg"), "Extra argument to subcommands (available as positional paramters $1, etc.  in shell)");
    }

    @QbtCommandName("sdiff")
    public static interface Options extends QbtCommandOptions {
        public static final ConfigOptionsDelegate<Options> config = new ConfigOptionsDelegate<Options>();
        public static final CommonOptionsDelegate<Options> commonOptions = new CommonOptionsDelegate<Options>();
        public static final OptionsFragment<Options, ?, String> type = new NamedStringSingletonArgumentOptionsFragment<Options>(ImmutableList.of("--type"), Maybe.<String>of(null), "Type of diff to show");
        public static final OptionsFragment<Options, ?, Boolean> log = new NamedBooleanFlagOptionsFragment<Options>(ImmutableList.of("--log"), "Show a log");
        public static final OptionsFragment<Options, ?, Boolean> diff = new NamedBooleanFlagOptionsFragment<Options>(ImmutableList.of("--diff"), "Show a diff");
        public static final OptionsFragment<Options, ?, Boolean> logDiff = new NamedBooleanFlagOptionsFragment<Options>(ImmutableList.of("--log-diff"), "Show a log diff");
        public static final OptionsFragment<Options, ?, ImmutableList<String>> manifests = new UnparsedOptionsFragment<Options>("\"Manifests\" to diff.  Give a commitlike, \".\" for the working tree manifest, and \"SAT\" for working tree manifest overridden with HEAD from satellites", false, 2, 2);
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
        return "compare qbt-manifests in a friendly manner";
    }

    private static QbtManifest resolveManifest(Path workspaceRoot, QbtConfig config, String arg) throws IOException {
        if(arg.equals(".")) {
            return QbtManifestVersions.parse(QbtUtils.readLines(workspaceRoot.resolve("qbt-manifest")));
        }
        if(arg.equals("SAT")) {
            QbtManifest base = QbtManifestVersions.parse(QbtUtils.readLines(workspaceRoot.resolve("qbt-manifest")));

            QbtManifest.Builder b = base.builder();
            for(Map.Entry<RepoTip, RepoManifest> e : base.repos.entrySet()) {
                RepoTip repo = e.getKey();
                RepoManifest repoManifest = e.getValue();
                VcsVersionDigest version = repoManifest.version;
                LocalRepoAccessor localRepoAccessor = config.localRepoFinder.findLocalRepo(repo);
                if(localRepoAccessor == null) {
                    continue;
                }
                VcsVersionDigest newVersion = localRepoAccessor.vcs.getRepository(localRepoAccessor.dir).getCurrentCommit();
                if(!newVersion.equals(version)) {
                    b = b.with(repo, repoManifest.builder().set(RepoManifest.VERSION, newVersion));
                }
            }

            return b.build();
        }
        GitLocalVcs vcs = new GitLocalVcs();
        Repository workspaceRepository = vcs.getRepository(workspaceRoot);
        Iterable<String> lines = vcs.getRepository(workspaceRoot).showFile(workspaceRepository.getUserSpecifiedCommit(arg), "qbt-manifest");
        return QbtManifestVersions.parse(ImmutableList.copyOf(lines));
    }

    private static String checkType(String type, boolean set, String value) {
        if(set) {
            if(type != null) {
                throw new IllegalArgumentException("Too many types specified!");
            }
            type = value;
        }
        return type;
    }

    private static final Map<String, String> DEFAULT_CONFIG;
    static {
        ImmutableMap.Builder<String, String> b = ImmutableMap.builder();

        b.put("git.log.edit.command", "git log --left-right \"$@\" $REPO_VERSION_LHS...$REPO_VERSION_RHS");
        b.put("git.log.edit.prefix", "true");
        b.put("git.log.add.command", "echo \"Added at $REPO_VERSION:\"; git log -1 $REPO_VERSION");
        b.put("git.log.add.prefix", "true");
        b.put("git.log.del.command", "echo \"Deleted at $REPO_VERSION:\"; git log -1 $REPO_VERSION");
        b.put("git.log.del.prefix", "true");

        b.put("git.diff.edit.command", "git diff --src-prefix=\"$REPO_NAME/$REPO_TIP/a/\" --dst-prefix=\"$REPO_NAME/$REPO_TIP/b/\" \"$@\" $REPO_VERSION_LHS $REPO_VERSION_RHS");
        b.put("git.diff.edit.prefix", "false");
        b.put("git.diff.add.command", "git diff --src-prefix=\"$REPO_NAME/$REPO_TIP/a/\" --dst-prefix=\"$REPO_NAME/$REPO_TIP/b/\" \"$@\" 4b825dc642cb6eb9a060e54bf8d69288fbee4904 $REPO_VERSION");
        b.put("git.diff.add.prefix", "false");
        b.put("git.diff.del.command", "git diff --src-prefix=\"$REPO_NAME/$REPO_TIP/a/\" --dst-prefix=\"$REPO_NAME/$REPO_TIP/b/\" \"$@\" $REPO_VERSION 4b825dc642cb6eb9a060e54bf8d69288fbee4904");
        b.put("git.diff.del.prefix", "false");

        b.put("git.logDiff.edit.command", "git log -p --src-prefix=\"$REPO_NAME/$REPO_TIP/a/\" --dst-prefix=\"$REPO_NAME/$REPO_TIP/b/\" --left-right \"$@\" $REPO_VERSION_LHS...$REPO_VERSION_RHS");
        b.put("git.logDiff.edit.prefix", "true");
        b.put("git.logDiff.add.command", "echo \"Added at $REPO_VERSION:\"; git log -1 $REPO_VERSION");
        b.put("git.logDiff.add.prefix", "true");
        b.put("git.logDiff.del.command", "echo \"Deleted at $REPO_VERSION:\"; git log -1 $REPO_VERSION");
        b.put("git.logDiff.del.prefix", "true");

        DEFAULT_CONFIG = b.build();
    }

    private static ImmutableMap<String, String> resolveConfig(Path workspaceRoot) {
        Map<String, String> b = Maps.newHashMap();
        b.putAll(DEFAULT_CONFIG);
        String prefix = "sdiff.";
        for(Map.Entry<String, String> e : new GitLocalVcs().getRepository(workspaceRoot).getAllConfig().entries()) {
            if(e.getKey().startsWith(prefix)) {
                b.put(e.getKey().substring(prefix.length()), e.getValue());
            }
        }
        return ImmutableMap.copyOf(b);
    }

    @Override
    public int run(final OptionsResults<? extends Options> options) throws Exception {
        final QbtConfig config = Options.config.getConfig(options);
        Path workspaceRoot = QbtUtils.findInMeta("", null);

        ImmutableList<String> manifests = options.get(Options.manifests);
        if(manifests.size() != 2) {
            throw new OptionsException("Exactly two manifests must be specified");
        }
        QbtManifest lhs = resolveManifest(workspaceRoot, config, manifests.get(0));
        QbtManifest rhs = resolveManifest(workspaceRoot, config, manifests.get(1));

        String type = options.get(Options.type);
        type = checkType(type, options.get(Options.log), "log");
        type = checkType(type, options.get(Options.diff), "diff");
        type = checkType(type, options.get(Options.logDiff), "logDiff");
        if(type == null) {
            type = "log";
        }

        return run(config, workspaceRoot, type, options, Options.commonOptions, lhs, rhs);
    }

    private static final Function<RepoManifest, VcsVersionDigest> REPO_VERSION_FUNCTION = (repoManifest) -> repoManifest.version;

    public static <O> int run(final QbtConfig config, Path workspaceRoot, final String type, final OptionsResults<? extends O> options, final CommonOptionsDelegate<O> commonsOptions, QbtManifest lhs, QbtManifest rhs) {
        final ImmutableMap<String, String> vcsConfig = resolveConfig(workspaceRoot);

        new MapDiffer<RepoTip, VcsVersionDigest>(Maps.transformValues(lhs.repos, REPO_VERSION_FUNCTION), Maps.transformValues(rhs.repos, REPO_VERSION_FUNCTION), RepoTip.TYPE.COMPARATOR) {
            @Override
            protected void edit(RepoTip repo, VcsVersionDigest lhs, VcsVersionDigest rhs) {
                run(repo, "edit", ImmutableMap.of("REPO_VERSION_LHS", lhs, "REPO_VERSION_RHS", rhs));
            }

            @Override
            protected void add(RepoTip repo, VcsVersionDigest value) {
                run(repo, "add", ImmutableMap.of("REPO_VERSION", value));
            }

            @Override
            protected void del(RepoTip repo, VcsVersionDigest value) {
                run(repo, "del", ImmutableMap.of("REPO_VERSION", value));
            }

            private void run(RepoTip repo, String deltaType, Map<String, VcsVersionDigest> versions) {
                if(options.get(commonsOptions.override)) {
                    LocalRepoAccessor localRepoAccessor = config.localRepoFinder.findLocalRepo(repo);
                    if(localRepoAccessor == null) {
                        return;
                    }
                    Path dir = localRepoAccessor.dir;
                    LocalVcs localVcs = localRepoAccessor.vcs;
                    run2(dir, localVcs, repo, deltaType, versions);
                }
                else {
                    try(QbtTempDir tempDir = new QbtTempDir()) {
                        run2(tempDir.path, null, repo, deltaType, versions);
                    }
                }
            }

            private void run2(Path dir, LocalVcs localVcs, final RepoTip repo, String deltaType, Map<String, VcsVersionDigest> versions) {
                for(VcsVersionDigest version : versions.values()) {
                    if(localVcs != null && localVcs.getRepository(dir).commitExists(version)) {
                        continue;
                    }
                    PinnedRepoAccessor pinnedAccessor = config.localPinsRepo.requirePin(repo, version);
                    LocalVcs localVcs2 = pinnedAccessor.getLocalVcs();
                    if(localVcs == null) {
                        localVcs = localVcs2;
                        localVcs.createWorkingRepo(dir);
                    }
                    else {
                        if(!localVcs.equals(localVcs2)) {
                            throw new RuntimeException("Mismatched local VCSs: " + localVcs + " / " + localVcs2);
                        }
                    }
                    pinnedAccessor.findCommit(dir);
                }
                if(localVcs == null) {
                    throw new IllegalStateException("Sdiff.run() called with no versions");
                }
                String configPrefix = localVcs.getName() + "." + type + "." + deltaType + ".";
                String command = vcsConfig.get(configPrefix + "command");
                if(command == null) {
                    return;
                }
                ImmutableList.Builder<String> commandBuilder = ImmutableList.builder();
                commandBuilder.add("sh", "-c");
                commandBuilder.add(command);
                commandBuilder.add("-");
                commandBuilder.addAll(options.get(commonsOptions.extraArgs));
                ProcessHelper p = ProcessHelper.of(dir, commandBuilder.build().toArray(new String[0]));
                p = p.apply(ProcessHelperUtils.STRIP_GIT_ENV);
                for(Map.Entry<String, VcsVersionDigest> e : versions.entrySet()) {
                    p = p.putEnv(e.getKey(), e.getValue().getRawDigest().toString());
                }
                p = p.putEnv("REPO_NAME", repo.name);
                p = p.putEnv("REPO_TIP", repo.tip);
                if("true".equals(vcsConfig.get(configPrefix + "prefix"))) {
                    p.run(ProcessHelperUtils.simplePrefixCallback(String.valueOf(repo)));
                }
                else {
                    p = p.inheritInput();
                    p = p.inheritOutput();
                    p = p.inheritError();
                    p.run().requireSuccess();
                }
            }
        }.diff();

        return 0;
    }
}
