# Forgery release rules — conservative.
# Consumer rules Hilt/Room/Retrofit/OkHttp/Coil/Navigation применяются автоматически,
# ниже только страховка для наших контрактов, создаваемых рефлексией.

-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-keepattributes SourceFile,LineNumberTable

# Сериализуемые контракты: navigation routes + сетевые/очередь модели
-keep class com.forgery.app.core.model.** { *; }
-keep class com.forgery.app.feature.**.api.** { *; }
-keepclassmembers class com.forgery.app.core.model.** { *; }
-keepclassmembers class com.forgery.app.feature.**.api.** { *; }

# Room — сущности/DAO/БД
-keep class com.forgery.app.core.database.** { *; }

# WorkManager/Hilt workers — создаются рефлексией
-keep class com.forgery.app.core.data.GenerationWorker { *; }
-keep class com.forgery.app.core.data.QueueWatchdogWorker { *; }
-keep class * extends androidx.work.Worker { *; }
-keep class * extends androidx.work.CoroutineWorker { *; }
-keep class * extends androidx.work.ListenableWorker { *; }

# Hilt/Dagger — кодогенерация
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.lifecycle.HiltViewModel { *; }

# kotlinx-serialization — generic signatures
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class ** { @kotlinx.serialization.Serializable *; }

# Retrofit service contracts — R8 full mode keeps the generic rules, this is
# belt and braces so release can never lose Forge/Llm endpoints to shrinking.
-keep interface com.forgery.app.core.network.ForgeService { *; }
-keep interface com.forgery.app.core.network.LlmService { *; }
-keep class com.forgery.app.core.network.ForgeApiFactory { *; }
-keep class com.forgery.app.core.network.LlmApiFactory { *; }
-keep class com.jakewharton.retrofit2.converter.kotlinx.serialization.** { *; }
