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

		CoreConstants.INSTANCE.setApiBaseUrl("KENKAI_URL");
		new CFLog.Builder()
			.init(this)
			.disableAutoCollectAppEvents()
			.disableAutoPageTrack()
			.setLifecycleEvent(event)
			.build();
	}
}
