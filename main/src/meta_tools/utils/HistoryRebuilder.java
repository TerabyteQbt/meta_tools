package meta_tools.utils;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import misc1.commons.concurrent.ctree.ComputationTree;
import org.apache.commons.lang3.ObjectUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qbt.VcsVersionDigest;
import qbt.vcs.CommitData;
import qbt.vcs.Repository;

public abstract class HistoryRebuilder {
    private static final Logger LOGGER = LoggerFactory.getLogger(HistoryRebuilder.class);

    private final Repository metaRepository;
    private final ImmutableSet<VcsVersionDigest> bases;
    private final String label;

    public HistoryRebuilder(Repository metaRepository, Iterable<VcsVersionDigest> bases, String label) {
        this.metaRepository = metaRepository;
        this.bases = ImmutableSet.copyOf(bases);
        this.label = label;
    }

    public ComputationTree<?> buildMany(Iterable<String> commitStrings) {
        ImmutableList.Builder<ComputationTree<ObjectUtils.Null>> b = ImmutableList.builder();
        for(String commitString : commitStrings) {
            VcsVersionDigest commit = metaRepository.getUserSpecifiedCommit(commitString);
            b.add(build(commit).transform((result) -> {
                System.out.println(label + " " + commitString + " (" + commit.getRawDigest() + ") -> " + result.getRawDigest());
                return ObjectUtils.NULL;
            }));
        }
        return ComputationTree.list(b.build());
    }

    public ComputationTree<VcsVersionDigest> build(VcsVersionDigest commit) {
        return computationTrees.getUnchecked(commit);
    }

    private final LoadingCache<VcsVersionDigest, ComputationTree<VcsVersionDigest>> computationTrees = CacheBuilder.newBuilder().build(new CacheLoader<VcsVersionDigest, ComputationTree<VcsVersionDigest>>() {
        @Override
        public ComputationTree<VcsVersionDigest> load(VcsVersionDigest commit) {
            return buildUncached(commit);
        }
    });

    private ComputationTree<VcsVersionDigest> buildUncached(VcsVersionDigest commit) {
        if(bases.contains(commit)) {
            return ComputationTree.ofSupplier(() -> {
                LOGGER.debug("Processing " + label + " of [base] " + commit + "...");
                return mapBase(commit);
            });
        }

        CommitData cd = metaRepository.getCommitData(commit);

        ImmutableList.Builder<ComputationTree<VcsVersionDigest>> parentComputationTreesBuilder = ImmutableList.builder();
        for(VcsVersionDigest parent : cd.get(CommitData.PARENTS)) {
            parentComputationTreesBuilder.add(build(parent));
        }

        return ComputationTree.list(parentComputationTreesBuilder.build()).transform((parentResults) -> {
            LOGGER.debug("Processing " + label + " of " + commit + "...");
            return map(commit, cd, parentResults);
        });
    }

    protected abstract VcsVersionDigest mapBase(VcsVersionDigest base);
    protected abstract VcsVersionDigest map(VcsVersionDigest next, CommitData cd, ImmutableList<VcsVersionDigest> parents);
}
