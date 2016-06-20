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

import com.google.common.base.Function;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import misc1.commons.Either;
import misc1.commons.Maybe;
import misc1.commons.concurrent.ctree.ComputationTree;
import misc1.commons.resources.FreeScope;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.tuple.Pair;
import qbt.NormalDependencyType;
import qbt.QbtUtils;
import qbt.artifactcacher.ArtifactReference;
import qbt.build.BuildUtils;
import qbt.map.CumulativeVersionComputer;
import qbt.recursive.cvrpd.CvRecursivePackageData;
import qbt.recursive.utils.RecursiveDataUtils;
import qbt.tip.PackageTip;

public final class DevProtoInputs {
    private DevProtoInputs() {
        // nope
    }

    private abstract static class BaseInput<T> implements DevProtoInput {
        private final NormalDependencyType normalDependencyType;

        public BaseInput(NormalDependencyType normalDependencyType) {
            this.normalDependencyType = normalDependencyType;
        }

        @Override
        public ComputationTree<DevProtoResolvedInput> computationTree(CvRecursivePackageData<CumulativeVersionComputer.Result> r, final Stage1Callback cb) {
            Map<String, Pair<NormalDependencyType, CvRecursivePackageData<CumulativeVersionComputer.Result>>> filteredChildren = Maps.filterEntries(r.children, (input) -> input.getValue().getLeft() == normalDependencyType);
            Map<String, Pair<NormalDependencyType, ComputationTree<T>>> mappedChildren = RecursiveDataUtils.transformMap(filteredChildren, new Function<CvRecursivePackageData<CumulativeVersionComputer.Result>, ComputationTree<T>>() {
                @Override
                public ComputationTree<T> apply(CvRecursivePackageData<CumulativeVersionComputer.Result> input) {
                    return mapChild(cb, input);
                }
            });
            return RecursiveDataUtils.computationTreeMap(mappedChildren, (input) -> (scope, inputsDir) -> materialize1(scope, inputsDir, input));
        }

        protected abstract ComputationTree<T> mapChild(Stage1Callback cb, CvRecursivePackageData<CumulativeVersionComputer.Result> r);
        protected abstract void materialize1(Maybe<FreeScope> scope, Path inputsDir, Map<String, Pair<NormalDependencyType, T>> results);
    }

    private abstract static class BuildBaseInput extends BaseInput<CvRecursivePackageData<ArtifactReference>> {
        public BuildBaseInput(NormalDependencyType normalDependencyType) {
            super(normalDependencyType);
        }

        @Override
        protected ComputationTree<CvRecursivePackageData<ArtifactReference>> mapChild(Stage1Callback cb, CvRecursivePackageData<CumulativeVersionComputer.Result> r) {
            return cb.buildComputationTree(r);
        }
    }

    public static final DevProtoInput STRONG = new BuildBaseInput(NormalDependencyType.STRONG) {
        @Override
        protected void materialize1(Maybe<FreeScope> scope, Path inputsDir, Map<String, Pair<NormalDependencyType, CvRecursivePackageData<ArtifactReference>>> results) {
            BuildUtils.materializeStrongDependencyArtifacts(scope, inputsDir.resolve("strong"), results);
        }
    };

    public static final DevProtoInput RUNTIME_WEAK = new BuildBaseInput(NormalDependencyType.RUNTIME_WEAK) {
        @Override
        protected void materialize1(Maybe<FreeScope> scope, Path inputsDir, Map<String, Pair<NormalDependencyType, CvRecursivePackageData<ArtifactReference>>> results) {
            BuildUtils.materializeWeakArtifacts(scope, inputsDir.resolve("weak"), ImmutableSet.of(NormalDependencyType.RUNTIME_WEAK), results);
        }
    };

    public static final DevProtoInput BUILDTIME_WEAK = new BuildBaseInput(NormalDependencyType.BUILDTIME_WEAK) {
        @Override
        protected void materialize1(Maybe<FreeScope> scope, Path inputsDir, Map<String, Pair<NormalDependencyType, CvRecursivePackageData<ArtifactReference>>> results) {
            BuildUtils.materializeWeakArtifacts(scope, inputsDir.resolve("weak"), ImmutableSet.of(NormalDependencyType.BUILDTIME_WEAK), results);
        }
    };

    public static final DevProtoInput PROTO = new BaseInput<DevProtoResult>(NormalDependencyType.STRONG) {
        @Override
        protected ComputationTree<DevProtoResult> mapChild(Stage1Callback cb, CvRecursivePackageData<CumulativeVersionComputer.Result> r) {
            return cb.devProtoComputationTree(r);
        }

        @Override
        protected void materialize1(final Maybe<FreeScope> scope, Path inputsDir, Map<String, Pair<NormalDependencyType, DevProtoResult>> results) {
            Map<String, DevProtoResult> resultsFlat = collectStrongDeps(results);
            Path protoDir = inputsDir.resolve("proto");
            final Path fixedDir = protoDir.resolve("fixed");
            final Path overriddenDir = protoDir.resolve("overridden");
            QbtUtils.mkdirs(fixedDir);
            QbtUtils.mkdirs(overriddenDir);
            for(Map.Entry<String, DevProtoResult> e : resultsFlat.entrySet()) {
                final String packageName = e.getKey();
                e.getValue().result.visit(new Either.Visitor<ArtifactReference, ArtifactReference, ObjectUtils.Null>() {
                    @Override
                    public ObjectUtils.Null left(ArtifactReference protoResult) {
                        BuildUtils.materializeArtifact(scope, overriddenDir.resolve(packageName), protoResult);
                        return ObjectUtils.NULL;
                    }

                    @Override
                    public ObjectUtils.Null right(ArtifactReference buildResult) {
                        BuildUtils.materializeArtifact(scope, fixedDir.resolve(packageName), buildResult);
                        return ObjectUtils.NULL;
                    }
                });
            }
        }
    };

    public static DevProtoInput extra(String pkg) {
        return extra(PackageTip.TYPE.parseRequire(pkg));
    }

    public static DevProtoInput extra(final PackageTip pkg) {
        return (r, cb) -> {
            CvRecursivePackageData<CumulativeVersionComputer.Result> rExtra = cb.computeCumulativeVersion(pkg);
            return cb.buildComputationTree(rExtra).transform((input) -> (scope, inputsDir) -> BuildUtils.materializeRuntimeArtifacts(scope, inputsDir.resolve("extra").resolve(pkg.name), input));
        };
    }

    private static Map<String, DevProtoResult> collectStrongDeps(Map<String, Pair<NormalDependencyType, DevProtoResult>> r) {
        final Map<String, DevProtoResult> ret = Maps.newHashMap();
        final Set<DevProtoResult> already = Sets.newHashSet();
        class Collector {
            public void collect(String packageName, DevProtoResult r) {
                if(!already.add(r)) {
                    return;
                }

                DevProtoResult rOld = ret.put(packageName, r);
                if(rOld != null && rOld != r) {
                    throw new IllegalArgumentException("Collision at " + packageName);
                }

                for(Map.Entry<String, Pair<ObjectUtils.Null, DevProtoResult>> e : r.children.entrySet()) {
                    collect(e.getKey(), e.getValue().getRight());
                }
            }
        }
        final Collector c = new Collector();
        for(Map.Entry<String, Pair<NormalDependencyType, DevProtoResult>> e : r.entrySet()) {
            c.collect(e.getKey(), e.getValue().getRight());
        }
        return ImmutableMap.copyOf(ret);
    }
}
