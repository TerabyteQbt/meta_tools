package meta_tools.pinproxy;

import com.google.common.collect.ImmutableMap;
import misc1.commons.concurrent.ctree.ComputationTree;
import org.apache.commons.lang3.tuple.Pair;
import qbt.VcsVersionDigest;

public interface PinProxyRewrite {
    ComputationTree<Pair<VcsVersionDigest, VcsVersionDigest>> localToRemote(Pair<VcsVersionDigest, VcsVersionDigest> localCommits);
    ComputationTree<ImmutableMap<VcsVersionDigest, VcsVersionDigest>> remoteToLocal(Iterable<VcsVersionDigest> remoteCommits);
}
