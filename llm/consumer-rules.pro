# ModelSpec and friends are kotlinx.serialization @Serializable and are read back from JSON written
# by an earlier version of the app. R8 renaming their fields would not break compilation -- it would
# break every custom model a user has saved, at runtime, with no error until they open the catalogue.
-keepclassmembers class com.example.aiagent.llm.** {
    *** Companion;
}
-keepclasseswithmembers class com.example.aiagent.llm.** {
    kotlinx.serialization.KSerializer serializer(...);
}
