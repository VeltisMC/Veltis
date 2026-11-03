plugins {
    id("java-library")
}

description = "VeltisMC API - the stable surface other modules and external integrations compile against"

// Deliberately empty.
//
// The module exists so the public surface is its own boundary and so CI compiles
// it, rather than the API arriving as a package inside a module that also ships
// a server or a build tool. Declare dependencies here once the first class lands;
// nothing should depend on this module until then.
