# CF SDK integration for CHT Android

This guide is for developers starting from a clean checkout of
[Medic's `cht-android` repository](https://github.com/medic/cht-android), on the
`master` branch. It adds the Causal Foundry (CF) Android SDK and WebView
instrumentation while keeping event rules updateable from a CDN.

The integration provides:

- native CF SDK initialization and app open/close events;
- CHT login, user identification, and user catalog updates through the WebView bridge;
- updateable `client`, `client_form`, `task`, `report`, and `sync` events;
- a bundled JavaScript fallback for clean-install offline use;
- a validated, persistent CDN cache for later offline use.

Sync emits `start`, `complete`, `error`, and `denied`.

## What to pass to the CHT Android developers

Pass this README together with these two complete files:

| File                         | Destination in the clean CHT Android repository                       |
|------------------------------|-----------------------------------------------------------------------|
| `CfSdkHelper.java`           | `src/main/java/org/medicmobile/webapp/mobile/cf_sdk/CfSdkHelper.java` |
| `cht-web-instrumentation.js` | `src/main/assets/cf_sdk/cht-web-instrumentation.js`                   |

`CfSdkHelper.java` contains the entire native CF integration, including the SDK
adapter, JavaScript bridge, trusted-URL checks, CDN downloader, validation, and
offline cache. Do not split it into another repository or handler class.

The JavaScript file contains all CDN-updateable CHT event rules. Keep it as a
separate asset because the APK needs it as its guaranteed offline fallback.

## Files added or changed

| Action | Path                                                                       |
|--------|----------------------------------------------------------------------------|
| Add    | `src/main/java/org/medicmobile/webapp/mobile/cf_sdk/CfSdkHelper.java`      |
| Add    | `src/main/assets/cf_sdk/cht-web-instrumentation.js`                        |
| Add    | `src/main/java/org/medicmobile/webapp/mobile/MyApplication.java`           |
| Edit   | `build.gradle`                                                             |
| Edit   | `src/main/AndroidManifest.xml`                                             |
| Edit   | `src/main/java/org/medicmobile/webapp/mobile/EmbeddedBrowserActivity.java` |
| Edit   | `src/main/java/org/medicmobile/webapp/mobile/UrlHandler.java`              |


## Before starting

You need:

1. A clean CHT Android checkout that builds successfully.
2. The two handoff files listed above.
3. A CF application key for the Android application.
4. The CF API base hostname assigned to the deployment.

Do not copy an application key from another deployment. Replace
`YOUR_CF_APPLICATION_KEY` below with the key assigned to this application.

## Step 1: Add the Android dependencies

Open the root `build.gradle`. Inside the existing `dependencies { ... }` block,
add:

~~~gradle
implementation 'io.kenkai.android.sdk:core:1.0.8'
implementation 'androidx.lifecycle:lifecycle-process:2.6.2'
~~~

The current Medic `master` branch already has `mavenCentral()`, Java 17, core
library desugaring, and the `desugar_jdk_libs` dependency. Keep those existing
settings unchanged.

Do not add `CF_SDK_WEB_INSTRUMENTATION_URL` to Gradle or `BuildConfig`. The
supplied helper already contains the fixed CDN endpoint.

## Step 2: Add the native CF helper

Create this directory:

~~~text
src/main/java/org/medicmobile/webapp/mobile/cf_sdk/
~~~

Copy the supplied `CfSdkHelper.java` into it.


## Step 3: Add the bundled JavaScript fallback

Create this directory:

~~~text
src/main/assets/cf_sdk/
~~~

Copy the supplied JavaScript file to exactly:

~~~text
src/main/assets/cf_sdk/cht-web-instrumentation.js
~~~

Do not rename or remove it. A missing or invalid bundled file prevents the
instrumentation repository from starting.

## Step 4: Create the application class and configure the CF API host

Create:

~~~text
src/main/java/org/medicmobile/webapp/mobile/MyApplication.java
~~~

Use:

~~~java
package org.medicmobile.webapp.mobile;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.ProcessLifecycleOwner;

import io.kenkai.android.sdk.core.builders.CFLog;
import io.kenkai.android.sdk.core.utils.CoreConstants;

import org.medicmobile.webapp.mobile.cf_sdk.CfSdkHelper;

public class MyApplication extends Application implements LifecycleEventObserver {
    @Override
    public void onCreate() {
        super.onCreate();
        CfSdkHelper.prepareWebInstrumentation(this);
        ProcessLifecycleOwner.get().getLifecycle().addObserver(this);
    }

    @Override
    public void onStateChanged(
            @NonNull LifecycleOwner source,
            @NonNull Lifecycle.Event event
    ) {
        if (event == Lifecycle.Event.ON_START) {
            CfSdkHelper.prepareWebInstrumentation(this);
        }

        CoreConstants.INSTANCE.setApiBaseUrl("https://ai.echis.go.ke/api/v1/");
        new CFLog.Builder()
                .init(this)
                .disableAutoCollectAppEvents()
                .disableAutoPageTrack()
                .setLifecycleEvent(event)
                .build();
    }
}
~~~

Set `CoreConstants.INSTANCE.setApiBaseUrl(...)` to the CF API base URL assigned to
the deployment. In the example above, the hostname is `ai.echis.go.ke`. When a
different CF host is supplied, replace that hostname while retaining `https://`
and the `/api/v1/` path. This is the host value developers are expected to update;
it is separate from the fixed JavaScript CDN URL.

`disableAutoCollectAppEvents()` is required because app open/close are sent
manually in the activity. Leaving automatic app collection enabled can create
duplicate events.

If the project already has an `Application` subclass, merge this initialization
and lifecycle-observer code into it instead of creating a second one. CF
notification action callbacks are optional and are not required for event
tracking.

## Step 5: Register the application and CF key

Open `src/main/AndroidManifest.xml`.

Add `android:name=".MyApplication"` to the existing `<application>` element,
and add the CF metadata inside that element:

~~~xml

<application android:name=".MyApplication" android:label="@string/app_name"
    android:icon="@mipmap/ic_launcher"...>

<meta-data android:name="io.kenkai.android.sdk.APPLICATION_KEY"
android:value="${cfSdkApplicationKey}" />

    <!-- Keep all existing activities, providers, and services here. -->
    </application>
~~~

The clean CHT Android manifest already has the `android.permission.INTERNET`
permission. Do not remove it.

Supply the key at build time instead of committing it. The build accepts values
from an environment variable, a Gradle project property, or the ignored root
`local.properties` file. For example, a local flavor-specific configuration is:

~~~properties
CF_SDK_APPLICATION_KEY_MOH_KENYA_ECHIS=replace-with-your-local-key
~~~

The supported names, from highest to lowest specificity, are:

1. `CF_SDK_APPLICATION_KEY_<BUILD_TYPE>`, such as
   `CF_SDK_APPLICATION_KEY_RELEASE`;
2. `CF_SDK_APPLICATION_KEY_<FLAVOR>`, such as
   `CF_SDK_APPLICATION_KEY_MOH_KENYA_ECHIS`;
3. `CF_SDK_APPLICATION_KEY` as a fallback.

This lets two release variants receive different keys while keeping both values
out of version control. In CI, store each value in the CI secret manager and map
it to the appropriate environment-variable name for that build. Avoid passing a
real key with `-P` on a shared machine because command lines can be recorded in
shell history or visible to other processes.

## Step 6: Connect the helper to the WebView activity

Open:

~~~text
src/main/java/org/medicmobile/webapp/mobile/EmbeddedBrowserActivity.java
~~~

### 6.1 Add the import

~~~java
import org.medicmobile.webapp.mobile.cf_sdk.CfSdkHelper;
~~~

### 6.2 Add app open and close events

Add these methods to `EmbeddedBrowserActivity`, near its other lifecycle methods:

```java

@Override
protected void onResume() {
    CfSdkHelper.logAppOpen();
    super.onResume();
}

@Override
private void onPause() {
    CfSdkHelper.logAppClose();
    super.onPause();
}
```

### 6.3 Register the JavaScript bridge

Find `enableJavascript(WebView container)`. Immediately after the existing
Medic bridge registration:

~~~java
container.addJavascriptInterface(maj, "medicmobile_android");
~~~

add:

~~~java
CfSdkHelper.initWebViewInstrumentation(container);
~~~

The finished part of the method should look like:

~~~java
container.addJavascriptInterface(maj, "medicmobile_android");
CfSdkHelper.initWebViewInstrumentation(container);
~~~

Register the bridge only once and before the first `loadUrl`. Do not change
`enableRemoteChromeDebugging()`; it is unrelated to the CF integration.

## Step 7: Connect the helper to WebView navigation

Open:

~~~text
src/main/java/org/medicmobile/webapp/mobile/UrlHandler.java
~~~

### 7.1 Add the import

~~~java
import org.medicmobile.webapp.mobile.cf_sdk.CfSdkHelper;
~~~

### 7.2 Start instrumentation when a page starts

In `onPageStarted`, add this immediately after the existing trace call:

~~~java
CfSdkHelper.startWebInstrumentationPage(view, this.settings.getAppUrl(), url);
~~~

Keep the existing migration and cookie logic below it unchanged.

### 7.3 Finish instrumentation when a page finishes

In `onPageFinished`, add this immediately after the existing trace call:

~~~java
CfSdkHelper.injectWebInstrumentation(view, this.settings.getAppUrl(), url);
~~~

Keep the existing broadcast logic below it unchanged.

Both calls are required. The start callback chooses and pins one script for the
new trusted CHT document. The finish callback performs the definitive readiness
check and promotes a valid CDN candidate.


## Step 8: Verify the integration on a device

1. Install the APK with a valid CF application key.
2. Open the CHT app and log in.
3. Open a client, submit a form, complete a task, create or update a report, and
   run sync.
4. Confirm that app, login/catalog, client, form, task, report, and sync activity
   reaches the expected CF environment.

Login does not require an additional cookie-driven Android implementation. The
JavaScript resolves the CHT user and facility, then the native helper performs CF
Identify/Login and catalog calls.

## How CDN updates and offline use work

The loader selects scripts in this order:

1. newly downloaded and validated CDN candidate;
2. last-known-good CDN copy stored in app-internal storage;
3. bundled asset from the APK.

The refresh runs in the background during application creation, app foregrounding,
bridge initialization, and page setup. It never blocks WebView startup.

A newly downloaded script is first written as a candidate. It becomes the active
offline copy only after a fresh CHT page runs it and passes the readiness probe.
The active file is written atomically. On a later offline launch, the app uses that
last-known-good remote copy. A clean install that has never downloaded a valid
remote copy uses the bundled asset.

The APK asset itself is read-only and is never overwritten.

The loader deliberately does not replace JavaScript inside an already-running CHT
page. If a download finishes after that page starts, the update is used on the
next full top-level page or app launch. This avoids duplicate navigation listeners
and PouchDB wrappers.

Instrumentation remains runnable offline. Event delivery to the CF backend still
depends on the CF SDK's own network and queue behavior.


## Event ownership

| Events                                                                  | Implementation                                               |
|-------------------------------------------------------------------------|--------------------------------------------------------------|
| App open and close                                                      | Native calls in `EmbeddedBrowserActivity`                    |
| Login/Identify and user catalog                                         | User resolution in JavaScript; CF SDK calls in `CfSdkHelper` |
| Client, client form, task, report, and sync                             | CDN-updateable JavaScript                                    |
| CDN fetch, validation, cache, bridge security, Track payload validation | `CfSdkHelper.java`                                           |

The CDN endpoint must remain unchanged. A bridge-contract change, SDK upgrade, or
new native CF API requires an Android release. Event detection, filtering,
metadata, and mapping can be changed through a compatible JavaScript CDN release.

## Troubleshooting

### `BuildConfig.CF_SDK_WEB_INSTRUMENTATION_URL` cannot be found

An older integration is being used. The clean integration has no Gradle field with
that name. Copy the supplied `CfSdkHelper.java` unchanged; it already contains the
fixed endpoint.

### Bundled instrumentation is unavailable or invalid

Confirm the file exists at exactly:

~~~text
src/main/assets/cf_sdk/cht-web-instrumentation.js
~~~

Check that it starts with the required marker, contains a positive integer
`VERSION`, uses UTF-8, and is smaller than 256 KiB.

### A CDN update is ignored

Check all of the following:

- the URL uses HTTPS and does not redirect;
- `VERSION` is greater than the bundled and highest previously accepted version;
- the first-line marker and bridge contract are unchanged;
- the CDN validator changed or its cache was purged;
- a fresh top-level page or app launch occurred after the download.

### Events are duplicated

Confirm `disableAutoCollectAppEvents()` and `disableAutoPageTrack()` are still
present, the CF bridge is registered only once, and no second cookie-based Identify
or app-event implementation was added.

### CF does not initialize

Confirm:

- `android:name=".MyApplication"` is on the manifest `<application>`;
- the CF application-key metadata is inside that element;
- the key belongs to the intended CF environment;
- both Gradle dependencies were added.

### Login or catalog is missing

Confirm the WebView bridge is registered before the first page load, the CHT
`userCtx` cookie is available, the local user document can be read, and the
instrumentation debug logs show successful user resolution.

## Final handoff checklist

- [ ] CF SDK and lifecycle dependencies added.
- [ ] Complete `CfSdkHelper.java` copied to the exact package path.
- [ ] Bundled JavaScript copied to the exact asset path.
- [ ] Fixed CDN URL in `CfSdkHelper.java` left unchanged.
- [ ] `MyApplication` created and registered.
- [ ] Deployment-specific CF API base hostname configured in `MyApplication`.
- [ ] Deployment-specific CF application key configured.
- [ ] WebView bridge registered once.
- [ ] Page-start and page-finish callbacks connected.
- [ ] JavaScript syntax, Android compilation, and Checkstyle pass.
- [ ] Online CDN update and later offline reuse verified.
- [ ] No production key or sensitive CHT data added to the handoff documentation.
