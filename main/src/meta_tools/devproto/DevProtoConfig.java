package meta_tools.devproto;

import java.util.List;
import java.util.Map;

public final class DevProtoConfig {
    public final Map<String, List<DevProtoInput>> map;

    public DevProtoConfig(Map<String, List<DevProtoInput>> map) {
        this.map = map;
    }
}
