package meta_tools.sdiff;

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
import misc1.commons.options.OptionsException;
import misc1.commons.options.OptionsFragment;
import misc1.commons.options.OptionsResults;
import misc1.commons.options.UnparsedOptionsFragment;
import org.apache.commons.lang3.tuple.Pair;
import qbt.HelpTier;
import qbt.PackageTip;
import qbt.QbtCommand;
import qbt.QbtCommandName;
import qbt.QbtCommandOptions;
import qbt.QbtManifest;
import qbt.QbtTempDir;
import qbt.RepoManifest;
import qbt.VcsVersionDigest;
import qbt.config.QbtConfig;
import qbt.diffmanifests.MapDiffer;
import qbt.options.ConfigOptionsDelegate;
import qbt.repo.LocalRepoAccessor;
import qbt.repo.PinnedRepoAccessor;
import qbt.utils.ProcessHelper;
import qbt.vcs.LocalVcs;
import qbt.vcs.Repository;
import qbt.vcs.git.GitLocalVcs;

public class Sdiff extends QbtCommand<Sdiff.Options> {
    @QbtCommandName("sdiff")
    public static interface Options extends QbtCommandOptions {
        public static final ConfigOptionsDelegate<Options> config = new ConfigOptionsDelegate<Options>();
        public static final OptionsFragment<Options, ?, Boolean> override = new NamedBooleanFlagOptionsFragment<Options>(ImmutableList.of("--override", "-o"), "Run commands in overrides");
        public static final OptionsFragment<Options, ?, String> type = new NamedStringSingletonArgumentOptionsFragment<Options>(ImmutableList.of("--type"), Maybe.<String>of(null), "Type of diff to show");
        public static final OptionsFragment<Options, ?, Boolean> log = new NamedBooleanFlagOptionsFragment<Options>(ImmutableList.of("--log"), "Show a log");
        public static final OptionsFragment<Options, ?, Boolean> diff = new NamedBooleanFlagOptionsFragment<Options>(ImmutableList.of("--diff"), "Show a diff");
        public static final OptionsFragment<Options, ?, Boolean> logDiff = new NamedBooleanFlagOptionsFragment<Options>(ImmutableList.of("--log-diff"), "Show a log diff");
        public static final OptionsFragment<Options, ?, ImmutableList<String>> manifests = new UnparsedOptionsFragment<Options>("\"Manifests\" to diff.  Give a commitlike, \".\" for the working tree manifest, and \"SAT\" for working tree manifest overridden with HEAD from satellites", false, 2, 2);
        public static final OptionsFragment<Options, ?, ImmutableList<String>> extraArgs = new NamedStringListArgumentOptionsFragment<Options>(ImmutableList.of("--extra-arg"), "Extra argument to subcommands (available as positional paramters $1, etc.  in shell)");
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
            return QbtManifest.parse(workspaceRoot.resolve("qbt-manifest"));
        }
        if(arg.equals("SAT")) {
            QbtManifest base = QbtManifest.parse(workspaceRoot.resolve("qbt-manifest"));

            QbtManifest.Builder b = base.builder();
            for(Map.Entry<PackageTip, RepoManifest> e : base.repos.entrySet()) {
                PackageTip repo = e.getKey();
                RepoManifest repoManifest = e.getValue();
                VcsVersionDigest version = repoManifest.version;
                LocalRepoAccessor localRepoAccessor = config.localRepoFinder.findLocalRepo(repo);
                if(localRepoAccessor == null) {
                    continue;
                }
                VcsVersionDigest newVersion = localRepoAccessor.vcs.getRepository(localRepoAccessor.dir).getCurrentCommit();
                if(!newVersion.equals(version)) {
                    repoManifest = repoManifest.builder().withVersion(newVersion).build();
                    b = b.with(repo, repoManifest);
                }
            }

            return b.build();
        }
        GitLocalVcs vcs = new GitLocalVcs();
        Repository workspaceRepository = vcs.getRepository(workspaceRoot);
        Iterable<String> lines = vcs.getRepository(workspaceRoot).showFile(workspaceRepository.getUserSpecifiedCommit(arg), "qbt-manifest");
        return QbtManifest.parse(arg + ":qbt-manifest", lines);
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
        Pair<Path, QbtConfig> configPair = Options.config.getPair(options);
        Path configFile = configPair.getLeft();
        final QbtConfig config = configPair.getRight();

        ImmutableList<String> manifests = options.get(Options.manifests);
        if(manifests.size() != 2) {
            throw new OptionsException("Exactly two manifests must be specified");
        }
        QbtManifest lhs = resolveManifest(configFile.getParent(), config, manifests.get(0));
        QbtManifest rhs = resolveManifest(configFile.getParent(), config, manifests.get(1));
        final ImmutableMap<String, String> vcsConfig = resolveConfig(configFile.getParent());

        String type = options.get(Options.type);
        type = checkType(type, options.get(Options.log), "log");
        type = checkType(type, options.get(Options.diff), "diff");
        type = checkType(type, options.get(Options.logDiff), "logDiff");
        if(type == null) {
            type = "log";
        }
        final String typeFinal = type;

        Function<RepoManifest, VcsVersionDigest> repoVersionFunction = new Function<RepoManifest, VcsVersionDigest>() {
            @Override
            public VcsVersionDigest apply(RepoManifest repoManifest) {
                return repoManifest.version;
            }
        };

        new MapDiffer<PackageTip, VcsVersionDigest>(Maps.transformValues(lhs.repos, repoVersionFunction), Maps.transformValues(rhs.repos, repoVersionFunction), PackageTip.COMPARATOR) {
            @Override
            protected void edit(PackageTip repo, VcsVersionDigest lhs, VcsVersionDigest rhs) {
                run(repo, "edit", ImmutableMap.of("REPO_VERSION_LHS", lhs, "REPO_VERSION_RHS", rhs));
            }

            @Override
            protected void add(PackageTip repo, VcsVersionDigest value) {
                run(repo, "add", ImmutableMap.of("REPO_VERSION", value));
            }

            @Override
            protected void del(PackageTip repo, VcsVersionDigest value) {
                run(repo, "del", ImmutableMap.of("REPO_VERSION", value));
            }

            private void run(PackageTip repo, String deltaType, Map<String, VcsVersionDigest> versions) {
                if(options.get(Options.override)) {
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

            private void run2(Path dir, LocalVcs localVcs, final PackageTip repo, String deltaType, Map<String, VcsVersionDigest> versions) {
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
                String configPrefix = localVcs.getName() + "." + typeFinal + "." + deltaType + ".";
                String command = vcsConfig.get(configPrefix + "command");
                if(command == null) {
                    return;
                }
                ImmutableList.Builder<String> commandBuilder = ImmutableList.builder();
                commandBuilder.add("sh", "-c");
                commandBuilder.add(command);
                commandBuilder.add("-");
                commandBuilder.addAll(options.get(Options.extraArgs));
                ProcessHelper p = new ProcessHelper(dir, commandBuilder.build().toArray(new String[0]));
                for(Map.Entry<String, VcsVersionDigest> e : versions.entrySet()) {
                    p = p.putEnv(e.getKey(), e.getValue().getRawDigest().toString());
                }
                p = p.putEnv("REPO_NAME", repo.pkg);
                p = p.putEnv("REPO_TIP", repo.tip);
                if("true".equals(vcsConfig.get(configPrefix + "prefix"))) {
                    p = p.combineError();
                    p.completeLinesCallback(new Function<String, Void>() {
                        @Override
                        public Void apply(String line) {
                            System.out.println("[" + repo + "] " + line);
                            return null;
                        }
                    });
                }
                else {
                    p = p.inheritInput();
                    p = p.inheritOutput();
                    p = p.inheritError();
                    p.completeVoid();
                }
            }
        }.diff();

        return 0;
    }
}
