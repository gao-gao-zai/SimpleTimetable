# Persistent JSON uses Gson reflection. Keep the storage schema and enum names
# stable across optimized releases, including Kotlin's generic field signatures.
-keepattributes Signature,*Annotation*
-keep class io.github.gaogaoza.simpletimetable.data.Subject { *; }
-keep class io.github.gaogaoza.simpletimetable.data.TimeSlot { *; }
-keep class io.github.gaogaoza.simpletimetable.data.TimeLayout { *; }
-keep class io.github.gaogaoza.simpletimetable.data.ClassPlan { *; }
-keep class io.github.gaogaoza.simpletimetable.data.PlanGroup { *; }
-keep class io.github.gaogaoza.simpletimetable.data.LessonPatch { *; }
-keep class io.github.gaogaoza.simpletimetable.data.Temporary { *; }
-keep class io.github.gaogaoza.simpletimetable.data.Profile { *; }
-keep class io.github.gaogaoza.simpletimetable.data.Settings { *; }
-keep class io.github.gaogaoza.simpletimetable.data.AppData { *; }
-keep enum io.github.gaogaoza.simpletimetable.data.SlotType { *; }
-keep enum io.github.gaogaoza.simpletimetable.data.TempType { *; }
