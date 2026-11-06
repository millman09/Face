# Preserve native entry points used by the TensorFlow Lite runtime.
-keepclasseswithmembers,includedescriptorclasses class * {
    native <methods>;
}

# Keep runtime metadata used by ML libraries and Kotlin reflection.
-keepattributes Signature,RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,AnnotationDefault
