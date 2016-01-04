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
