package meta_tools.pinproxy;

import misc1.commons.concurrent.ctree.ComputationTree;
import qbt.VcsVersionDigest;

public interface PinProxyRewrite {
    ComputationTree<VcsVersionDigest> localToRemote(VcsVersionDigest localCommit);
    ComputationTree<VcsVersionDigest> remoteToLocal(VcsVersionDigest remoteCommit);
}
