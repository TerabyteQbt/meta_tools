package meta_tools.submanifest;

import com.google.common.base.Charsets;
import com.google.common.base.Functions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Ordering;
import java.nio.file.Path;
import java.util.Arrays;
import meta_tools.utils.HistoryRebuilder;
import misc1.commons.concurrent.ctree.ComputationTree;
import misc1.commons.options.OptionsFragment;
import misc1.commons.options.OptionsLibrary;
import misc1.commons.options.OptionsResults;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qbt.HelpTier;
import qbt.QbtCommand;
import qbt.QbtCommandName;
import qbt.QbtCommandOptions;
import qbt.QbtUtils;
import qbt.VcsTreeDigest;
import qbt.VcsVersionDigest;
import qbt.config.QbtConfig;
import qbt.manifest.current.QbtManifest;
import qbt.options.ConfigOptionsDelegate;
import qbt.options.ParallelismOptionsDelegate;
import qbt.tip.RepoTip;
import qbt.vcs.CommitData;
import qbt.vcs.Repository;
import qbt.vcs.TreeAccessor;
import qbt.vcs.VcsRegistry;

public class Submanifest extends QbtCommand<Submanifest.Options> {
    private static final Logger LOGGER = LoggerFactory.getLogger(Submanifest.class);

    @QbtCommandName("submanifest")
    public static interface Options extends QbtCommandOptions {
        public static final OptionsLibrary<Options> o = OptionsLibrary.of();
        public static final ConfigOptionsDelegate<Options> config = new ConfigOptionsDelegate<Options>();
        public static final OptionsFragment<Options, String> metaVcs = o.oneArg("metaVcs").transform(o.singleton("git")).helpDesc("VCS for meta");
        public static final ParallelismOptionsDelegate<Options> parallelism = new ParallelismOptionsDelegate<Options>();
        public static final OptionsFragment<Options, String> importedFile = o.oneArg("importedFile").transform(o.singleton("imported-repos")).helpDesc("File in root of tree to use to track imported repos.");
        public static final OptionsFragment<Options, ImmutableList<String>> base = o.oneArg("base").helpDesc("Treat this commit, and any of its ancestors as bases.");
        public static final OptionsFragment<Options, ImmutableList<String>> lifts = o.oneArg("lift").helpDesc("Lift this commit.");
        public static final OptionsFragment<Options, ImmutableList<String>> splits = o.oneArg("split").helpDesc("Split this commit.");
    }

    @Override
    public Class<Options> getOptionsClass() {
        return Options.class;
    }

    @Override
    public HelpTier getHelpTier() {
        return HelpTier.ARCANE;
    }

    @Override
    public String getDescription() {
        return null;
    }

    @Override
    public boolean isProgrammaticOutput() {
        return true;
    }

    @Override
    public int run(final OptionsResults<? extends Options> options) throws Exception {
        final QbtConfig config = Options.config.getConfig(options);
        String metaVcs = options.get(Options.metaVcs);

        Path metaDir = QbtUtils.findInMeta("", null);
        final Repository metaRepository = VcsRegistry.getLocalVcs(metaVcs).getRepository(metaDir);

        final String importedFile = options.get(Options.importedFile);

        final ImmutableList<VcsVersionDigest> bases;
        {
            ImmutableList.Builder<VcsVersionDigest> b = ImmutableList.builder();
            for(String base : options.get(Options.base)) {
                b.add(metaRepository.getUserSpecifiedCommit(base));
            }
            bases = b.build();
        }

        abstract class Side extends HistoryRebuilder {
            private final String sideName;

            public Side(String sideName) {
                super(metaRepository, bases, sideName);

                this.sideName = sideName;
            }
        }

        ComputationTree<?> liftTree = new Side("lift") {
            private VcsTreeDigest liftTree(VcsTreeDigest tree) {
                QbtManifest manifest = config.manifestParser.parse(ImmutableList.copyOf(metaRepository.showFile(tree, "qbt-manifest")));
                TreeAccessor treeAccessor = metaRepository.getTreeAccessor(tree);
                byte[] importedBytes = linesToBytes(Ordering.natural().immutableSortedCopy(Iterables.transform(manifest.repos.keySet(), Functions.toStringFunction())));
                treeAccessor = treeAccessor.replace(importedFile, importedBytes);
                return treeAccessor.getDigest();
            }

            @Override
            protected VcsVersionDigest mapBase(VcsVersionDigest base) {
                CommitData.Builder cd = metaRepository.getCommitData(base).builder();
                cd = cd.transform(CommitData.TREE, this::liftTree);
                cd = cd.set(CommitData.PARENTS, ImmutableList.of(base));
                cd = cd.set(CommitData.MESSAGE, "(submanifest import)");
                return metaRepository.createCommit(cd.build());
            }

            @Override
            protected VcsVersionDigest map(VcsVersionDigest commit, CommitData cd0, ImmutableList<VcsVersionDigest> parents) {
                CommitData.Builder cd = cd0.builder();
                cd = cd.transform(CommitData.TREE, this::liftTree);
                cd = cd.set(CommitData.PARENTS, parents);
                return metaRepository.createCommit(cd.build());
            }
        }.buildMany(options.get(Options.lifts));

        ComputationTree<?> splitTree = new Side("split") {
            private VcsTreeDigest splitTree(final VcsTreeDigest tree) {
                QbtManifest manifest = config.manifestParser.parse(ImmutableList.copyOf(metaRepository.showFile(tree, "qbt-manifest")));
                TreeAccessor treeAccessor = metaRepository.getTreeAccessor(tree);
                byte[] importedBytes = treeAccessor.get(importedFile).rightOrNull();
                ImmutableSet<RepoTip> keep = ImmutableSet.copyOf(Iterables.transform(Arrays.asList(new String(importedBytes, Charsets.UTF_8).split("\n")), RepoTip.TYPE::parseRequire));
                QbtManifest.Builder newManifestBuilder = manifest.builder();
                for(RepoTip repo : manifest.repos.keySet()) {
                    if(!keep.contains(repo)) {
                        newManifestBuilder = newManifestBuilder.without(repo);
                    }
                }
                treeAccessor = treeAccessor.remove(importedFile);
                treeAccessor = treeAccessor.replace("qbt-manifest", linesToBytes(config.manifestParser.deparse(newManifestBuilder.build())));
                return treeAccessor.getDigest();
            }

            @Override
            protected VcsVersionDigest mapBase(VcsVersionDigest base) {
                return base;
            }

            @Override
            protected VcsVersionDigest map(VcsVersionDigest next, CommitData cd, ImmutableList<VcsVersionDigest> parents) {
                if(parents.isEmpty()) {
                    throw new IllegalArgumentException("Root commit outside of bases!");
                }
                cd = cd.transform(CommitData.TREE, this::splitTree);

                return cleanUpAndCommit(metaRepository, cd);
            }
        }.buildMany(options.get(Options.splits));

        Options.parallelism.getResult(options, false).runComputationTree(ComputationTree.pair(liftTree, splitTree));
        return 0;
    }

    private static byte[] linesToBytes(Iterable<String> lines) {
        StringBuilder sb = new StringBuilder();
        for(String line : lines) {
            sb.append(line);
            sb.append('\n');
        }
        return sb.toString().getBytes(Charsets.UTF_8);
    }
}
