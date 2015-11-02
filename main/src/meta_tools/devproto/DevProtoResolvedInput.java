package meta_tools.devproto;

import java.nio.file.Path;
import misc1.commons.Maybe;
import misc1.commons.resources.FreeScope;

public interface DevProtoResolvedInput {
    public void materialize(Maybe<FreeScope> scope, Path inputsDir);
}
