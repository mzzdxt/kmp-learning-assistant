# Release stack traces are useful only with the mapping from the exact same build.
# Preserve source/line metadata for R8 retrace while still allowing optimization.
-keepattributes SourceFile,LineNumberTable

# PocketLab currently has no reflection-based application models. Add the narrowest
# possible keep rule here only when a release smoke test proves one is required.
