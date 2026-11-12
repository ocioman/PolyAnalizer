# PolyAnalizer

PolyAnalizer is an IntelliJ Platform plugin for inspecting Java type hierarchies and explaining method dispatch. It combines static PSI analysis with observations from a suspended Java debugger, without modifying the analyzed project.

The **PolyAnalizer** tool window contains three views:

- **Hierarchy** — recursive supertypes, project subtypes, and bounded wildcard usages for a Java class or interface.
- **Compile time** — the signature selected for a method call, alternative candidates, argument conversions, specificity comparisons, and the inheritance path to the selected declaration.
- **Runtime** — the actual receiver and implementation observed in the selected debugger frame, the lookup path, and, when source is available, the static call reconstructed from the caller frame.

## Requirements

### Build and test

- JDK 21. The Java toolchain and compilation target are both set to Java 21.
- The included Gradle wrapper; a separate Gradle installation is not required.
- Network access on the first build so Gradle can download the IntelliJ Platform, JetBrains Runtime, and other dependencies.

### IDE

- IntelliJ Platform build **243 or later**; the project targets and verifies against **IntelliJ IDEA Community 2024.3**. No upper IDE build bound is declared.
- An IDE with the Java plugin and XDebugger support. The plugin declares dependencies on `com.intellij.modules.platform`, `com.intellij.modules.xdebugger`, and `com.intellij.java`.
- A JDK configured for the Java project being inspected. The included manual demo is set up for Java 21.

## Build and test

Run commands from the repository root. On Windows, use `gradlew.bat` instead of `./gradlew`.

```bash
# Run the JUnit and IntelliJ Platform tests
./gradlew test

# Assemble the installable plugin ZIP in build/distributions/
./gradlew buildPlugin

# Run the plugin verification task against the configured IDE
./gradlew verifyPlugin

# Optional: launch a development IDE with PolyAnalizer installed
./gradlew runIde
```

The build uses Gradle 9.9.1, IntelliJ Platform Gradle Plugin 2.20.1, and JUnit 4.13.2. Tests cover hierarchy and wildcard analysis, static and runtime dispatch analysis, action targeting, and the three-view UI. Debugger tests construct snapshots directly; manual debugger capture can be exercised with `runIde` and the demo described below.

## Install a private build from the ZIP

This installation method does not require publishing PolyAnalizer to JetBrains Marketplace.

1. Run `./gradlew buildPlugin`.
2. Locate the generated `.zip` file in `build/distributions/`. Its file name includes the Gradle project name and plugin version.
3. In the IDE where PolyAnalizer will be used, open **Settings/Preferences | Plugins**.
4. Open the gear menu and choose **Install Plugin from Disk...**.
5. Select the ZIP itself, without extracting it, confirm the installation, and restart the IDE if prompted.
6. Open **View | Tool Windows | PolyAnalizer**, or use the **PolyAnalizer** button on the right tool-window stripe.

To update a private installation, build the new ZIP and install it in the same way. The plugin ID is currently `io.github.ocioman.hierarchy-tree`.

## Using the views

All three actions are in the Java editor's context menu. An action opens the PolyAnalizer tool window, selects its corresponding tab, and replaces that tab's previous result. Double-click a source-backed node to navigate to its class, method, argument, or wildcard reference; nodes without a valid navigation target do nothing.

### Hierarchy

1. Open a Java class or interface and place the caret anywhere inside its declaration or body.
2. Right-click and choose **Analyze Hierarchy**.
3. Inspect the three sections under the selected type:
   - **Supertypes** shows direct and transitive classes and interfaces, including whether each relation is `extends` or `implements`. Unresolved supertypes are shown as warnings.
   - **Subtypes** recursively shows classes and interfaces found in the **project scope**.
   - **Wildcard** finds project references using the selected type as the bound of `? extends ...` or `? super ...`. Each usage includes its source location and an **Allowed types** section based on the bound and its subtypes or supertypes.

If no wildcard with the selected bound is found, the view reports that explicitly. Hierarchy searches stop recursive cycles and support IDE cancellation.

### Compile time: explain static dispatch

1. Place the caret inside a Java method-call expression, such as `receiver.speak()` or `choose("text")`.
2. Right-click and choose **Explain Static Dispatch**.
3. Start with **Selected signature** to see the method chosen by the IntelliJ Java resolver, its declaring class, and the path from the static receiver type to that declaration.
4. Expand **Candidates** to compare the available signatures. The selected candidate is identified explicitly; every candidate reports applicability and, where relevant, varargs. Argument nodes show actual and formal types, generic substitution, assignment conversion, and boxing or unboxing when detected.
5. For the selected candidate, expand the comparisons with other applicable candidates to see parameter-specificity details. Unresolved and ambiguous calls are presented as warnings instead of a selected signature.
6. Check **Runtime dispatch** to see whether ordinary virtual dispatch can affect the implementation eventually executed. Static, private, and final methods, and `super` calls, are identified separately; other instance calls require the actual receiver type, which can be observed through the Runtime view.

This view explains compile-time selection. It does not predict a concrete runtime override for a virtual call without a debugger snapshot.

### Runtime: capture dynamic dispatch from the debugger

The Runtime action captures the **currently selected Java stack frame**. It does not capture whichever call happens to be under the editor caret.

1. Start the application with **Debug**, not **Run**, and set a breakpoint inside the method implementation whose dispatch you want to inspect.
2. When execution stops, select the corresponding Java frame in the debugger's **Frames** view. Keep the session suspended.
3. With focus in the Java editor, right-click and choose **Capture Dynamic Dispatch**. The action is available only when the current debug session is suspended and its current frame is a Java frame.
4. Inspect the **Runtime** tab:
   - **Actual receiver** is the runtime class of `this`. For a static frame, where no `this` exists, the declaring class is used and the absence of a receiver is reported.
   - **Observed implementation** maps the frame's declaring class, JVM method name, and JVM signature back to a PSI method.
   - **Call site in caller frame** appears when the caller has a source position and a compatible call can be found on its source line. It contains the same static analysis available in the Compile time view.
   - **Lookup path** walks from the dynamic class to the observed implementation, marking overrides, types without an override, and default interface implementations.
   - **Dispatch rule** distinguishes ordinary overriding from static, private, final, constructor, and `super` cases.
5. Leave the session suspended until capture and analysis finish. Resume or stop invalidates the capture; select a suspended Java frame and invoke the action again for a new observation.

Runtime capture is a snapshot of one selected frame, not a continuous execution trace. Full lookup requires the runtime class and observed method to be available in PSI. If either cannot be mapped, or if the caller has no usable source position, the view retains the information that is available and adds a warning instead of inventing a call site or lookup path.

## Manual demo

The local `manual-demo/` directory contains a small Java project and a `Demo` application run configuration. Open it in the development IDE launched by `./gradlew runIde`, or in another IDE with the private ZIP installed. Configure a Java 21 project SDK; if necessary, adjust the run configuration's JRE setting because the supplied configuration references a local macOS Temurin 21 installation.

The demo is not a Gradle module and is separate from the automated test suite.

### Hierarchy example

Place the caret in `demo.Dog` and run **Analyze Hierarchy**:

- `demo.Animal` appears under Supertypes.
- `demo.Puppy` appears under Subtypes.
- `Usage.java` contributes both `List<? extends Dog>` and `List<? super Dog>` to the Wildcard section.

### Compile-time example

In `demo.Main`, place the caret in:

```java
choose("Spiega dispatch statico");
```

Run **Explain Static Dispatch**. The resolver selects `choose(String)` rather than `choose(Object)`, and the specificity comparison explains that `String` is more specific than `Object`.

You can also inspect `inherited.speak()` and `overridden.speak()`. Both calls have static receiver type `Animal`, so their compile-time selected declaration is `Animal.speak()`; the Runtime view distinguishes their actual executions.

### Runtime examples

**Inherited implementation:**

1. Set a breakpoint in `Animal.speak()` and debug `demo.Main`.
2. On the first hit, `inherited` holds a `Dog`, which does not override `speak()`.
3. Keep the `Animal.speak` frame selected and run **Capture Dynamic Dispatch**.
4. The result shows actual receiver `demo.Dog`, observed implementation `Animal.speak()`, a lookup path through `Dog` to `Animal`, and the caller's static selection of `Animal.speak()`.

**Observed override:**

1. Set a breakpoint in `Puppy.speak()` and continue debugging `demo.Main` until that method is entered for `overridden.speak()`.
2. Keep the `Puppy.speak` frame selected and run **Capture Dynamic Dispatch**.
3. The result shows actual receiver `demo.Puppy` and observed implementation `Puppy.speak()`, while the reconstructed caller analysis still shows the compile-time selection through `Animal`.

`Main.main` ends with `System.in.read()`, which leaves the process waiting for console input if you want to keep the demo running between debugging steps.

## Implementation notes

### Packages and UI

The user-facing name is **PolyAnalizer**. The current Java package root and plugin ID remain `io.github.ocioman.hierarchytree` and `io.github.ocioman.hierarchy-tree`, respectively.

- `io.github.ocioman.hierarchytree.action` resolves the caret target or current debugger frame and coordinates asynchronous actions.
- `io.github.ocioman.hierarchytree.hierarchy` implements type and wildcard analysis.
- `io.github.ocioman.hierarchytree.dispatch` implements static analysis, runtime PSI reconstruction, immutable debugger snapshots, and JDI capture.
- `io.github.ocioman.hierarchytree.model` defines `AnalysisNode`, an immutable tree record whose children are defensively copied and whose optional `SmartPsiElementPointer` supports navigation after PSI changes.
- `io.github.ocioman.hierarchytree.ui` contains the project-level tool-window service, factory, Swing tabbed panel, tree rendering, and double-click navigation.

`plugin.xml` registers the PolyAnalizer tool window on the right and adds the three actions to `EditorPopupMenu`.

### PSI analysis

- **Hierarchy:** `PsiClass.getSuperTypes()` supplies recursive inheritance relations. `ClassInheritorsSearch` finds subtypes in `ProjectScope`, and `ReferencesSearch` plus enclosing `PsiTypeElement` inspection identifies bounded wildcards. Path sets prevent infinite recursion, results are sorted for stable presentation, and search loops call `ProgressManager.checkCanceled()`.
- **Compile time:** `resolveMethodGenerics()` and `multiResolve(true)` delegate Java resolution to IntelliJ. Same-named methods in the receiver hierarchy supplement the candidate list. `MethodCandidateInfo`, `PsiSubstitutor`, and `TypeConversionUtil` provide applicability, substituted formal types, conversion labels, and specificity annotations. A separate PSI superclass/interface traversal explains inherited declarations.
- **Runtime:** debugger class names are resolved with `JavaPsiFacade` in `GlobalSearchScope.allScope(project)`, including a fallback from JVM nested-class names containing `$` to dotted PSI names. JVM descriptors are reconstructed from erased PSI types so overloaded methods, arrays, primitives, constructors, and return types can be matched. Caller reconstruction searches method calls intersecting the caller's source line and accepts a call only when its resolved method is compatible with the observed method or one of its super-methods/subsignatures.

### Threading and lifecycle

- Hierarchy and static-dispatch action updates run on a background thread. Their analyses use `ReadAction.nonBlocking`, wait for smart mode, expire with the project, coalesce repeated requests, and publish results on the UI thread.
- The hierarchy action obtains its initial PSI target in a read action and retains it with a smart pointer while background work is pending.
- Runtime action availability is evaluated on the EDT because it accesses the current debugger session and frame. JDI data is not read on the EDT: `DebuggerSnapshotCapturer` schedules a `DebuggerContextCommandImpl` on the Java debugger's manager thread, using the selected frame's thread proxy.
- Caller stack-frame positions are obtained asynchronously through `XExecutionStack.computeStackFrames`. The capture records the receiver, declaring type, method name, JVM signature, receiver presence, and caller source location in a `DebuggerSnapshot`.
- Runtime PSI analysis then runs in a non-blocking read action. It expires if the project closes, the session resumes, or the capture's disposable lifetime ends. Session resume and stop listeners invalidate the result and release that lifetime.
- Tool-window updates are dispatched back to the IDE UI thread. Source navigation resolves smart pointers inside a read action before calling `Navigatable.navigate(true)`.
