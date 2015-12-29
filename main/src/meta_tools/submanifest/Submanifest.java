package meta_tools.submanifest;

import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import misc1.commons.Maybe;
import misc1.commons.concurrent.WorkPool;
import misc1.commons.concurrent.ctree.ComputationTree;
import misc1.commons.concurrent.ctree.ComputationTreeComputer;
import misc1.commons.options.NamedStringListArgumentOptionsFragment;
import misc1.commons.options.NamedStringSingletonArgumentOptionsFragment;
import misc1.commons.options.OptionsFragment;
import misc1.commons.options.OptionsResults;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.tuple.Pair;
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
import qbt.manifest.QbtManifest;
import qbt.options.ConfigOptionsDelegate;
import qbt.options.ParallelismOptionsDelegate;
import qbt.tip.RepoTip;
import qbt.vcs.CommitData;
import qbt.vcs.CommitDataUtils;
import qbt.vcs.Repository;
import qbt.vcs.TreeAccessor;
import qbt.vcs.VcsRegistry;

public class Submanifest extends QbtCommand<Submanifest.Options> {
    private static final Logger LOGGER = LoggerFactory.getLogger(Submanifest.class);

    @QbtCommandName("submanifest")
    public static interface Options extends QbtCommandOptions {
        public static final ConfigOptionsDelegate<Options> config = new ConfigOptionsDelegate<Options>();
        public static final OptionsFragment<Options, ?, String> metaVcs = new NamedStringSingletonArgumentOptionsFragment<Options>(ImmutableList.of("--metaVcs"), Maybe.of("git"), "VCS for meta");
        public static final ParallelismOptionsDelegate<Options> parallelism = new ParallelismOptionsDelegate<Options>();
        public static final OptionsFragment<Options, ?, String> importedFile = new NamedStringSingletonArgumentOptionsFragment<Options>(ImmutableList.of("--importedFile"), Maybe.of("imported-repos"), "File in root of tree to use to track imported repos.");
        public static final OptionsFragment<Options, ?, ImmutableList<String>> base = new NamedStringListArgumentOptionsFragment<Options>(ImmutableList.of("--base"), "Treat this commit, and any of its ancestors as bases.");
        public static final OptionsFragment<Options, ?, ImmutableList<String>> lifts = new NamedStringListArgumentOptionsFragment<Options>(ImmutableList.of("--lift"), "Lift this commit.");
        public static final OptionsFragment<Options, ?, ImmutableList<String>> splits = new NamedStringListArgumentOptionsFragment<Options>(ImmutableList.of("--split"), "Split this commit.");
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

        abstract class Side {
            public ComputationTree<?> build(final String sideName, OptionsFragment<Options, ?, ImmutableList<String>> commitsOption) {
                ImmutableList<Pair<String, VcsVersionDigest>> commitPairs;
                ImmutableList<VcsVersionDigest> commits;
                {
                    ImmutableList.Builder<Pair<String, VcsVersionDigest>> commitPairsBuilder = ImmutableList.builder();
                    ImmutableList.Builder<VcsVersionDigest> commitsBuilder = ImmutableList.builder();
                    for(String commitString : options.get(commitsOption)) {
                        VcsVersionDigest commit = metaRepository.getUserSpecifiedCommit(commitString);
                        commitPairsBuilder.add(Pair.of(commitString, commit));
                        commitsBuilder.add(commit);
                    }
                    commitPairs = commitPairsBuilder.build();
                    commits = commitsBuilder.build();
                }
                Map<VcsVersionDigest, CommitData> revWalk = metaRepository.revWalk(bases, commits);
                ImmutableList<Pair<VcsVersionDigest, CommitData>> buildOrder = ImmutableList.copyOf(CommitDataUtils.revWalkFlatten(revWalk, commits)).reverse();

                LoadingCache<VcsVersionDigest, ComputationTree<VcsVersionDigest>> baseComputationTrees = CacheBuilder.newBuilder().build(new CacheLoader<VcsVersionDigest, ComputationTree<VcsVersionDigest>>() {
                    @Override
                    public ComputationTree<VcsVersionDigest> load(VcsVersionDigest base) {
                        LOGGER.debug("Processing " + sideName + " of [base] " + base + "...");
                        return ComputationTree.constant(base).transform(mapBaseFunction);
                    }
                });

                Map<VcsVersionDigest, ComputationTree<VcsVersionDigest>> computationTrees = Maps.newHashMap();

                for(Pair<VcsVersionDigest, CommitData> e : buildOrder) {
                    final VcsVersionDigest next = e.getKey();
                    final CommitData cd = e.getValue();

                    ImmutableList.Builder<ComputationTree<VcsVersionDigest>> parentComputationTreesBuilder = ImmutableList.builder();
                    for(VcsVersionDigest parent : cd.get(CommitData.PARENTS)) {
                        if(revWalk.containsKey(parent)) {
                            parentComputationTreesBuilder.add(computationTrees.get(parent));
                        }
                        else {
                            parentComputationTreesBuilder.add(baseComputationTrees.getUnchecked(parent));
                        }
                    }

                    ComputationTree<VcsVersionDigest> nextTree = ComputationTree.list(parentComputationTreesBuilder.build()).transform(new Function<ImmutableList<VcsVersionDigest>, VcsVersionDigest>() {
                        @Override
                        public VcsVersionDigest apply(ImmutableList<VcsVersionDigest> parentResults) {
                            LOGGER.debug("Processing " + sideName + " of " + next + "...");
                            return map(next, cd, parentResults);
                        }
                    });

                    computationTrees.put(next, nextTree);
                }

                ImmutableList.Builder<ComputationTree<ObjectUtils.Null>> b = ImmutableList.builder();
                for(Pair<String, VcsVersionDigest> commitPair : commitPairs) {
                    final String commitString = commitPair.getLeft();
                    final VcsVersionDigest commit = commitPair.getRight();
                    ComputationTree<VcsVersionDigest> computationTree = computationTrees.get(commit);
                    if(computationTree == null) {
                        computationTree = baseComputationTrees.getUnchecked(commit);
                    }
                    b.add(computationTree.transform(new Function<VcsVersionDigest, ObjectUtils.Null>() {
                        @Override
                        public ObjectUtils.Null apply(VcsVersionDigest result) {
                            System.out.println(sideName + " " + commitString + " (" + commit.getRawDigest() + ") -> " + result.getRawDigest());
                            return ObjectUtils.NULL;
                        }
                    }));
                }
                return ComputationTree.list(b.build());
            }

            protected abstract VcsVersionDigest mapBase(VcsVersionDigest base);
            private final Function<VcsVersionDigest, VcsVersionDigest> mapBaseFunction = new Function<VcsVersionDigest, VcsVersionDigest>() {
                @Override
                public VcsVersionDigest apply(VcsVersionDigest input) {
                    return mapBase(input);
                }
            };

            protected abstract VcsVersionDigest map(VcsVersionDigest next, CommitData cd, ImmutableList<VcsVersionDigest> parents);
        }

        ComputationTree<?> liftTree = new Side() {
            private VcsTreeDigest liftTree(VcsTreeDigest tree) {
                QbtManifest manifest = QbtManifest.parse(tree.getRawDigest() + ":qbt-manifest", metaRepository.showFile(tree, "qbt-manifest"));
                TreeAccessor treeAccessor = metaRepository.getTreeAccessor(tree);
                byte[] importedBytes = linesToBytes(Ordering.natural().immutableSortedCopy(Iterables.transform(manifest.repos.keySet(), Functions.toStringFunction())));
                treeAccessor = treeAccessor.replace(importedFile, importedBytes);
                return treeAccessor.getDigest();
            }

            @Override
            protected VcsVersionDigest mapBase(VcsVersionDigest base) {
                CommitData cd = metaRepository.getCommitData(base);
                cd = cd.set(CommitData.TREE, liftTree(cd.get(CommitData.TREE)));
                cd = cd.set(CommitData.PARENTS, ImmutableList.of(base));
                cd = cd.set(CommitData.MESSAGE, "(submanifest import)");
                return metaRepository.createCommit(cd);
            }

            @Override
            protected VcsVersionDigest map(VcsVersionDigest commit, CommitData cd0, ImmutableList<VcsVersionDigest> parents) {
                CommitData cd = cd0;
                cd = cd.set(CommitData.TREE, liftTree(cd.get(CommitData.TREE)));
                cd = cd.set(CommitData.PARENTS, parents);
                return metaRepository.createCommit(cd);
            }
        }.build("lift", Options.lifts);

        ComputationTree<?> splitTree = new Side() {
            private VcsTreeDigest splitTree(VcsTreeDigest tree) {
                QbtManifest manifest = QbtManifest.parse(tree.getRawDigest() + ":qbt-manifest", metaRepository.showFile(tree, "qbt-manifest"));
                TreeAccessor treeAccessor = metaRepository.getTreeAccessor(tree);
                byte[] importedBytes = treeAccessor.get(importedFile).rightOrNull();
                QbtManifest.Builder newManifestBuilder = QbtManifest.TYPE.builder();
                for(String repoString : new String(importedBytes, Charsets.UTF_8).split("\n")) {
                    RepoTip repo = RepoTip.TYPE.parseRequire(repoString);
                    newManifestBuilder = newManifestBuilder.with(repo, manifest.repos.get(repo).builder());
                }
                treeAccessor = treeAccessor.remove(importedFile);
                treeAccessor = treeAccessor.replace("qbt-manifest", linesToBytes(newManifestBuilder.build().deparse()));
                return treeAccessor.getDigest();
            }

            @Override
            protected VcsVersionDigest mapBase(VcsVersionDigest base) {
                return base;
            }

            @Override
            protected VcsVersionDigest map(VcsVersionDigest next, CommitData cd, ImmutableList<VcsVersionDigest> parents) {
                List<VcsVersionDigest> keptParents = Lists.newArrayList();
                for(VcsVersionDigest parent : parents) {
                    boolean keep = true;
                    for(VcsVersionDigest priorParent : keptParents) {
                        if(metaRepository.isAncestorOf(parent, priorParent)) {
                            // parent is covered by a parent on its left, drop it
                            keep = false;
                            break;
                        }
                    }
                    if(!keep) {
                        continue;
                    }
                    keptParents.add(parent);
                }
                if(keptParents.isEmpty()) {
                    throw new IllegalArgumentException("Root commit outside of bases!");
                }
                VcsTreeDigest selfTree = splitTree(cd.get(CommitData.TREE));
                if(keptParents.size() == 1) {
                    VcsVersionDigest parent = keptParents.get(0);
                    VcsTreeDigest parentTree = metaRepository.getSubtree(parent, "");
                    if(parentTree.equals(selfTree)) {
                        // only one parent and the same tree, drop ourselves
                        return parent;
                    }
                }
                cd = cd.set(CommitData.TREE, selfTree);
                cd = cd.set(CommitData.PARENTS, ImmutableList.copyOf(keptParents));
                return metaRepository.createCommit(cd);
            }
        }.build("split", Options.splits);

        try(WorkPool workPool = Options.parallelism.getResult(options, false).createWorkPool()) {
            ComputationTreeComputer ctc = new ComputationTreeComputer() {
                @Override
                protected void submit(Runnable r) {
                    workPool.execute(r);
                }
            };

            ctc.await(ComputationTree.pair(liftTree, splitTree)).getCommute();

            return 0;
        }
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
