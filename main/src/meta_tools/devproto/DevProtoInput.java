//   Copyright 2016 Keith Amling
//
//   Licensed under the Apache License, Version 2.0 (the "License");
//   you may not use this file except in compliance with the License.
//   You may obtain a copy of the License at
//
//       http://www.apache.org/licenses/LICENSE-2.0
//
//   Unless required by applicable law or agreed to in writing, software
//   distributed under the License is distributed on an "AS IS" BASIS,
//   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//   See the License for the specific language governing permissions and
//   limitations under the License.
//
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
