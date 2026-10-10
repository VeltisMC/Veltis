rootProject.name = "veltismc"

enableFeaturePreview("STABLE_CONFIGURATION_CACHE")

// Four modules, and each one is a boundary something actually depends on.
//
//   :veltis-api   the stable surface another module or an external integration
//                 compiles against. It is a boundary even while it holds no
//                 classes: an API that lives inside a module which also ships
//                 something else is not an API, it is a package.
//
//   :launcher     the jar an operator runs: bootstrap, argument handling, the
//                 startup clock, and the uber-jar packaging of the modules it
//                 ships.
//   :patch-engine Mojang acquisition, access widening, decompilation, the patch
//                 engine, the workspace layout and the runtime that builds and
//                 loads it. The Gradle pipeline's entry point lives here too —
//                 it is a front end on this package, not a module of its own.
//   :server       everything that runs *inside* Minecraft's classloader: the
//                 NMS entrypoint, the Veltis runtime framework and the world
//                 engine.
//
// Two former modules are gone because their boundaries were not boundaries.
// `build-tools` held one class whose callers all already depended on
// `patch-engine`; `world` and `runtime` were three jars stitched together at
// packaging time by a script that had to name all three in the right order.
include(
    ":veltis-api",
    ":launcher",
    ":patch-engine",
    ":server"
)
