package meta_tools.pinproxy;

import com.google.common.collect.ImmutableMap;
import misc1.commons.concurrent.ctree.ComputationTree;
import qbt.VcsVersionDigest;

public interface PinProxyRewrite {
    ComputationTree<ImmutableMap<VcsVersionDigest, VcsVersionDigest>> localToRemote(Iterable<VcsVersionDigest> localCommits);
    ComputationTree<ImmutableMap<VcsVersionDigest, VcsVersionDigest>> remoteToLocal(Iterable<VcsVersionDigest> remoteCommits);
}
