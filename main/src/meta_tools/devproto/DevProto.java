package meta_tools.devproto;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import groovy.lang.GroovyShell;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import misc1.commons.Either;
import misc1.commons.ExceptionUtils;
import misc1.commons.Maybe;
import misc1.commons.concurrent.ctree.ComputationTree;
import misc1.commons.options.NamedStringSingletonArgumentOptionsFragment;
import misc1.commons.options.OptionsFragment;
import misc1.commons.options.OptionsResults;
import misc1.commons.ph.ProcessHelper;
import misc1.commons.resources.FreeScope;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qbt.HelpTier;
import qbt.NormalDependencyType;
import qbt.PackageDirectories;
import qbt.PackageDirectory;
import qbt.QbtCommand;
import qbt.QbtCommandName;
import qbt.QbtCommandOptions;
import qbt.QbtTempDir;
import qbt.QbtUtils;
import qbt.artifactcacher.ArtifactReference;
import qbt.artifactcacher.ArtifactReferences;
import qbt.build.BuildData;
import qbt.build.PackageMapperHelper;
import qbt.build.PackageMapperHelperOptionsDelegate;
import qbt.config.QbtConfig;
import qbt.manifest.current.QbtManifest;
import qbt.map.CumulativeVersionComputer;
import qbt.map.CumulativeVersionComputerOptionsDelegate;
import qbt.map.CumulativeVersionComputerOptionsResult;
import qbt.options.ConfigOptionsDelegate;
import qbt.options.ManifestOptionsDelegate;
import qbt.options.PackageActionOptionsDelegate;
import qbt.recursive.cv.CumulativeVersion;
import qbt.recursive.cv.CumulativeVersionNodeData;
import qbt.recursive.cvrpd.CvRecursivePackageData;
import qbt.recursive.cvrpd.CvRecursivePackageDataComputationMapper;
import qbt.recursive.cvrpd.CvRecursivePackageDataMapper;
import qbt.tip.PackageTip;
import qbt.utils.ProcessHelperUtils;

public final class DevProto extends QbtCommand<DevProto.Options> {
    private static final Logger LOGGER = LoggerFactory.getLogger(DevProto.class);

    @QbtCommandName("devProto")
    public static interface Options extends QbtCommandOptions {
        public static final ConfigOptionsDelegate<Options> config = new ConfigOptionsDelegate<Options>();
        public static final ManifestOptionsDelegate<Options> manifest = new ManifestOptionsDelegate<Options>();
        public static final CumulativeVersionComputerOptionsDelegate<Options> cumulativeVersionComputerOptions = new CumulativeVersionComputerOptionsDelegate<Options>();
        public static final PackageMapperHelperOptionsDelegate<Options> packageMapperHelperOptions = new PackageMapperHelperOptionsDelegate<Options>();
        public static final PackageActionOptionsDelegate<Options> packages = new PackageActionOptionsDelegate<Options>(PackageActionOptionsDelegate.NoArgsBehaviour.EMPTY);
        public static final OptionsFragment<Options, ?, String> proto = new NamedStringSingletonArgumentOptionsFragment<Options>(ImmutableList.of("--proto"), Maybe.<String>not(), "Protocol to run");
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
        return "run a dev proto";
    }

    @Override
    public int run(OptionsResults<? extends Options> options) throws Exception {
        final QbtConfig config = Options.config.getConfig(options);
        final QbtManifest manifest = Options.manifest.getResult(options).parse();
        final Collection<PackageTip> packages = Options.packages.getPackages(config, manifest, options);
        final CumulativeVersionComputerOptionsResult cumulativeVersionComputerOptionsResult = Options.cumulativeVersionComputerOptions.getResults(options);
        final String proto = options.get(Options.proto);
        try(final FreeScope scopeGlobal = new FreeScope()) {
            PackageMapperHelper.run(config.artifactCacher, options, Options.packageMapperHelperOptions, new PackageMapperHelper.PackageMapperHelperCallback<ObjectUtils.Null>() {
                private ArtifactReference runDevProto(CvRecursivePackageData<CumulativeVersionComputer.Result> requireRepoResults, List<DevProtoResolvedInput> inputs) {
                    final CumulativeVersion v = requireRepoResults.v;
                    CumulativeVersionComputer.Result requireRepoResult = requireRepoResults.result.getRight();
                    try(FreeScope scopeRunDevProto = new FreeScope()) {
                        try(PackageDirectory packageDir = PackageDirectories.forCvcResult(requireRepoResult)) {
                            LOGGER.info("Running proto for " + v.prettyDigest() + " in " + packageDir.getDir() + "...");
                            try(QbtTempDir tempDir = new QbtTempDir()) {
                                Path inputsDir = tempDir.resolve("inputs");
                                QbtUtils.mkdirs(inputsDir);
                                Path outputsDir = tempDir.resolve("outputs");
                                QbtUtils.mkdirs(outputsDir);

                                for(DevProtoResolvedInput input : inputs) {
                                    input.materialize(Maybe.of(scopeRunDevProto), inputsDir);
                                }

                                ProcessHelper p = ProcessHelper.of(packageDir.getDir(), new String[] {"./.qbt-dev-proto/" + proto + ".exec"});
                                p = p.putEnv("INPUT_DEV_PROTO_DIR", inputsDir.toAbsolutePath().toString());
                                p = p.putEnv("OUTPUT_DEV_PROTO_DIR", outputsDir.toAbsolutePath().toString());
                                p = p.putEnv("PACKAGE_DIR", packageDir.getDir().toAbsolutePath().toString());
                                p = p.putEnv("PACKAGE_NAME", v.getPackageName());
                                p = p.putEnv("PACKAGE_CUMULATIVE_VERSION", v.getDigest().getRawDigest().toString());
                                for(String key : p.get(ProcessHelper.ENV).keys()) {
                                    if(key.startsWith("QBT_ENV_")) {
                                        p = p.removeEnv(key);
                                    }
                                }
                                for(Map.Entry<String, String> e : v.result.qbtEnv.entrySet()) {
                                    p = p.putEnv("QBT_ENV_" + e.getKey(), e.getValue());
                                }
                                p.run(ProcessHelperUtils.simplePrefixCallback(v.prettyDigest()));

                                return ArtifactReferences.copyDirectory(scopeGlobal, outputsDir);
                            }
                        }
                    }
                }

                @Override
                public ComputationTree<ObjectUtils.Null> run(final PackageMapperHelper.PackageMapperHelperCallbackCallback cb) {
                    final CvRecursivePackageDataComputationMapper<CumulativeVersionComputer.Result, CvRecursivePackageData<CumulativeVersionComputer.Result>, CvRecursivePackageData<ArtifactReference>> buildComputationMapper = new CvRecursivePackageDataComputationMapper<CumulativeVersionComputer.Result, CvRecursivePackageData<CumulativeVersionComputer.Result>, CvRecursivePackageData<ArtifactReference>>() {
                        @Override
                        protected CvRecursivePackageData<ArtifactReference> map(CvRecursivePackageData<CumulativeVersionComputer.Result> requireRepoResults, Map<String, Pair<NormalDependencyType, CvRecursivePackageData<ArtifactReference>>> dependencyResults) {
                            return new CvRecursivePackageData<ArtifactReference>(requireRepoResults.v, cb.runBuild(new BuildData(requireRepoResults, dependencyResults)), dependencyResults);
                        }
                    };
                    final LoadingCache<CumulativeVersionComputer.Result, Maybe<ImmutableList<DevProtoInput>>> readInputs = CacheBuilder.newBuilder().build(new CacheLoader<CumulativeVersionComputer.Result, Maybe<ImmutableList<DevProtoInput>>>() {
                        @Override
                        public Maybe<ImmutableList<DevProtoInput>> load(CumulativeVersionComputer.Result requireRepoResult) throws Exception {
                            if(!requireRepoResult.commonRepoAccessor.isOverride()) {
                                return Maybe.<ImmutableList<DevProtoInput>>not();
                            }

                            try(PackageDirectory packageDir = PackageDirectories.forCvcResult(requireRepoResult)) {
                                Path script = packageDir.getDir().resolve(".qbt-dev-proto/" + proto + ".inputs");
                                if(!Files.isRegularFile(script)) {
                                    return Maybe.<ImmutableList<DevProtoInput>>not();
                                }

                                GroovyShell shell = new GroovyShell();
                                List<DevProtoInput> inputs;
                                try {
                                    inputs = (List<DevProtoInput>)shell.evaluate(script.toFile());
                                }
                                catch(Exception e) {
                                    throw ExceptionUtils.commute(e);
                                }

                                return Maybe.of(ImmutableList.copyOf(inputs));
                            }
                        }
                    });
                    final CumulativeVersionComputer<?> cumulativeVersionComputer = new CumulativeVersionComputer<Pair<CumulativeVersionNodeData, PackageTip>>(config, manifest) {
                        @Override
                        protected Pair<CumulativeVersionNodeData, PackageTip> canonicalizationKey(CumulativeVersionComputer.Result result) {
                            return Pair.of(result.cumulativeVersionNodeData, result.packageTip);
                        }

                        @Override
                        protected Map<String, String> getQbtEnv() {
                            return cumulativeVersionComputerOptionsResult.qbtEnv;
                        }
                    };
                    CvRecursivePackageDataMapper<CumulativeVersionComputer.Result, ComputationTree<DevProtoResult>> devProtoComputationMapper = new CvRecursivePackageDataMapper<CumulativeVersionComputer.Result, ComputationTree<DevProtoResult>>() {
                        @Override
                        protected ComputationTree<DevProtoResult> map(final CvRecursivePackageData<CumulativeVersionComputer.Result> r) {
                            CumulativeVersionComputer.Result result = r.result.getRight();
                            ImmutableList<DevProtoInput> inputs = readInputs.getUnchecked(result).get(null);

                            // for us alone
                            ComputationTree<Either<ArtifactReference, ArtifactReference>> singleResultComputation;
                            if(inputs == null) {
                                // Package does not participate, build and use that as fixed
                                singleResultComputation = buildComputationMapper.transform(r).transform((input) -> Either.<ArtifactReference, ArtifactReference>right(input.result.getRight()));
                            }
                            else {
                                // Package does participate, do whatever the
                                // inputs ask for
                                ImmutableList.Builder<ComputationTree<DevProtoResolvedInput>> inputsComputationTreesBuilder = ImmutableList.builder();
                                for(DevProtoInput input : inputs) {
                                    inputsComputationTreesBuilder.add(input.computationTree(r, new DevProtoInput.Stage1Callback() {
                                        @Override
                                        public CvRecursivePackageData<CumulativeVersionComputer.Result> computeCumulativeVersion(PackageTip pkg) {
                                            return cumulativeVersionComputer.compute(pkg);
                                        }

                                        @Override
                                        public ComputationTree<CvRecursivePackageData<ArtifactReference>> buildComputationTree(CvRecursivePackageData<CumulativeVersionComputer.Result> r) {
                                            return buildComputationMapper.transform(r);
                                        }

                                        @Override
                                        public ComputationTree<DevProtoResult> devProtoComputationTree(CvRecursivePackageData<CumulativeVersionComputer.Result> r) {
                                            return transform(r);
                                        }
                                    }));
                                }
                                singleResultComputation = ComputationTree.list(inputsComputationTreesBuilder.build()).transform((input) -> Either.<ArtifactReference, ArtifactReference>left(runDevProto(r, input)));
                            }

                            // now what is up with our deps?
                            Map<String, CvRecursivePackageData<CumulativeVersionComputer.Result>> strongDepRs = collectStrongDeps(r);
                            ImmutableMap.Builder<String, ComputationTree<DevProtoResult>> strongDepResults = ImmutableMap.builder();
                            for(Map.Entry<String, CvRecursivePackageData<CumulativeVersionComputer.Result>> e : strongDepRs.entrySet()) {
                                String strongDepName = e.getKey();
                                CvRecursivePackageData<CumulativeVersionComputer.Result> strongDepR = e.getValue();
                                ComputationTree<DevProtoResult> strongDepResult = transform(strongDepR);
                                strongDepResults.put(strongDepName, strongDepResult);
                            }
                            ComputationTree<ImmutableMap<String, DevProtoResult>> strongDepResultsCombined = ComputationTree.map(strongDepResults.build());

                            return ComputationTree.pair(singleResultComputation, strongDepResultsCombined).transform((input) -> new DevProtoResult(input.getLeft(), input.getRight()));
                        }
                    };

                    ImmutableList.Builder<ComputationTree<ObjectUtils.Null>> computationTreesBuilder = ImmutableList.builder();
                    for(final PackageTip packageTip : packages) {
                        CvRecursivePackageData<CumulativeVersionComputer.Result> r = cumulativeVersionComputer.compute(packageTip);
                        if(!readInputs.getUnchecked(r.result.getRight()).isPresent()) {
                            LOGGER.info("Skipping generation for non-proto package " + packageTip);
                            continue;
                        }
                        computationTreesBuilder.add(devProtoComputationMapper.transform(r).transform((input) -> {
                            LOGGER.info("Completed proto for " + packageTip);
                            return ObjectUtils.NULL;
                        }));
                    }
                    return ComputationTree.list(computationTreesBuilder.build()).ignore();
                }
            });
        }

        return 0;
    }

    private static Map<String, CvRecursivePackageData<CumulativeVersionComputer.Result>> collectStrongDeps(CvRecursivePackageData<CumulativeVersionComputer.Result> r) {
        final Map<String, CvRecursivePackageData<CumulativeVersionComputer.Result>> ret = Maps.newHashMap();
        final Set<CvRecursivePackageData<CumulativeVersionComputer.Result>> already = Sets.newHashSet();
        class Collector {
            public void collect(Map<String, Pair<NormalDependencyType, CvRecursivePackageData<CumulativeVersionComputer.Result>>> children) {
                for(Map.Entry<String, Pair<NormalDependencyType, CvRecursivePackageData<CumulativeVersionComputer.Result>>> e : children.entrySet()) {
                    if(e.getValue().getLeft() == NormalDependencyType.STRONG) {
                        collect(e.getValue().getRight());
                    }
                }
            }

            public void collect(CvRecursivePackageData<CumulativeVersionComputer.Result> r) {
                if(!already.add(r)) {
                    return;
                }
                String packageName = r.result.getRight().packageTip.name;

                CvRecursivePackageData<CumulativeVersionComputer.Result> rOld = ret.put(packageName, r);
                if(rOld != null && rOld != r) {
                    throw new IllegalArgumentException("Collision at " + packageName);
                }

                collect(r.children);
            }
        }
        final Collector c = new Collector();
        c.collect(r.children);
        return ImmutableMap.copyOf(ret);
    }
}
