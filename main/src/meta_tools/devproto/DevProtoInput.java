package meta_tools.devproto;

import misc1.commons.concurrent.ctree.ComputationTree;
import qbt.artifactcacher.ArtifactReference;
import qbt.map.CumulativeVersionComputer;
import qbt.recursive.cvrpd.CvRecursivePackageData;
import qbt.tip.PackageTip;

public interface DevProtoInput {
    public ComputationTree<DevProtoResolvedInput> computationTree(CvRecursivePackageData<CumulativeVersionComputer.Result> r, Stage1Callback cb);

    public interface Stage1Callback {
        public CvRecursivePackageData<CumulativeVersionComputer.Result> computeCumulativeVersion(PackageTip packageTip);
        public ComputationTree<CvRecursivePackageData<ArtifactReference>> buildComputationTree(CvRecursivePackageData<CumulativeVersionComputer.Result> r);
        public ComputationTree<DevProtoResult> devProtoComputationTree(CvRecursivePackageData<CumulativeVersionComputer.Result> r);
    }
}
