add_executable(native_tests native_tests.cpp)
find_package(Threads REQUIRED)
target_link_libraries(native_tests PRIVATE veltis_native Threads::Threads)
# The buffer predicates are header-only and tested directly, so the unit needs
# the library's private internal include directory. It is PRIVATE here as well:
# nothing outside this module may include src/cpp headers.
target_include_directories(native_tests PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/../src/cpp)
veltis_enable_warnings(native_tests)
veltis_configure_runtime(native_tests)

# Put the test executable beside the shared library. Windows only searches the
# executable's own directory for a DLL, and keeping the pair together is also
# what makes a manual `ctest` work without a PATH or LD_LIBRARY_PATH wrapper.
veltis_set_output_directory(native_tests "${VELTIS_NATIVE_OUTPUT_DIR}")

add_test(NAME veltis_native_tests COMMAND native_tests)

# Proves the artifact the host will actually load has the host's binary format
# and exactly the public exports. It loads the library through LoadLibrary/dlopen
# so it covers PE, ELF and Mach-O without objdump/readelf/dumpbin.
add_executable(native_export_check export_check.c)
target_link_libraries(native_export_check PRIVATE ${CMAKE_DL_LIBS})
veltis_enable_warnings(native_export_check)
veltis_configure_runtime(native_export_check C_ONLY)
veltis_set_output_directory(native_export_check "${VELTIS_NATIVE_OUTPUT_DIR}")
add_test(NAME veltis_native_exports
    COMMAND native_export_check $<TARGET_FILE:veltis_native>)

# The ABI contract, exercised from both languages against the same header. The C
# unit is the only consumer of veltis_native.h that is compiled as pure C, so a
# C++-only construct in the header fails here instead of in an operator's build.
# Both units pin every exported signature as a function-pointer type: a changed
# parameter, a dropped const or a different return type is a compile error, which
# is the one ABI test that can run before a library exists.
add_executable(native_abi_contract_c abi_contract_c.c)
target_link_libraries(native_abi_contract_c PRIVATE veltis_native)
veltis_enable_warnings(native_abi_contract_c)
veltis_configure_runtime(native_abi_contract_c C_ONLY)
veltis_set_output_directory(native_abi_contract_c "${VELTIS_NATIVE_OUTPUT_DIR}")
add_test(NAME veltis_native_abi_contract_c COMMAND native_abi_contract_c)

add_executable(native_abi_contract_cxx abi_contract_cxx.cpp)
target_link_libraries(native_abi_contract_cxx PRIVATE veltis_native)
veltis_enable_warnings(native_abi_contract_cxx)
veltis_configure_runtime(native_abi_contract_cxx)
veltis_set_output_directory(native_abi_contract_cxx "${VELTIS_NATIVE_OUTPUT_DIR}")
add_test(NAME veltis_native_abi_contract_cxx COMMAND native_abi_contract_cxx)

# Seeded, bounded fuzzing of the ABI argument surface. It is a normal CTest case
# with a small round count, so the lane always runs it, and the same binary takes
# VELTIS_FUZZ_ROUNDS/VELTIS_FUZZ_SEED for a long developer run. Under a sanitizer
# build this is the case that turns a wrong validation order into a report.
add_executable(native_fuzz_abi_inputs fuzz_abi_inputs.cpp)
target_link_libraries(native_fuzz_abi_inputs PRIVATE veltis_native)
veltis_enable_warnings(native_fuzz_abi_inputs)
veltis_configure_runtime(native_fuzz_abi_inputs)
veltis_set_output_directory(native_fuzz_abi_inputs "${VELTIS_NATIVE_OUTPUT_DIR}")
add_test(NAME veltis_native_fuzz_abi_inputs
    COMMAND native_fuzz_abi_inputs)
set_tests_properties(veltis_native_fuzz_abi_inputs PROPERTIES
    ENVIRONMENT "VELTIS_FUZZ_ROUNDS=4000"
    LABELS "fuzz")

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
    veltis_enable_warnings(${name})
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
veltis_enable_warnings(veltis_native_fake_minimal)
veltis_set_output_directory(veltis_native_fake_minimal "${VELTIS_NATIVE_OUTPUT_DIR}/test-fixtures")
