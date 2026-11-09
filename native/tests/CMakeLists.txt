add_executable(native_module_contract
    module_contract.c
    ../modules/transform/transform.c)
target_include_directories(native_module_contract PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/../include"
    "${CMAKE_CURRENT_SOURCE_DIR}/../modules/transform")
veltis_enable_c_warnings(native_module_contract)
veltis_set_output_directory(native_module_contract "${VELTIS_NATIVE_OUTPUT_DIR}")
add_test(NAME native_module_contract COMMAND native_module_contract)

function(add_native_fixture name abi major minor mode)
    add_library(${name} SHARED fixtures/fixture.c)
    target_compile_definitions(${name} PRIVATE
        VELTIS_MODULE_EXPORTS
        VELTIS_FIXTURE_ABI=${abi}
        VELTIS_FIXTURE_MAJOR=${major}
        VELTIS_FIXTURE_MINOR=${minor}
        VELTIS_FIXTURE_MODE=${mode})
    target_include_directories(${name} PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/../include")
    set_target_properties(${name} PROPERTIES
        C_VISIBILITY_PRESET hidden
        POSITION_INDEPENDENT_CODE ON)
    if(WIN32)
        set_target_properties(${name} PROPERTIES PREFIX "")
    endif()
    veltis_enable_c_warnings(${name})
    veltis_set_output_directory(${name} "${VELTIS_NATIVE_OUTPUT_DIR}/test-fixtures")
endfunction()

add_native_fixture(veltis_native_fake_abi 999 1 0 0)
add_native_fixture(veltis_native_fake_version 1 2 0 0)
add_native_fixture(veltis_native_fake_init 1 1 0 1)
add_native_fixture(veltis_native_fake_transform 1 1 0 2)
add_native_fixture(veltis_native_fake_missing 1 1 0 3)

add_library(veltis_native_fake_minimal SHARED fixtures/minimal.c)
target_compile_definitions(veltis_native_fake_minimal PRIVATE VELTIS_MODULE_EXPORTS)
target_include_directories(veltis_native_fake_minimal PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/../include")
set_target_properties(veltis_native_fake_minimal PROPERTIES C_VISIBILITY_PRESET hidden)
if(WIN32)
    set_target_properties(veltis_native_fake_minimal PROPERTIES PREFIX "")
endif()
veltis_enable_c_warnings(veltis_native_fake_minimal)
veltis_set_output_directory(veltis_native_fake_minimal "${VELTIS_NATIVE_OUTPUT_DIR}/test-fixtures")
