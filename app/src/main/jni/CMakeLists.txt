cmake_minimum_required(VERSION 3.4.1)
project(nsp_bridge C)

add_library(nsp_bridge SHARED nsp_bridge.c)

target_link_libraries(nsp_bridge c log)

# Hide irrelevant symbols, only keep JNI entry points
set_target_properties(nsp_bridge PROPERTIES
    C_STANDARD 11
    C_STANDARD_REQUIRED ON
)
