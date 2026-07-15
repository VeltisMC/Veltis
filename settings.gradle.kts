rootProject.name = "veltismc"

enableFeaturePreview("STABLE_CONFIGURATION_CACHE")

include(
    ":launcher",
    ":build-tools",
    ":patch-engine",
    ":builder",
    ":server",
    ":runtime"
)
