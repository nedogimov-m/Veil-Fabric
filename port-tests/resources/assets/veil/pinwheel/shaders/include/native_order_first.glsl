#define ORDER_FROM_INCLUDE 7
#define NATIVE_SCALE(value) ((value) * 2.0)
float nativeFirst() { return 0.25; }
#include <veil:native_order_second.glsl>
