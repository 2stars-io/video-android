# Rules applied to consumers of the SDK at app shrink time.
# Keep public API surface so reflection-based libraries work.
-keep class io.twostars.sdk.** { *; }
