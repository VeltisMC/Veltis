add_executable(native_tests native_tests.cpp)
find_package(Threads REQUIRED)
target_link_libraries(native_tests PRIVATE veltis_native Threads::Threads)

# Put the test executable beside the shared library. Windows only searches the
# executable's own directory for a DLL, and keeping the pair together is also
# what makes a manual `ctest` work without a PATH or LD_LIBRARY_PATH wrapper.
veltis_set_output_directory(native_tests "${VELTIS_NATIVE_OUTPUT_DIR}")

add_test(NAME veltis_native_tests COMMAND native_tests)

# Incompatible libraries used by the Java integration tests. Each one exports
# real version symbols (or deliberately omits one), so the loader exercises a
# genuine dlopen/LoadLibrary path instead of a mock. Output goes to a
# test-fixtures subdirectory so nothing can confuse them with the real library.
function(add_native_fixture name source abi major minor patch mode)
    add_library(${name} SHARED ${source})
    target_compile_definitions(${name} PRIVATE
        VELTIS_NATIVE_EXPORTS
        VELTIS_FIXTURE_ABI=${abi}
        VELTIS_FIXTURE_MAJOR=${major}
        VELTIS_FIXTURE_MINOR=${minor}
        VELTIS_FIXTURE_PATCH=${patch}
        VELTIS_FIXTURE_MODE=${mode}
    )
    target_include_directories(${name} PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/../include
    )
    veltis_set_output_directory(${name} "${VELTIS_NATIVE_OUTPUT_DIR}/test-fixtures")
endfunction()

add_native_fixture(veltis_native_fake_abi fixtures/fixture.c 999 1 0 0 0)
add_native_fixture(veltis_native_fake_version fixtures/fixture.c 1 2 0 0 0)
add_native_fixture(veltis_native_fake_init fixtures/fixture.c 1 1 0 0 1)
add_native_fixture(veltis_native_fake_transform fixtures/fixture.c 1 1 0 0 2)
add_native_fixture(veltis_native_fake_missing fixtures/fixture.c 1 1 0 0 3)

add_library(veltis_native_fake_minimal SHARED fixtures/minimal.c)
target_compile_definitions(veltis_native_fake_minimal PRIVATE VELTIS_NATIVE_EXPORTS)
target_include_directories(veltis_native_fake_minimal PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/../include
)
veltis_set_output_directory(veltis_native_fake_minimal "${VELTIS_NATIVE_OUTPUT_DIR}/test-fixtures")
