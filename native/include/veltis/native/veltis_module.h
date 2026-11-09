#ifndef VELTIS_NATIVE_MODULE_H
#define VELTIS_NATIVE_MODULE_H

#include <stdint.h>

#if defined(_WIN32)
#  if defined(VELTIS_MODULE_EXPORTS)
#    define VELTIS_MODULE_API __declspec(dllexport)
#  else
#    define VELTIS_MODULE_API __declspec(dllimport)
#  endif
#  define VELTIS_MODULE_CALL __cdecl
#elif defined(__GNUC__) || defined(__clang__)
#  define VELTIS_MODULE_API __attribute__((visibility("default")))
#  define VELTIS_MODULE_CALL
#else
#  define VELTIS_MODULE_API
#  define VELTIS_MODULE_CALL
#endif

#define VELTIS_MODULE_ABI_VERSION 1
#define VELTIS_MODULE_CAPABILITY_BATCH_I32 (UINT32_C(1) << 0)
#define VELTIS_MODULE_CAPABILITY_SELF_TEST (UINT32_C(1) << 1)

#if defined(__STDC_VERSION__) && __STDC_VERSION__ >= 201112L
_Static_assert(sizeof(uint32_t) == 4, "native modules require 32-bit uint32_t");
_Static_assert(sizeof(int32_t) == 4, "native modules require 32-bit int32_t");
#endif

#endif
