package meta_tools.utils;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import java.util.Map;
import misc1.commons.concurrent.ctree.ComputationTree;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qbt.VcsVersionDigest;
import qbt.vcs.CommitData;
import qbt.vcs.CommitDataUtils;
import qbt.vcs.Repository;

public abstract class HistoryRebuilder {
    private static final Logger LOGGER = LoggerFactory.getLogger(HistoryRebuilder.class);

    public ComputationTree<?> build(Repository metaRepository, ImmutableList<VcsVersionDigest> bases, String label, ImmutableList<String> commitStrings) {
        ImmutableList<Pair<String, VcsVersionDigest>> commitPairs;
        ImmutableList<VcsVersionDigest> commits;
        {
            ImmutableList.Builder<Pair<String, VcsVersionDigest>> commitPairsBuilder = ImmutableList.builder();
            ImmutableList.Builder<VcsVersionDigest> commitsBuilder = ImmutableList.builder();
            for(String commitString : commitStrings) {
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
                LOGGER.debug("Processing " + label + " of [base] " + base + "...");
                return ComputationTree.constant(base).transform(HistoryRebuilder.this::mapBase);
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

            ComputationTree<VcsVersionDigest> nextTree = ComputationTree.list(parentComputationTreesBuilder.build()).transform((parentResults) -> {
                LOGGER.debug("Processing " + label + " of " + next + "...");
                return map(next, cd, parentResults);
            });

            computationTrees.put(next, nextTree);
        }

        return ComputationTree.list(Iterables.transform(commitPairs, (commitPair) -> {
            final String commitString = commitPair.getLeft();
            final VcsVersionDigest commit = commitPair.getRight();
            ComputationTree<VcsVersionDigest> computationTree = computationTrees.get(commit);
            if(computationTree == null) {
                computationTree = baseComputationTrees.getUnchecked(commit);
            }
            return computationTree.transform((result) -> {
                onResult(label, commitString, commit, result);
                return ObjectUtils.NULL;
            });
        }));
    }

    protected abstract VcsVersionDigest mapBase(VcsVersionDigest base);
    protected abstract VcsVersionDigest map(VcsVersionDigest next, CommitData cd, ImmutableList<VcsVersionDigest> parents);
    protected abstract void onResult(String label, String commitString, VcsVersionDigest commit, VcsVersionDigest result);
}
