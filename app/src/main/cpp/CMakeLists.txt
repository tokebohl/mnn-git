cmake_minimum_required(VERSION 3.22.1)
project("mnn_llm_jni" LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -ffast-math -march=armv8.2-a+fp16+dotprod -fvisibility=hidden")

set(MNN_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/mnn_dist)
set(MNN_LIB_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI})

add_library(mnn_llm_jni SHARED mnn_llm_jni.cpp)

find_library(log-lib log)
find_library(android-lib android)

if(EXISTS "${MNN_LIB_DIR}/libllm.so" AND EXISTS "${MNN_LIB_DIR}/libMNN.so")
    message(STATUS "MNN Native LLM biblioteker fundet - aktiverer fuld hardware-inferens")
    target_compile_definitions(mnn_llm_jni PRIVATE HAS_MNN_LIB=1)

    include_directories(
        ${MNN_ROOT}/include
        ${MNN_ROOT}/include/MNN
        ${MNN_ROOT}/transformers/llm/engine/include
    )

    add_library(mnn_core SHARED IMPORTED)
    set_target_properties(mnn_core PROPERTIES IMPORTED_LOCATION ${MNN_LIB_DIR}/libMNN.so)

    add_library(mnn_llm SHARED IMPORTED)
    set_target_properties(mnn_llm PROPERTIES IMPORTED_LOCATION ${MNN_LIB_DIR}/libllm.so)

    if(EXISTS "${MNN_LIB_DIR}/libMNN_Express.so")
        add_library(mnn_express SHARED IMPORTED)
        set_target_properties(mnn_express PROPERTIES IMPORTED_LOCATION ${MNN_LIB_DIR}/libMNN_Express.so)
        target_link_libraries(mnn_llm_jni mnn_llm mnn_express mnn_core ${log-lib} ${android-lib})
    else()
        target_link_libraries(mnn_llm_jni mnn_llm mnn_core ${log-lib} ${android-lib})
    endif()
else()
    message(WARNING "libllm.so ikke fundet i jniLibs/${ANDROID_ABI} - kompilerer uden HAS_MNN_LIB")
    target_link_libraries(mnn_llm_jni ${log-lib} ${android-lib})
endif()
