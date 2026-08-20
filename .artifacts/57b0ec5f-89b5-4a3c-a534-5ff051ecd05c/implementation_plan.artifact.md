# Fix AI Analysis Retirement Error

The app is showing an error in the Smart Analysis section because the `gemini-1.5-flash` model has been retired by Google as of September 24, 2025. The app needs to be updated to use a newer, supported model like `gemini-2.5-flash`.

## Proposed Changes

### AI Service Migration

#### [MODIFY] [GeminiHelper.kt](file:///C:/Users/Willyam/StudioProjects/Nexur/app/src/main/java/com/naxor/app/util/GeminiHelper.kt)
- Update the model name from `gemini-1.5-flash` to `gemini-2.5-flash`.
- (Optional but recommended) Update to the latest Firebase AI SDK to ensure full compatibility with Gemini 2.5 features.

### Dependency Updates

#### [MODIFY] [libs.versions.toml](file:///C:/Users/Willyam/StudioProjects/Nexur/gradle/libs.versions.toml)
- Update `firebaseBom` to `34.18.0`.

#### [MODIFY] [build.gradle.kts (app)](file:///C:/Users/Willyam/StudioProjects/Nexur/app/build.gradle.kts)
- Update `firebase-vertexai` to use the version provided by the BoM or migrate to the new `firebase-ai` library.

## Verification Plan

### Manual Verification
1. Open the **Rendimiento** screen.
2. Tap on **Actualizar análisis** in the AI Analysis card.
3. Verify that the analysis is generated successfully without the retirement error.
