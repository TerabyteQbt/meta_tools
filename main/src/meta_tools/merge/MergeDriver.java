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
package meta_tools.merge;

import com.google.common.collect.ImmutableList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import misc1.commons.options.OptionsFragment;
import misc1.commons.options.OptionsLibrary;
import misc1.commons.options.OptionsResults;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qbt.HelpTier;
import qbt.QbtCommand;
import qbt.QbtCommandName;
import qbt.QbtCommandOptions;
import qbt.mains.MergeManifests;

public class MergeDriver extends QbtCommand<MergeDriver.Options> {
    private static final Logger LOGGER = LoggerFactory.getLogger(MergeDriver.class);

    @QbtCommandName("mergeDriver")
    public static interface Options extends QbtCommandOptions {
        public static final OptionsLibrary<Options> o = OptionsLibrary.of();
        public static final OptionsFragment<Options, MergeManifests.StrategyEnum> pullStrategy = o.oneArg("pullStrategy").transform(o.singleton(null)).transform(o.parseEnum(MergeManifests.StrategyEnum.class)).helpDesc("Strategy to use during a pull (merge or rebase)");
        public static final OptionsFragment<Options, ImmutableList<String>> manifests = o.unparsed(false).transform(o.minMax(3, 3)).helpDesc("Manifests to merge.");
    }

    @Override
    public Class<Options> getOptionsClass() {
        return Options.class;
    }

    @Override
    public HelpTier getHelpTier() {
        return HelpTier.PLUMBING;
    }

    @Override
    public String getDescription() {
        return "act as git low-level merge driver for qbt-manifest";
    }

    @Override
    public int run(final OptionsResults<? extends Options> options) throws Exception {
        ImmutableList<String> manifests = options.get(Options.manifests);
        MergeManifests.StrategyEnum pullStrategy = options.get(Options.pullStrategy);

        String reflogAction = System.getenv("GIT_REFLOG_ACTION");
        if(reflogAction == null) {
            reflogAction = "";
        }
        OptionsResults.Builder<MergeManifests.Options> options2 = OptionsResults.builder(MergeManifests.Options.class);
        options2 = options2.addResult(MergeManifests.Options.lhs.file, manifests.get(0));
        options2 = options2.addResult(MergeManifests.Options.mhs.file, manifests.get(1));
        options2 = options2.addResult(MergeManifests.Options.rhs.file, manifests.get(2));
        options2 = options2.addResult(MergeManifests.Options.out.file, manifests.get(0));
        Matcher mergeMatcher = Pattern.compile("^merge( (.*))?$").matcher(reflogAction);
        if(mergeMatcher.matches()) {
            String rhsName = mergeMatcher.group(1);
            if(rhsName == null) {
                rhsName = "RHS";
            }
            LOGGER.debug("Reflog action [" + reflogAction + "] -> merge " + rhsName);
            options2 = options2.addResult(MergeManifests.Options.strategy, MergeManifests.StrategyEnum.merge);
            options2 = options2.addResult(MergeManifests.Options.lhsName, "HEAD");
            options2 = options2.addResult(MergeManifests.Options.mhsName, "merged common ancestors");
            options2 = options2.addResult(MergeManifests.Options.rhsName, rhsName);
            return new MergeManifests().run(options2.build());
        }
        Matcher cherryPickMatcher = Pattern.compile("^cherry-pick( (.*))?$").matcher(reflogAction);
        if(cherryPickMatcher.matches()) {
            String rhsName = cherryPickMatcher.group(1);
            if(rhsName == null) {
                rhsName = "RHS";
            }
            LOGGER.debug("Reflog action [" + reflogAction + "] -> cherry-pick " + rhsName);
            options2 = options2.addResult(MergeManifests.Options.strategy, MergeManifests.StrategyEnum.rebase);
            options2 = options2.addResult(MergeManifests.Options.lhsName, "HEAD");
            options2 = options2.addResult(MergeManifests.Options.mhsName, rhsName + "^");
            options2 = options2.addResult(MergeManifests.Options.rhsName, rhsName);
            return new MergeManifests().run(options2.build());
        }
        Matcher rebaseMatcher = Pattern.compile("^rebase( .*)?$").matcher(reflogAction);
        if(rebaseMatcher.matches()) {
            LOGGER.debug("Reflog action [" + reflogAction + "] -> rebase");
            options2 = options2.addResult(MergeManifests.Options.strategy, MergeManifests.StrategyEnum.rebase);
            return new MergeManifests().run(options2.build());
        }
        Matcher pullMatcher = Pattern.compile("^pull( .*)?$").matcher(reflogAction);
        if(pullMatcher.matches() && pullStrategy != null) {
            LOGGER.debug("Reflog action [" + reflogAction + "] -> pull (" + pullStrategy + ")");
            options2 = options2.addResult(MergeManifests.Options.strategy, pullStrategy);
            return new MergeManifests().run(options2.build());
        }
        LOGGER.debug("Reflog action [" + reflogAction + "] -> unknown, only allowing trivial merges");
        options2 = options2.addResult(MergeManifests.Options.shellAction.command, ImmutableList.of("/bin/false"));
        return new MergeManifests().run(options2.build());
    }
}
