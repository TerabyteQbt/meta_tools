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

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import java.util.Map;
import misc1.commons.Either;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.tuple.Pair;
import qbt.artifactcacher.ArtifactReference;
import qbt.recursive.rd.RecursiveData;

public final class DevProtoResult extends RecursiveData<Either<ArtifactReference, ArtifactReference>, String, ObjectUtils.Null, DevProtoResult> {
    public DevProtoResult(Either<ArtifactReference, ArtifactReference> result, Map<String, DevProtoResult> children) {
        super(result, ImmutableMap.copyOf(Maps.transformValues(children, (input) -> Pair.of(ObjectUtils.NULL, input))));
    }
}
