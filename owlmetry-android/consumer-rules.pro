# Consumer ProGuard/R8 rules shipped to apps that depend on owlmetry-android.
# Keep the public API surface so consumers' R8 doesn't strip or rename it.
-keep public class com.owlmetry.android.Owl { public *; }
-keep public class com.owlmetry.android.OwlConfiguration { *; }
-keep public class com.owlmetry.android.** { public *; }
