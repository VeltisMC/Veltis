package org.veltismc.veltis.buildtools;

import org.veltismc.veltis.buildtools.context.BuildContext;

public interface BuildTool {

    String name();

    String version();

    BuildPhase phase();

    void execute(BuildContext context) throws Exception;

    enum BuildPhase {
        PREPARE,
        RESOLVE,
        DOWNLOAD,
        REMAP,
        DECOMPILE,
        PATCH,
        COMPILE,
        PACKAGE
    }
}


